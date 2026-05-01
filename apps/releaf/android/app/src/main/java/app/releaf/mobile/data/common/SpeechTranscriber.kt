/*
 * SpeechTranscriber.kt
 *
 * On-device speech-to-text for voice notes. Runs *after* the recorder
 * stops — mirrors the iOS path, which uses `SFSpeechURLRecognitionRequest`
 * on the finalized file.
 *
 * Two back-ends, dispatched at call time:
 *
 *   1. ML Kit GenAI Speech Recognition (preferred, API 31+). Uses
 *      AICore / Gemini Nano on supported devices. No model bundle in
 *      the APK — the weights ship with AICore. Alpha API (1.0.0-alpha1)
 *      as of 2026, so the dispatch gracefully catches any surprise and
 *      falls through to sherpa-onnx.
 *
 *   2. sherpa-onnx (fallback), whisper-tiny.en int8. ONNX Runtime +
 *      sherpa-onnx JNI ship as ~34MB of native libs per ABI (App Bundle
 *      splits per-device). The model itself (~98MB) is downloaded on
 *      first use into the app's files dir — same UX as the old Vosk
 *      path. Works on API 26+ regardless of AICore availability.
 *      File-based via `MediaExtractor` + `MediaCodec` decode, then the
 *      int16 PCM is normalized to float[-1,1] for sherpa.
 *      Replaced Vosk 0.3.47 in April 2026: Vosk's prebuilt .so has
 *      4 KB-aligned LOAD segments, which Google Play rejects for
 *      Android 15+ (16 KB page size). Upstream has been stale for
 *      >1 year with no fix in sight.
 *
 * History (for the next person who touches this): we originally tried
 * concurrent recognition with `android.speech.SpeechRecognizer`
 * alongside `MediaRecorder`. The platform recognizer has no file-based
 * API, so it had to observe the mic live — and Android's audio policy
 * never lets two mic clients share input on real devices. The
 * recognizer saw silence and returned NO_MATCH every time. File-based
 * transcription sidesteps the problem entirely.
 *
 * Public API:
 *   - `SpeechTranscriber.transcribe(context, fileUri)`: suspend fn,
 *     one-shot. Returns `TranscribeResult.Success(text)` or
 *     `TranscribeResult.Failure(reason)` — reason is a user-facing
 *     string the UI drops into the "unavailable" card placeholder.
 *
 * Threading: all recognition work happens on the IO dispatcher.
 * Callers drive this off a coroutine and show a pending indicator
 * while they await.
 */

package app.releaf.mobile.data.common

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

sealed interface TranscribeResult {
    /** `source` is one of [SpeechTranscriber.BACKEND_MLKIT] or
     *  [SpeechTranscriber.BACKEND_SHERPA] — the UI uses it to label
     *  the "try again with the other engine" retry affordance. */
    data class Success(val text: String, val source: String) : TranscribeResult
    data class Failure(val reason: String) : TranscribeResult
}

object SpeechTranscriber {

    private const val TAG = "SpeechTranscriber"

    /** Stable string ids for the two back-ends. Stored on the
     *  attachment so old transcripts survive an app restart with the
     *  same engine-attribution the UI shows. */
    const val BACKEND_MLKIT = "mlkit"
    const val BACKEND_SHERPA = "sherpa"

    /**
     * Run speech-to-text on the audio at `fileUri`.
     *
     * `preferredBackend` lets the caller force one engine:
     *   - null → default priority: ML Kit first, sherpa-onnx as fallback
     *     (matches the behaviour users get from the normal Transcribe
     *     tap).
     *   - BACKEND_MLKIT → ML Kit only, no sherpa fallback. Fails hard
     *     if the device doesn't support it.
     *   - BACKEND_SHERPA → sherpa-onnx only.
     *
     * The Retry affordance on the card passes whichever backend is the
     * *opposite* of what produced the current transcript, so the user
     * gets a genuinely different result on each tap.
     */
    suspend fun transcribe(
        context: Context,
        fileUri: String,
        preferredBackend: String? = null,
    ): TranscribeResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "transcribe: preferredBackend=${preferredBackend ?: "default"} uri=${Uri.parse(fileUri).lastPathSegment}")
        val file = runCatching { File(Uri.parse(fileUri).path ?: "") }.getOrNull()
        if (file == null || !file.exists() || file.length() == 0L) {
            return@withContext TranscribeResult.Failure("Audio file missing")
        }

        when (preferredBackend) {
            BACKEND_MLKIT -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    return@withContext TranscribeResult.Failure(
                        "Gemini Nano requires Android 12 or later"
                    )
                }
                val mlKit = runCatching { transcribeWithMlKit(context, file) }
                    .onFailure { Log.w(TAG, "ML Kit forced path threw", it) }
                    .getOrNull()
                return@withContext mlKit ?: TranscribeResult.Failure(
                    "Gemini Nano unavailable on this device"
                )
            }
            BACKEND_SHERPA -> {
                return@withContext transcribeWithSherpa(context, file)
            }
        }

        // Default priority: sherpa-onnx / Whisper first. Whisper's
        // accuracy on informal dictation beats Gemini Nano's in our
        // testing, which matters more than the one-time 112MB model
        // download (cached after first transcription). ML Kit stays as
        // the retry back-end — if Whisper's take is off, the user
        // taps "Try again" and we flip to ML Kit for a second opinion.
        val sherpa = runCatching { transcribeWithSherpa(context, file) }
            .onFailure { Log.w(TAG, "sherpa-onnx path threw, falling back to ML Kit", it) }
            .getOrNull()
        if (sherpa is TranscribeResult.Success) return@withContext sherpa

        // Sherpa failed (model download, recognizer crash, etc.). Try
        // ML Kit on supported devices before giving up entirely — we'd
        // rather the user get *some* transcript than see a dead-end
        // error from a back-end that happened to be unavailable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mlKit = runCatching { transcribeWithMlKit(context, file) }
                .onFailure { Log.w(TAG, "ML Kit fallback path threw", it) }
                .getOrNull()
            if (mlKit != null) return@withContext mlKit
        }

        sherpa ?: TranscribeResult.Failure("Transcription unavailable")
    }

    // ========================= ML Kit GenAI ======================
    //
    // Preferred path on API 31+ devices with AICore. Smaller footprint
    // (no model bundle), better quality (Gemini Nano), and official
    // Google support. Returns null if the feature isn't available on
    // this device so the caller can fall back to sherpa-onnx.

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun transcribeWithMlKit(context: Context, file: File): TranscribeResult? {
        Log.d(TAG, "ML Kit transcribe starting: file=${file.name} size=${file.length()}B")
        val options = SpeechRecognizerOptions.Builder().apply {
            // en-US is the broadest-trained Basic-mode locale and works
            // well for en-IN / en-GB speakers too. Swap to
            // `Locale.getDefault()` once we start translating the rest
            // of the UI.
            locale = Locale.US
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }.build()
        val recognizer = SpeechRecognition.getClient(options)

        return try {
            val status = recognizer.checkStatus()
            Log.d(TAG, "ML Kit checkStatus=$status (${featureStatusName(status)})")
            when (status) {
                FeatureStatus.UNAVAILABLE -> {
                    // Device doesn't have AICore / Gemini Nano — not an
                    // error, just "try the other back-end".
                    return null
                }
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    val err = awaitModelDownload(recognizer)
                    if (err != null) {
                        // Download path didn't pan out — could be network,
                        // AICore not fully provisioned, or Gemini Nano
                        // not available for our locale on this device.
                        // Log the real reason and fall through to sherpa-onnx
                        // instead of leaving the user with a dead-end
                        // error message.
                        Log.w(TAG, "ML Kit model download failed: $err — falling back to sherpa-onnx")
                        return null
                    }
                }
                // AVAILABLE — proceed to recognition.
            }

            // Decode the M4A to raw 16-bit PCM ourselves and hand ML Kit
            // a standard WAV file rather than the AAC-in-MP4 container.
            //
            // Empirically AICore's VAD rejects our .m4a with
            // `ERROR_TYPE_NO_SPEECH_DETECTED` even when the audio is
            // clearly speech (verified via `WaveformSamples.extract`
            // returning full-range amplitudes). Fronting it with a WAV
            // whose format descriptor is explicit — sample rate,
            // channel count, bit depth — removes any ambiguity about
            // what the recognizer's internal decoder thinks we're
            // feeding it.
            val pcm = runCatching { decodeToPcm(file) }
                .onFailure { Log.w(TAG, "ML Kit: decodeToPcm failed", it) }
                .getOrNull()
            if (pcm == null || pcm.isEmpty()) {
                Log.w(TAG, "ML Kit: empty PCM, falling back")
                null
            } else {
                val wavFile = File.createTempFile("mlkit-", ".wav", context.cacheDir)
                try {
                    writeWav(
                        pcm = pcm,
                        sampleRate = SAMPLE_RATE_HZ,
                        channels = 1,
                        bitsPerSample = 16,
                        out = wavFile,
                    )
                    Log.d(TAG, "ML Kit wav written: ${wavFile.length()}B")

                    // ML Kit's `AudioSource` just wraps the PFD
                    // (decompiled class: `private final
                    // ParcelFileDescriptor zza`) and `recognizer.close()`
                    // doesn't touch our PFD — `.use` guarantees it
                    // closes even if the Flow throws.
                    ParcelFileDescriptor.open(
                        wavFile, ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { pfd ->
                        runRecognition(recognizer, pfd)
                    }
                } finally {
                    wavFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit transcription threw", e)
            null // fall through to sherpa-onnx
        } finally {
            runCatching { recognizer.close() }
        }
    }

    /**
     * Inner body of `transcribeWithMlKit` — isolated from the
     * model-download / PFD-lifecycle scaffolding so the Flow handling
     * stays readable. Collects the response Flow, keeps any text that
     * came in, and returns:
     *   - `Success(text)` — any non-empty transcription, even if a
     *     trailing ErrorResponse fired afterward (stream closed
     *     abruptly cases).
     *   - `null`          — the error is recoverable (AICore
     *     unavailable / quota / etc.), caller should fall through to
     *     the sherpa-onnx back-end.
     *   - `Failure(reason)` — a legitimate dead-end: "No speech
     *     detected", "Recording too short", etc.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun runRecognition(
        recognizer: com.google.mlkit.genai.speechrecognition.SpeechRecognizer,
        pfd: ParcelFileDescriptor,
    ): TranscribeResult? {
        val request = SpeechRecognizerRequest.Builder()
            .apply { audioSource = AudioSource.fromPfd(pfd) }
            .build()

        val buffer = StringBuilder()
        var errorReason: String? = null
        var errorAllowsFallback = false

        recognizer.startRecognition(request).collect { response ->
            when (response) {
                is SpeechRecognizerResponse.FinalTextResponse -> {
                    if (response.text.isNotBlank()) {
                        if (buffer.isNotEmpty()) buffer.append(' ')
                        buffer.append(response.text.trim())
                    }
                }
                is SpeechRecognizerResponse.ErrorResponse -> {
                    val mapped = mlKitFailureReason(response.e)
                    errorReason = mapped.first
                    errorAllowsFallback = mapped.second
                    Log.w(
                        TAG,
                        "ML Kit recognition error: code=${response.e.errorCode} msg=${response.e.message}",
                    )
                }
                // Partials flicker — skip. Completed is just an
                // end-of-stream marker; the Flow terminates after.
                else -> Unit
            }
        }

        // Any text we collected wins over a trailing error — some ML
        // Kit failure paths fire both a final text chunk *and* an
        // error (stream closed abruptly), and we'd rather return the
        // partial transcription than throw it away.
        val text = buffer.toString().trim()
        return when {
            text.isNotEmpty() -> {
                Log.d(TAG, "ML Kit transcribe: text='${text.take(80)}'")
                TranscribeResult.Success(text, BACKEND_MLKIT)
            }
            errorReason != null && errorAllowsFallback -> {
                Log.d(TAG, "ML Kit error is recoverable, falling back to sherpa-onnx")
                null
            }
            errorReason != null -> TranscribeResult.Failure(errorReason!!)
            else -> TranscribeResult.Failure("No speech detected")
        }
    }

    /**
     * Walk the `download` Flow until it emits `DownloadCompleted` (or
     * errors). Returns null on success, or a human-readable failure
     * reason lifted off the `GenAiException` that `DownloadFailed`
     * carries. The Flow emits `DownloadStarted` → `DownloadProgress` →
     * terminates on `DownloadCompleted` / `DownloadFailed`.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun awaitModelDownload(
        recognizer: com.google.mlkit.genai.speechrecognition.SpeechRecognizer,
    ): String? {
        var failure: String? = null
        var completed = false
        recognizer.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadStarted ->
                    Log.d(TAG, "ML Kit download started")
                is DownloadStatus.DownloadProgress ->
                    Log.d(TAG, "ML Kit download progress: $status")
                is DownloadStatus.DownloadCompleted -> {
                    Log.d(TAG, "ML Kit download completed")
                    completed = true
                }
                is DownloadStatus.DownloadFailed -> {
                    val ex = status.e
                    failure = "${ex.errorCode}: ${ex.message ?: "no message"}"
                    Log.w(TAG, "ML Kit download failed — $failure", ex)
                }
            }
        }
        return when {
            failure != null -> failure
            !completed -> "download flow ended without completion signal"
            else -> null
        }
    }

    private fun featureStatusName(status: Int): String = when (status) {
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        else -> "UNKNOWN($status)"
    }

    /**
     * Map a `GenAiException` into (user-facing reason, should-fallback).
     *
     * AICore routes a handful of cases through a generic
     * `RESPONSE_PROCESSING_ERROR` code with the actual cause stashed in
     * the message string (e.g. "ERROR_TYPE_NO_SPEECH_DETECTED"). We
     * sniff the message for those so the card shows a clean "No speech
     * detected" instead of the raw "Speech recognition engine is closed
     * due to internal error: …" text the user shouldn't have to parse.
     *
     * The boolean controls whether the caller should fall through to
     * Vosk — true for engine-level failures that Vosk might recover
     * from, false for legitimate "nothing was said" cases where the
     * audio itself is the problem and a second back-end would waste
     * the user's time.
     */
    private fun mlKitFailureReason(e: GenAiException): Pair<String, Boolean> {
        val msg = e.message.orEmpty()
        val upper = msg.uppercase()

        // Message-sniffed cases — these override the error code because
        // AICore uses generic codes for many of them.
        when {
            "NO_SPEECH" in upper || "ERROR_TYPE_NO_MATCH" in upper ->
                return "No speech detected" to false
            "ERROR_AUDIO" in upper ->
                return "Audio read error" to true
            "ERROR_LANGUAGE_UNAVAILABLE" in upper || "LANGUAGE_UNAVAILABLE" in upper ->
                return "Language model not installed" to true
        }

        return when (e.errorCode) {
            GenAiException.ErrorCode.BUSY ->
                "Recognizer busy — try again" to false
            GenAiException.ErrorCode.CANCELLED ->
                "Cancelled" to false
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE ->
                "Not enough storage to run transcription" to false
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
                "Transcription is blocked in the background" to false
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE ->
                "System update needed for on-device transcription" to false
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
                "Battery-use quota reached — try later" to false
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE ->
                "On-device recognizer unavailable" to true
            GenAiException.ErrorCode.REQUEST_TOO_SMALL ->
                "Recording too short" to false
            GenAiException.ErrorCode.REQUEST_TOO_LARGE ->
                "Recording too long for on-device recognition" to true
            else ->
                "Recognition error (code ${e.errorCode})" to true
        }
    }

    // ========================= sherpa-onnx =======================
    //
    // Fallback path. Used on API 26-30 (ML Kit requires 31+) and on
    // API 31+ devices without AICore.

    /**
     * whisper-tiny.en, int8 quantized. The release tarball is ~112MB
     * compressed; unpacks to ~98MB on disk (encoder 12MB + decoder
     * 86MB + tokens ~800KB). Smallest English whisper variant — swap
     * to whisper-base.en (~60MB → ~170MB on disk) if accuracy turns
     * out to be a real complaint on longer voice notes.
     */
    private const val SHERPA_MODEL_NAME = "sherpa-onnx-whisper-tiny.en"
    private const val SHERPA_MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-whisper-tiny.en.tar.bz2"
    private const val SHERPA_ENCODER = "tiny.en-encoder.int8.onnx"
    private const val SHERPA_DECODER = "tiny.en-decoder.int8.onnx"
    private const val SHERPA_TOKENS  = "tiny.en-tokens.txt"

    /** 16kHz mono matches both `MediaRecorder` and whisper's input
     *  contract — decoded PCM goes straight in without resampling. */
    private const val SAMPLE_RATE_HZ = 16_000

    @Volatile private var cachedSherpaRecognizer: OfflineRecognizer? = null
    private val sherpaRecognizerLock = Any()

    private suspend fun transcribeWithSherpa(context: Context, file: File): TranscribeResult {
        val recognizer = runCatching { ensureSherpaRecognizer(context) }
            .onFailure { Log.e(TAG, "sherpa-onnx model load failed", it) }
            .getOrNull()
            ?: return TranscribeResult.Failure(
                "Could not download speech model — check network"
            )

        val pcm = runCatching { decodeToPcm(file) }
            .onFailure { Log.e(TAG, "decode failed", it) }
            .getOrNull()
            ?: return TranscribeResult.Failure("Could not decode audio file")

        if (pcm.isEmpty()) {
            return TranscribeResult.Failure("Audio file was empty")
        }

        val samples = pcmBytesToFloats(pcm)
        val text = runCatching { runSherpaRecognizer(recognizer, samples) }
            .onFailure { Log.e(TAG, "sherpa-onnx recognizer failed", it) }
            .getOrNull()
            ?: return TranscribeResult.Failure("Transcription failed")

        Log.d(TAG, "sherpa transcribe: pcm=${pcm.size}B, text='${text.take(80)}'")
        return if (text.isBlank()) TranscribeResult.Failure("No speech detected")
        else TranscribeResult.Success(text, BACKEND_SHERPA)
    }

    private suspend fun ensureSherpaRecognizer(context: Context): OfflineRecognizer {
        cachedSherpaRecognizer?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(sherpaRecognizerLock) {
                cachedSherpaRecognizer?.let { return@withContext it }
                val modelDir = File(context.filesDir, SHERPA_MODEL_NAME)
                if (!isSherpaModelPresent(modelDir)) {
                    downloadAndExtractSherpaModel(modelDir)
                }
                // null assetManager → sherpa's `newFromFile` path, which
                // takes absolute paths on disk. (The assetManager path
                // is for models bundled in `app/src/main/assets/`.)
                val recognizer = OfflineRecognizer(
                    assetManager = null,
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(
                            sampleRate = SAMPLE_RATE_HZ,
                            featureDim = 80,
                        ),
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = File(modelDir, SHERPA_ENCODER).absolutePath,
                                decoder = File(modelDir, SHERPA_DECODER).absolutePath,
                            ),
                            tokens = File(modelDir, SHERPA_TOKENS).absolutePath,
                            modelType = "whisper",
                            numThreads = 2,
                        ),
                    ),
                )
                cachedSherpaRecognizer = recognizer
                recognizer
            }
        }
    }

    private fun isSherpaModelPresent(modelDir: File): Boolean {
        if (!modelDir.isDirectory) return false
        // Encoder is the biggest file; if it's here the bundle finished
        // extracting. Tokens is tiny and still a useful sanity check.
        return File(modelDir, SHERPA_ENCODER).exists() &&
            File(modelDir, SHERPA_TOKENS).exists()
    }

    private fun downloadAndExtractSherpaModel(modelDir: File) {
        Log.d(TAG, "sherpa model: downloading…")
        if (modelDir.exists()) modelDir.deleteRecursively()
        modelDir.mkdirs()

        val conn = (URL(SHERPA_MODEL_URL).openConnection() as HttpURLConnection).apply {
            // The release asset is ~112MB and served from GitHub's CDN
            // via a 302 to Azure blob storage — allow the redirect and
            // give it room to breathe on a slow network.
            connectTimeout = 30_000
            readTimeout = 300_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("sherpa model HTTP ${conn.responseCode}")
            }
            extractModelTarBz2(conn.inputStream.buffered(), modelDir)
        } catch (e: Exception) {
            // Leave nothing behind on failure — a half-extracted tree
            // would make `isSherpaModelPresent` return true next run
            // and then sherpa would crash trying to load it.
            modelDir.deleteRecursively()
            throw e
        } finally {
            runCatching { conn.disconnect() }
        }
        Log.d(TAG, "sherpa model: extracted to ${modelDir.absolutePath}")
    }

    /**
     * The release tarball nests everything under
     * `sherpa-onnx-whisper-tiny.en/<file>`; we flatten by basename so
     * the files land directly in `modelDir`. Only extracts the
     * encoder, decoder, and tokens — the bundled `test_wavs/` dir
     * would cost another ~200KB on disk for no reason.
     */
    private fun extractModelTarBz2(input: java.io.InputStream, modelDir: File) {
        val wanted = setOf(SHERPA_ENCODER, SHERPA_DECODER, SHERPA_TOKENS)
        TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tis ->
            var entry = tis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val base = entry.name.substringAfterLast('/')
                    if (base in wanted) {
                        File(modelDir, base).outputStream().use { out ->
                            tis.copyTo(out)
                        }
                    }
                }
                entry = tis.nextEntry
            }
        }
    }

    /**
     * Write a little-endian signed-int PCM buffer as a standard RIFF
     * WAV file. 44-byte header + raw PCM body — nothing fancy, just
     * enough that ML Kit's file-format detector sees an unambiguous
     * `WAVE` signature with explicit sample rate / channel / bit-depth
     * metadata (as opposed to the AAC-in-MP4 we record to, which the
     * recognizer's VAD rejects out of hand).
     *
     * Header layout matches the canonical
     * http://soundfile.sapp.org/doc/WaveFormat/ reference.
     */
    private fun writeWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        out: File,
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcm.size
        val riffChunkSize = 36 + dataSize

        out.outputStream().buffered().use { os ->
            fun writeInt32(v: Int) {
                os.write(v and 0xFF)
                os.write((v ushr 8) and 0xFF)
                os.write((v ushr 16) and 0xFF)
                os.write((v ushr 24) and 0xFF)
            }
            fun writeInt16(v: Int) {
                os.write(v and 0xFF)
                os.write((v ushr 8) and 0xFF)
            }

            // RIFF chunk descriptor
            os.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeInt32(riffChunkSize)
            os.write("WAVE".toByteArray(Charsets.US_ASCII))

            // `fmt ` subchunk (16 bytes, PCM)
            os.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeInt32(16)                  // subchunk1Size
            writeInt16(1)                   // audioFormat = PCM
            writeInt16(channels)
            writeInt32(sampleRate)
            writeInt32(byteRate)
            writeInt16(blockAlign.toInt())
            writeInt16(bitsPerSample)

            // `data` subchunk
            os.write("data".toByteArray(Charsets.US_ASCII))
            writeInt32(dataSize)
            os.write(pcm)
        }
    }

    private fun decodeToPcm(file: File): ByteArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var audioTrackIdx = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIdx = i
                inputFormat = fmt
                break
            }
        }
        if (audioTrackIdx < 0 || inputFormat == null) {
            extractor.release()
            throw IOException("No audio track in file")
        }
        extractor.selectTrack(audioTrackIdx)

        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val output = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val timeoutUs = 10_000L

        try {
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(
                                inIdx, 0, sampleSize,
                                extractor.sampleTime, 0,
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.get(chunk)
                            output.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        return output.toByteArray()
    }

    /**
     * sherpa-onnx's `OfflineStream.acceptWaveform` takes float PCM in
     * [-1, 1]; `MediaCodec` hands us little-endian int16 bytes. Divide
     * by 32768 (Short.MIN_VALUE magnitude) to normalize.
     */
    private fun pcmBytesToFloats(pcm: ByteArray): FloatArray {
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(shorts.remaining())
        for (i in samples.indices) {
            samples[i] = shorts.get().toInt() / 32768.0f
        }
        return samples
    }

    private fun runSherpaRecognizer(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
    ): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }
}
