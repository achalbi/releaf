/*
 * SpeechTranscriber.kt
 *
 * On-device speech-to-text for voice notes. Runs *after* the recorder
 * stops — mirrors the iOS path, which uses SFSpeechURLRecognitionRequest
 * on the finalized file.
 *
 * Two back-ends, dispatched at call time:
 *
 *   1. sherpa-onnx (preferred), whisper-small int8 multilingual.
 *      ONNX Runtime + sherpa-onnx JNI ship as ~34 MB of native libs
 *      per ABI. The model itself (~500 MB) downloads on first use
 *      into the app's files dir. Multilingual: supports English +
 *      every Whisper target language (Hindi, Kannada, Tamil, …) —
 *      chosen at call time from the user's
 *      `profile_settings.transcription_languages` allowlist.
 *      `small` is a deliberate step up from `base` (~165 MB) — base
 *      produced empty transcripts on Kannada audio because Whisper's
 *      lower-resource Indian-language support degrades quickly at
 *      smaller model sizes. Trade-off: ~3× inference time and a
 *      heavier first-run download.
 *
 *   2. ML Kit GenAI Speech Recognition (fallback, API 31+). Uses
 *      AICore / Gemini Nano on supported devices. No model bundle in
 *      the APK. English-only in practice as of this writing.
 *
 * Language picking: callers pass `userId` so the transcriber can read
 * the allowlist. With exactly one picked language we hand it directly
 * to Whisper as a hint (fastest, no LID needed). With ≥ 2 picked, we
 * pass `language = ""` so Whisper auto-detects from the audio and
 * reports the picked language in `result.lang`; if that lang lands
 * outside the allowlist we keep the transcript anyway and log the
 * mismatch (re-transcribing in the user's primary on miss is a
 * deferred enhancement).
 *
 * Migration: on upgrade we lazily wipe both the legacy `tiny.en`
 * (English-only, ~98 MB) and `whisper-base` (multilingual but too
 * weak for Indian languages, ~165 MB) model dirs on first
 * transcribe — reclaims ~263 MB combined for users upgrading from
 * either prior build.
 */

package app.quickink.mobile.data.voicenote

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

sealed interface TranscribeResult {
    data class Success(val text: String, val source: String) : TranscribeResult
    data class Failure(val reason: String) : TranscribeResult
}

object SpeechTranscriber {

    private const val TAG = "SpeechTranscriber"

    const val BACKEND_MLKIT = "mlkit"
    const val BACKEND_SHERPA = "sherpa"

    /**
     * Inspect the app's files directory and return the subset of
     * Whisper variants that are already extracted on disk. Drives
     * the per-item model picker on voice-note cards: the UI only
     * offers variants the user can pick without triggering a fresh
     * download. Cheap — three `File.exists()` checks per variant.
     */
    fun availableModels(context: Context): List<WhisperModel> {
        val filesDir = context.filesDir
        return WhisperModel.values().filter { model ->
            val dir = File(filesDir, model.sherpaDirName)
            dir.isDirectory &&
                File(dir, model.encoderFile).exists() &&
                File(dir, model.tokensFile).exists()
        }
    }

    suspend fun transcribe(
        context: Context,
        fileUri: String,
        userId: String? = null,
        preferredBackend: String? = null,
        /**
         * Optional per-call override for the Whisper variant. When
         * non-null, this variant is used instead of the global
         * `SettingsPreferences.transcriptionModel` pick. Wired into
         * the voice-note card's "Re-transcribe with…" picker so a
         * user can try a different model on a specific clip without
         * changing their default. Null = honor the global pref.
         */
        modelOverride: WhisperModel? = null,
    ): TranscribeResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "transcribe: preferredBackend=${preferredBackend ?: "default"} uri=${Uri.parse(fileUri).lastPathSegment}")
        val file = runCatching { File(Uri.parse(fileUri).path ?: "") }.getOrNull()
        if (file == null || !file.exists() || file.length() == 0L) {
            return@withContext TranscribeResult.Failure("Audio file missing")
        }

        // Lazy one-time cleanup of legacy model dirs. Reclaims
        // ~98 MB (tiny.en) and ~165 MB (whisper-base) for users
        // upgrading through the model-size progression. Cheap
        // no-op on fresh installs.
        runCatching { deleteLegacyModelDirsIfPresent(context) }

        val allowlist = resolveAllowlistCodes(context, userId)

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
                return@withContext transcribeWithSherpa(context, file, allowlist, modelOverride)
            }
        }

        val sherpa = runCatching { transcribeWithSherpa(context, file, allowlist, modelOverride) }
            .onFailure { Log.w(TAG, "sherpa-onnx path threw, falling back to ML Kit", it) }
            .getOrNull()
        if (sherpa is TranscribeResult.Success) return@withContext sherpa

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mlKit = runCatching { transcribeWithMlKit(context, file) }
                .onFailure { Log.w(TAG, "ML Kit fallback path threw", it) }
                .getOrNull()
            if (mlKit != null) return@withContext mlKit
        }

        sherpa ?: TranscribeResult.Failure("Transcription unavailable")
    }

    /**
     * Read the user's transcription-language allowlist (comma-
     * separated codes on `profile_settings.transcription_languages`)
     * and decode to a list of ISO 639-1 codes. Falls back to
     * [TranscriptionLanguages.defaultAllowlist] when null/empty —
     * matches the default the picker UI shows pre-checked.
     */
    private suspend fun resolveAllowlistCodes(
        context: Context,
        userId: String?,
    ): List<String> {
        if (userId.isNullOrEmpty()) {
            return TranscriptionLanguages.defaultAllowlist().map { it.code }
        }
        val stored = runCatching {
            val app = context.applicationContext as? app.quickink.mobile.QuickInkApp
                ?: return@runCatching null
            app.database.profileSettingsDao().findByUser(userId)?.transcriptionLanguages
        }.getOrNull()
        val parsed = TranscriptionLanguages.parse(stored)
        return if (parsed.isNotEmpty()) {
            parsed.map { it.code }
        } else {
            TranscriptionLanguages.defaultAllowlist().map { it.code }
        }
    }

    private fun deleteLegacyModelDirsIfPresent(context: Context) {
        // The English-only `whisper-tiny.en` dir from pre-multilingual
        // builds is the only truly-legacy artifact we eagerly wipe.
        // All other Whisper dirs (`whisper-tiny`, `-base`, `-small`,
        // `-medium`) are legitimate user picks via Settings →
        // Transcription → Model, so we leave them on disk — even
        // when the user has switched away from them, so a flip back
        // doesn't re-download. A "Clear cached models" action in
        // Settings can land later if disk pressure becomes a real
        // issue (medium + small + base + tiny ≈ 2.3 GB combined).
        val legacy = File(context.filesDir, "sherpa-onnx-whisper-tiny.en")
        if (legacy.exists()) {
            Log.d(TAG, "Removing legacy English-only model dir 'sherpa-onnx-whisper-tiny.en' to reclaim disk")
            legacy.deleteRecursively()
        }
        // Sweep `.staging` dirs from any previous extract that was
        // killed before its atomic rename. These can't be reused
        // (mid-extract state) and would otherwise pile up disk.
        context.filesDir.listFiles { _, name -> name.endsWith(".staging") }
            ?.forEach { dir ->
                Log.i(TAG, "Sweeping leftover extract staging dir: ${dir.name}")
                dir.deleteRecursively()
            }
    }

    // ========================= ML Kit GenAI ======================

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun transcribeWithMlKit(context: Context, file: File): TranscribeResult? {
        Log.d(TAG, "ML Kit transcribe starting: file=${file.name} size=${file.length()}B")
        val options = SpeechRecognizerOptions.Builder().apply {
            locale = Locale.US
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }.build()
        val recognizer = SpeechRecognition.getClient(options)

        return try {
            val status = recognizer.checkStatus()
            Log.d(TAG, "ML Kit checkStatus=$status (${featureStatusName(status)})")
            when (status) {
                FeatureStatus.UNAVAILABLE -> {
                    return null
                }
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    val err = awaitModelDownload(recognizer)
                    if (err != null) {
                        Log.w(TAG, "ML Kit model download failed: $err — falling back to sherpa-onnx")
                        return null
                    }
                }
            }

            val decoded = runCatching { decodeToPcm(file) }
                .onFailure { Log.w(TAG, "ML Kit: decodeToPcm failed", it) }
                .getOrNull()
            if (decoded == null || decoded.pcm.isEmpty()) {
                Log.w(TAG, "ML Kit: empty PCM, falling back")
                null
            } else {
                val wavFile = File.createTempFile("mlkit-", ".wav", context.cacheDir)
                try {
                    // Write the WAV header with the SOURCE audio's
                    // actual sample rate + channel count rather
                    // than the hardcoded 16kHz mono — otherwise a
                    // 44.1kHz stereo recording would be read by
                    // ML Kit at 2.75× speed with mixed channels.
                    writeWav(
                        pcm           = decoded.pcm,
                        sampleRate    = decoded.sampleRate,
                        channels      = decoded.channelCount,
                        bitsPerSample = 16,
                        out           = wavFile,
                    )

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
            null
        } finally {
            runCatching { recognizer.close() }
        }
    }

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
                else -> Unit
            }
        }

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

    private fun mlKitFailureReason(e: GenAiException): Pair<String, Boolean> {
        val msg = e.message.orEmpty()
        val upper = msg.uppercase()

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

    // The active Whisper variant (tiny / base / small / medium) is
    // chosen at runtime by the user via Settings → Transcription →
    // Model. See [WhisperModel] for the URLs + filenames; default
    // is [WhisperModel.DEFAULT] (Small) for fresh installs.
    private const val SAMPLE_RATE_HZ = 16_000

    /// The OfflineRecognizer's `language` field is read at construction
    /// time — there's no per-stream override — so we cache one
    /// recognizer per (model variant, language hint) pair and rebuild
    /// when either changes. Language-hint values:
    ///   - `""` (empty) — Whisper auto-detects. Returned in
    ///     `OfflineRecognizerResult.lang`.
    ///   - ISO 639-1 code (e.g. "hi", "kn") — Whisper transcribes
    ///     as that language. Faster + more accurate when the user
    ///     only speaks one language.
    @Volatile private var cachedSherpaRecognizer: OfflineRecognizer? = null
    @Volatile private var cachedSherpaLanguageHint: String? = null
    @Volatile private var cachedSherpaModelId: String? = null
    private val sherpaRecognizerLock = Any()

    // Model fetches run as foreground-service WorkManager jobs (see
    // [WhisperModelDownloadWorker]) — survives navigation away, the
    // app being dismissed, and process pressure. The transcribe
    // call awaits the work's WorkInfo terminal state instead of
    // owning a coroutine of its own.

    private suspend fun transcribeWithSherpa(
        context: Context,
        file: File,
        allowlistCodes: List<String>,
        modelOverride: WhisperModel?,
    ): TranscribeResult {
        val model = modelOverride ?: resolvePickedModel(context)

        // Decode the audio once — both transcription passes (the
        // initial auto-detect and the potential allowlist-rescue
        // re-run) reuse the same PCM samples.
        val decoded = runCatching { decodeToPcm(file) }
            .onFailure { Log.e(TAG, "decode failed", it) }
            .getOrNull()
            ?: return TranscribeResult.Failure("Could not decode audio file")

        if (decoded.pcm.isEmpty()) {
            return TranscribeResult.Failure("Audio file was empty")
        }

        val samples = pcmBytesToMonoFloats(decoded.pcm, decoded.channelCount)

        // One language picked → hand it to Whisper directly (fastest,
        // skips LID). Multiple → auto-detect; Whisper reports the
        // picked language back via `result.lang`.
        val initialHint = when {
            allowlistCodes.size == 1 -> allowlistCodes.first()
            else                     -> ""
        }
        val firstResult = runSherpaPass(context, model, samples, decoded.sampleRate, initialHint)
            ?: return TranscribeResult.Failure(
                "Could not download speech model — check network"
            )

        // Allowlist-rescue path. When the user has ≥ 2 languages
        // picked we let Whisper auto-detect; on a short or noisy
        // clip the small `base` model frequently confuses similar
        // Dravidian languages (Kannada → Tamil, Telugu → Tamil, …).
        // If the detection lands outside the allowlist, re-run with
        // the user's primary as the language hint so the transcript
        // is at least in a language they actually speak — even if
        // that means transcribing Tamil audio as Kannada, the result
        // is closer to right than a confidently-wrong Tamil
        // transcript.
        val needsRescue = initialHint.isEmpty() &&
            firstResult.detectedLang.isNotEmpty() &&
            allowlistCodes.isNotEmpty() &&
            firstResult.detectedLang !in allowlistCodes
        if (needsRescue) {
            val primary = allowlistCodes.first()
            Log.w(
                TAG,
                "sherpa-onnx detected '${firstResult.detectedLang}' outside " +
                    "allowlist=${allowlistCodes.joinToString(",")} — " +
                    "re-transcribing as '$primary'",
            )
            val rescued = runSherpaPass(context, model, samples, decoded.sampleRate, primary)
            if (rescued != null && rescued.text.isNotBlank()) {
                Log.d(
                    TAG,
                    "sherpa rescue: text='${rescued.text.take(80)}' " +
                        "(replaced detected='${firstResult.detectedLang}')",
                )
                return TranscribeResult.Success(rescued.text, BACKEND_SHERPA)
            }
            // Rescue failed (no text or model error) — fall through
            // and return the original detection. Empty rescue text
            // is usually worse than the (wrong-language) first pass.
            Log.w(TAG, "sherpa rescue produced empty text — keeping initial transcript")
        }

        Log.d(
            TAG,
            "sherpa transcribe: samples=${samples.size} " +
                "rate=${decoded.sampleRate}Hz channels=${decoded.channelCount} " +
                "languageHint='$initialHint' detected='${firstResult.detectedLang}' " +
                "text='${firstResult.text.take(80)}'",
        )
        return if (firstResult.text.isBlank()) TranscribeResult.Failure("No speech detected")
        else TranscribeResult.Success(firstResult.text, BACKEND_SHERPA)
    }

    /**
     * Load or rebuild the cached recognizer for ([model],
     * [languageHint]) and run a single decode pass on [samples].
     * Returns null on model-load failure (e.g. download failed); on
     * recognizer error returns a result with empty text so the
     * caller can decide whether to surface the failure.
     */
    private suspend fun runSherpaPass(
        context: Context,
        model: WhisperModel,
        samples: FloatArray,
        sampleRate: Int,
        languageHint: String,
    ): SherpaRecognitionResult? {
        val recognizer = runCatching { ensureSherpaRecognizer(context, model, languageHint) }
            .onFailure {
                Log.e(
                    TAG,
                    "sherpa-onnx model load failed (model='${model.id}' hint='$languageHint')",
                    it,
                )
            }
            .getOrNull() ?: return null
        return runCatching { runSherpaRecognizer(recognizer, samples, sampleRate) }
            .onFailure {
                Log.e(
                    TAG,
                    "sherpa-onnx recognizer failed (model='${model.id}' hint='$languageHint')",
                    it,
                )
            }
            .getOrNull()
    }

    /**
     * Read the user's picked Whisper variant from
     * `SettingsPreferences` on an IO dispatcher (SharedPreferences
     * touches disk on first access). Falls back to
     * [WhisperModel.DEFAULT] on any read error.
     */
    private suspend fun resolvePickedModel(context: Context): WhisperModel {
        return withContext(Dispatchers.IO) {
            runCatching {
                app.quickink.mobile.features.settings.SettingsPreferences(context).transcriptionModel
            }.getOrDefault(WhisperModel.DEFAULT)
        }
    }

    private suspend fun ensureSherpaRecognizer(
        context: Context,
        model: WhisperModel,
        languageHint: String,
    ): OfflineRecognizer {
        cachedSherpaRecognizer
            ?.takeIf {
                cachedSherpaLanguageHint == languageHint &&
                    cachedSherpaModelId == model.id
            }
            ?.let { return it }

        // Run / await the model download OUTSIDE the recognizer
        // cache's lock — the fetch lives on a process-scope coroutine
        // (see [modelDownloadScope]) so a caller-side cancel doesn't
        // kill the bytes mid-flight. When the user navigates back
        // and re-tries, this call rejoins the same Deferred and
        // resumes wherever the download is at.
        ensureModelDownloaded(context, model)

        return withContext(Dispatchers.IO) {
            synchronized(sherpaRecognizerLock) {
                cachedSherpaRecognizer
                    ?.takeIf {
                        cachedSherpaLanguageHint == languageHint &&
                            cachedSherpaModelId == model.id
                    }
                    ?.let { return@withContext it }
                // Drop the previously cached recognizer when either
                // the language hint or the picked model variant
                // changes — Whisper reads `language` at config time
                // and a different variant has different encoder /
                // decoder file paths, so we can't reuse the
                // existing instance.
                cachedSherpaRecognizer?.runCatching { release() }
                cachedSherpaRecognizer = null
                cachedSherpaLanguageHint = null
                cachedSherpaModelId = null

                val modelDir = File(context.filesDir, model.sherpaDirName)
                val recognizer = OfflineRecognizer(
                    assetManager = null,
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(
                            sampleRate = SAMPLE_RATE_HZ,
                            featureDim = 80,
                        ),
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder      = File(modelDir, model.encoderFile).absolutePath,
                                decoder      = File(modelDir, model.decoderFile).absolutePath,
                                language     = languageHint,
                                // 1000-sample (~62.5 ms at 16 kHz)
                                // zero-pad before the encoder runs.
                                // Whisper's decoder can refuse to
                                // emit text on short or abruptly-
                                // ended clips — the trailing silence
                                // window gives it room to commit a
                                // final segment. Particularly
                                // pronounced on low-resource
                                // languages (Kannada, Malayalam,
                                // Punjabi) where the model already
                                // tends toward empty output on
                                // borderline audio.
                                tailPaddings = 1000,
                            ),
                            tokens = File(modelDir, model.tokensFile).absolutePath,
                            modelType = "whisper",
                            numThreads = 2,
                        ),
                    ),
                )
                cachedSherpaRecognizer = recognizer
                cachedSherpaLanguageHint = languageHint
                cachedSherpaModelId = model.id
                recognizer
            }
        }
    }

    /** Marker file written at the end of a successful extract.
     *  Its absence means the dir holds files from a killed extract
     *  (or a pre-marker build) and the recognizer would abort
     *  natively when it tried to read them. */
    private val MODEL_READY_MARKER = ".quickink-extract-complete"

    private fun isSherpaModelPresent(modelDir: File, model: WhisperModel): Boolean {
        if (!modelDir.isDirectory) return false
        // The marker is the integrity gate — it's the last write
        // every successful extract makes, so its presence proves
        // every file beforehand was fully flushed to disk.
        if (!File(modelDir, MODEL_READY_MARKER).exists()) return false
        // Belt + suspenders: the recognizer config references these
        // three paths, so verify they exist (a marker-but-no-file
        // state would only happen if something external scrubbed
        // the dir between writes).
        return File(modelDir, model.encoderFile).exists() &&
            File(modelDir, model.decoderFile).exists() &&
            File(modelDir, model.tokensFile).exists()
    }

    /**
     * Ensure [model] is extracted on disk, kicking a foreground-
     * service WorkManager fetch if it isn't. The work survives the
     * caller's coroutine being cancelled, the user navigating away,
     * the activity being destroyed, AND the app being dismissed —
     * the bytes keep pulling against a system-managed foreground
     * service. Caller suspends until the work transitions to
     * SUCCEEDED (model is on disk) or FAILED/CANCELLED.
     */
    private suspend fun ensureModelDownloaded(context: Context, model: WhisperModel) {
        val parentDir = context.filesDir
        val modelDir = File(parentDir, model.sherpaDirName)
        if (isSherpaModelPresent(modelDir, model)) return

        val workFlow = WhisperModelDownloadWorker.enqueue(context, model)
        val terminal = workFlow.first { infos ->
            val state = infos.firstOrNull()?.state
            state == androidx.work.WorkInfo.State.SUCCEEDED ||
                state == androidx.work.WorkInfo.State.FAILED ||
                state == androidx.work.WorkInfo.State.CANCELLED
        }
        val info = terminal.firstOrNull()
        if (info?.state != androidx.work.WorkInfo.State.SUCCEEDED) {
            val msg = WhisperModelDownloadWorker.errorFor(info ?: return)
                ?: "Model download did not succeed (${info.state})"
            throw IOException(msg)
        }
    }

    /**
     * Worker-facing entry point. Runs the disk-write + extract
     * pipeline as two named phases so the worker can surface
     * progress separately for each:
     *   - [onDownloadProgress] fires every ~256 KB during phase 1
     *     (HTTP byte pull into `<modelDir>.partial`).
     *   - [onExtractStart] fires once when phase 2 begins, with the
     *     total compressed size so the worker can set up a
     *     determinate progress bar.
     *   - [onExtractProgress] fires every ~1 MB of compressed input
     *     consumed by the bzip2 + tar reader. Maps roughly to "how
     *     far along the decompression is" — not perfectly linear,
     *     but a much better signal than the previous "indeterminate
     *     bar that doesn't move."
     *
     * Throws on failure; `<modelDir>.partial` is preserved on a
     * download-phase failure so a retry resumes via HTTP `Range:`.
     */
    internal fun runModelDownload(
        context: Context,
        model: WhisperModel,
        onDownloadProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
        onExtractStart: (totalCompressedBytes: Long) -> Unit,
        onExtractProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit,
    ) {
        val parentDir = context.filesDir
        val modelDir = File(parentDir, model.sherpaDirName)
        if (isSherpaModelPresent(modelDir, model)) return
        downloadAndExtractSherpaModel(
            parentDir          = parentDir,
            modelDir           = modelDir,
            model              = model,
            onDownloadProgress = onDownloadProgress,
            onExtractStart     = onExtractStart,
            onExtractProgress  = onExtractProgress,
        )
    }

    /** Container for the bits of [OfflineRecognizerResult] we care about. */
    private data class SherpaRecognitionResult(
        val text: String,
        val detectedLang: String,
    )

    /**
     * Two-phase fetch:
     *   1. Pull the tar.bz2 archive into `<modelDir>.partial` with
     *      HTTP `Range:` resume — interrupted bytes from a previous
     *      attempt are preserved across network drops, process
     *      kills, and app backgrounding.
     *   2. Once the file is complete, extract into [modelDir] and
     *      delete the partial.
     *
     * The split eliminates the "process died mid-extract" failure
     * mode the original streaming implementation had: extraction
     * only ever runs against a fully-downloaded archive, so the
     * extracted dir is either pristine or absent.
     *
     * @param parentDir the `filesDir` (or equivalent) the model dir
     *                  and the sibling `.partial` file both live in.
     */
    private fun downloadAndExtractSherpaModel(
        parentDir: File,
        modelDir: File,
        model: WhisperModel,
        onDownloadProgress: (absoluteBytes: Long, totalBytes: Long) -> Unit,
        onExtractStart: (totalCompressedBytes: Long) -> Unit,
        onExtractProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit,
    ) {
        Log.i(TAG, "downloadAndExtract start: model=${model.id} url=${model.downloadUrl}")
        val partial = File(parentDir, "${model.sherpaDirName}.partial")

        // Phase 1 — pull bytes into the `.partial` staging file.
        // Failure leaves whatever bytes landed on disk so the next
        // enqueue resumes via HTTP `Range:`.
        downloadToPartial(partial, model, onDownloadProgress)
        Log.i(
            TAG,
            "download phase complete: model=${model.id} partialBytes=${partial.length()}",
        )

        // Phase 2 — extract the fully-downloaded archive. bzip2 is
        // a stream codec, so we have to decompress the entire 500+
        // MB tarball even though we only want three files out of it;
        // on phone hardware that's 30 s – 3 min of single-threaded
        // CPU. `onExtractStart` lets the worker flip its
        // notification / WorkInfo phase to "Extracting" so the UI
        // can show indeterminate progress instead of sitting at
        // 100% looking hung.
        //
        // Atomic placement: extract into `<modelDir>.staging/`, then
        // atomically rename to `modelDir`. A worker killed mid-extract
        // (OOM, process death, native crash elsewhere) leaves only
        // the staging dir behind — `modelDir` either has every file
        // intact or doesn't exist at all. Without this, partially-
        // written `.onnx` weights survived to the next launch,
        // `isSherpaModelPresent` saw the file paths existed and
        // returned true, and sherpa-onnx aborted natively when it
        // tried to read past the end of the truncated file.
        val staging = File(parentDir, "${model.sherpaDirName}.staging")
        try {
            val compressedTotal = partial.length()
            Log.i(
                TAG,
                "extract start: model=${model.id} partial=${partial.absolutePath} compressed=${compressedTotal}",
            )
            onExtractStart(compressedTotal)
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            val extractStartMs = System.currentTimeMillis()
            partial.inputStream().use { rawIn ->
                val tracked = ExtractProgressInputStream(
                    delegate     = rawIn,
                    totalBytes   = compressedTotal,
                    onProgress   = onExtractProgress,
                )
                tracked.buffered().use { input ->
                    extractModelTarBz2(input, staging, model)
                }
            }
            // Sanity-check before promoting: every wanted file must
            // exist with non-zero size. Catches a tar archive that
            // looked complete but somehow skipped one of our files.
            for (name in arrayOf(model.encoderFile, model.decoderFile, model.tokensFile)) {
                val f = File(staging, name)
                if (!f.exists() || f.length() == 0L) {
                    throw IOException("extract missing file: $name (exists=${f.exists()}, len=${f.length()})")
                }
            }
            if (modelDir.exists()) modelDir.deleteRecursively()
            if (!staging.renameTo(modelDir)) {
                // Same-filesystem rename failed (rare on Android
                // /data/data) — fall back to copy + delete.
                Log.w(TAG, "renameTo failed; falling back to copy")
                staging.copyRecursively(modelDir, overwrite = true)
                staging.deleteRecursively()
            }
            // Stamp the integrity marker. This is the LAST write —
            // its presence on the next launch proves every file
            // above was flushed before we lost the process. See
            // [isSherpaModelPresent].
            File(modelDir, MODEL_READY_MARKER).writeText("ok\n")
            Log.i(
                TAG,
                "extract complete: model=${model.id} dir=${modelDir.absolutePath} elapsed=${System.currentTimeMillis() - extractStartMs}ms",
            )
        } catch (e: Exception) {
            Log.e(TAG, "extract failed: model=${model.id} message=${e.message}", e)
            staging.deleteRecursively()
            // Leave the partial archive in place so the next attempt
            // can re-extract without re-downloading — the bytes
            // themselves were proven good by the truncation check at
            // the end of `downloadToPartial`.
            throw e
        }

        partial.delete()
        Log.i(TAG, "downloadAndExtract done: model=${model.id}")
    }

    /**
     * Download (or resume) the tar.bz2 archive to [partial]. On
     * success [partial] contains the complete archive. On any
     * exception [partial] holds whatever bytes were pulled so far
     * — caller is responsible for whether to keep it or wipe it.
     *
     * Resume mechanics: if [partial] already exists with non-zero
     * size, the request sends `Range: bytes=N-`. GitHub Releases
     * (where sherpa-onnx hosts) honor Range and answer with 206
     * Partial Content + a `Content-Range: bytes start-end/total`
     * header. If the server falls back to 200 (no Range support
     * or a different file behind the URL), the partial is wiped
     * and the download restarts from byte 0.
     */
    private fun downloadToPartial(
        partial: File,
        model: WhisperModel,
        onProgress: (absoluteBytes: Long, totalBytes: Long) -> Unit,
    ) {
        // Up to two passes. The first may discover a stale `.partial`
        // (left full-sized by a worker killed mid-extract) and 416
        // back; the second pass starts from byte 0 with a clean
        // partial and succeeds.
        for (attempt in 1..2) {
            val resumeFrom = if (partial.exists()) partial.length() else 0L
            Log.i(
                TAG,
                "downloadToPartial attempt=$attempt model=${model.id} resumeFrom=$resumeFrom partialExists=${partial.exists()}",
            )

            val conn = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 300_000
                instanceFollowRedirects = true
                if (resumeFrom > 0) {
                    setRequestProperty("Range", "bytes=$resumeFrom-")
                }
            }
            // Emit an initial "we're at resumeFrom bytes / total
            // unknown" beat so the modal renders progress immediately
            // even before the response headers arrive.
            onProgress(resumeFrom, 0L)
            try {
                Log.i(TAG, "HTTP connecting to ${model.downloadUrl}…")
                val connectStartMs = System.currentTimeMillis()
                conn.connect()
                val code = conn.responseCode
                Log.i(
                    TAG,
                    "HTTP connected: code=$code in ${System.currentTimeMillis() - connectStartMs}ms",
                )

                // 416 Range Not Satisfiable — partial is at or past
                // the resource's actual size. Two cases:
                //   1. partial.length() == total: the download
                //      already finished last attempt; the worker was
                //      killed before extract ran. Bail out happy —
                //      caller will extract from the existing file.
                //   2. partial.length() != total (over-sized or
                //      mismatched): the file on disk is bogus. Wipe
                //      and retry the loop from byte 0.
                if (code == 416) {
                    val total = parseContentRangeTotal(conn)
                    runCatching { conn.disconnect() }
                    if (total > 0 && partial.length() == total) {
                        Log.i(
                            TAG,
                            "HTTP 416 + partial is complete (${partial.length()} bytes) — skipping fetch, ready to extract",
                        )
                        onProgress(partial.length(), total)
                        return
                    }
                    Log.i(
                        TAG,
                        "HTTP 416 with mismatched partial (have=${partial.length()} total=$total); wiping and restarting from byte 0",
                    )
                    partial.delete()
                    continue
                }

                val resumed = code == 206
                if (!resumed && code !in 200..299) {
                    throw IOException("sherpa model HTTP $code")
                }
                if (!resumed && resumeFrom > 0) {
                    // Server ignored Range (returned 200 with the
                    // whole file). Drop the partial bytes — they
                    // may belong to a different revision of the asset.
                    Log.i(TAG, "Server didn't honor Range; restarting from byte 0")
                    partial.delete()
                }
                val effectiveStart = if (resumed) resumeFrom else 0L
                val totalBytes = totalBytesFor(conn, resumed)
                Log.i(
                    TAG,
                    "HTTP body: effectiveStart=$effectiveStart totalBytes=$totalBytes resumed=$resumed",
                )
                onProgress(effectiveStart, totalBytes)

                partial.parentFile?.mkdirs()
                val firstReadStartMs = System.currentTimeMillis()
                var loggedFirstChunk = false
                java.io.FileOutputStream(partial, /* append = */ resumed).use { out ->
                    val counting = ProgressTrackingInputStream(
                        delegate                 = conn.inputStream,
                        totalBytes               = totalBytes,
                        bytesAlreadyDownloaded   = effectiveStart,
                        onProgress               = { absolute, total ->
                            if (!loggedFirstChunk && absolute > effectiveStart) {
                                loggedFirstChunk = true
                                Log.i(
                                    TAG,
                                    "first bytes received in ${System.currentTimeMillis() - firstReadStartMs}ms (absolute=$absolute)",
                                )
                            }
                            onProgress(absolute, total)
                        },
                    )
                    counting.copyTo(out)
                }

                // Truncation check. With a known total we should
                // have landed exactly there; with an unknown total
                // we trust EOF and accept whatever we got.
                if (totalBytes > 0 && partial.length() < totalBytes) {
                    throw IOException(
                        "Download truncated: ${partial.length()}/$totalBytes bytes — will resume on next try"
                    )
                }
                Log.i(
                    TAG,
                    "downloadToPartial done: model=${model.id} partial=${partial.length()} expected=$totalBytes",
                )
                return
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        throw IOException("downloadToPartial retry exhausted (HTTP 416 ping-pong)")
    }

    // Pull the total-resource-size out of a `Content-Range` header.
    // Format: `bytes <start>-<end>/<total>` on a 206, or
    // `bytes <asterisk>/<total>` (literal *) on a 416. Returns 0 if
    // the header is missing or unparseable so the caller can fall
    // back to "wipe and retry".
    private fun parseContentRangeTotal(conn: HttpURLConnection): Long {
        val cr = conn.getHeaderField("Content-Range") ?: return 0L
        return cr.substringAfterLast('/').toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    /**
     * Compute the canonical total-bytes value to surface to the UI
     * — Content-Range (`bytes a-b/total`) on a 206 resume, plain
     * Content-Length on a 200. Returns 0 if neither is available
     * so the modal renders an indeterminate bar.
     */
    private fun totalBytesFor(conn: HttpURLConnection, resumed: Boolean): Long {
        if (!resumed) return conn.contentLengthLong.coerceAtLeast(0L)
        val range = conn.getHeaderField("Content-Range") ?: return 0L
        return range.substringAfterLast('/').toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    /**
     * Pass-through `InputStream` that counts read bytes and posts
     * progress to [SpeechModelDownloadProgress] at a coarse interval
     * (~256 KB). The interval keeps the StateFlow from churning on
     * every byte the underlying connection buffers. Reports
     * *absolute* progress (resumed start offset + bytes read from
     * this stream) so the bar reflects the user's true position in
     * the archive, not just the slice the current HTTP call is
     * fetching.
     */
    private class ProgressTrackingInputStream(
        private val delegate: java.io.InputStream,
        private val totalBytes: Long,
        private val bytesAlreadyDownloaded: Long,
        private val onProgress: (absoluteBytes: Long, total: Long) -> Unit,
    ) : java.io.InputStream() {
        private var bytesRead: Long = 0
        private var lastReportedBytes: Long = 0
        // 256 KB — frequent enough to feel live (a determinate bar
        // ticks ~2× per second on a 1 MB/s connection) but rare
        // enough that StateFlow consumers on the main thread don't
        // pay for per-byte recompositions.
        private val reportIntervalBytes: Long = 256L * 1024L

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) {
                bytesRead++
                maybeReport()
            }
            return b
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(buf, off, len)
            if (n > 0) {
                bytesRead += n
                maybeReport()
            }
            return n
        }

        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()

        private fun maybeReport() {
            val absolute = bytesAlreadyDownloaded + bytesRead
            if (bytesRead - lastReportedBytes >= reportIntervalBytes ||
                (totalBytes > 0 && absolute >= totalBytes)
            ) {
                lastReportedBytes = bytesRead
                onProgress(absolute, totalBytes)
            }
        }
    }

    private fun extractModelTarBz2(input: java.io.InputStream, modelDir: File, model: WhisperModel) {
        val wanted = setOf(model.encoderFile, model.decoderFile, model.tokensFile)
        TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tis ->
            var entry = tis.nextEntry
            var entriesSeen = 0
            while (entry != null) {
                if (!entry.isDirectory) {
                    val base = entry.name.substringAfterLast('/')
                    val wantedFlag = if (base in wanted) "[KEEP]" else "[skip]"
                    Log.i(TAG, "tar entry $wantedFlag ${entry.name} (${entry.size} bytes)")
                    if (base in wanted) {
                        val entryStartMs = System.currentTimeMillis()
                        File(modelDir, base).outputStream().use { out ->
                            tis.copyTo(out)
                        }
                        Log.i(
                            TAG,
                            "extracted '$base' (${File(modelDir, base).length()} bytes) in ${System.currentTimeMillis() - entryStartMs}ms",
                        )
                    }
                }
                entriesSeen++
                entry = tis.nextEntry
            }
            Log.i(TAG, "extract scanned $entriesSeen tar entries")
        }
    }

    /**
     * Counting wrapper around the partial-archive `InputStream` so
     * we can report extract progress to the worker. The bytes here
     * are *compressed* bytes pulled from disk; tar + bzip2 advance
     * the read pointer non-linearly relative to decompressed output,
     * so this is an approximation — but it's strictly monotonic and
     * reaches the file size at end of stream, which is all the
     * modal needs.
     */
    private class ExtractProgressInputStream(
        private val delegate: java.io.InputStream,
        private val totalBytes: Long,
        private val onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit,
    ) : java.io.InputStream() {
        private var bytesRead: Long = 0L
        private var lastReportedBytes: Long = 0L
        // 1 MB — coarser than the download phase (256 KB) because
        // the extract phase is CPU-bound and we don't want progress
        // emissions to interfere with the decompression throughput.
        private val reportIntervalBytes: Long = 1L * 1024L * 1024L

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) {
                bytesRead++
                maybeReport()
            }
            return b
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(buf, off, len)
            if (n > 0) {
                bytesRead += n
                maybeReport()
            }
            return n
        }

        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()

        private fun maybeReport() {
            if (bytesRead - lastReportedBytes >= reportIntervalBytes ||
                (totalBytes > 0 && bytesRead >= totalBytes)
            ) {
                lastReportedBytes = bytesRead
                onProgress(bytesRead, totalBytes)
            }
        }
    }

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

            os.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeInt32(riffChunkSize)
            os.write("WAVE".toByteArray(Charsets.US_ASCII))

            os.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeInt32(16)
            writeInt16(1)
            writeInt16(channels)
            writeInt32(sampleRate)
            writeInt32(byteRate)
            writeInt16(blockAlign.toInt())
            writeInt16(bitsPerSample)

            os.write("data".toByteArray(Charsets.US_ASCII))
            writeInt32(dataSize)
            os.write(pcm)
        }
    }

    /**
     * Raw PCM bytes + the actual format the source audio was
     * decoded into (sample rate + channel count). Both transcribe
     * backends need this metadata: sherpa converts the bytes into
     * mono floats and passes the rate to `acceptWaveform`
     * (sherpa-onnx resamples internally), while the ML Kit path
     * writes a WAV header with the same rate + channel count.
     * Without these, we silently mis-interpreted 44.1kHz stereo
     * CameraX-recorded audio as 16kHz mono — Whisper saw a 2.75×
     * time-stretched, channel-interleaved soup and produced empty
     * / nonsense output, and ML Kit's WAV file claimed 16kHz when
     * the bytes were at 44.1kHz. Manual voice notes weren't
     * affected because the recorder writes 16kHz mono directly.
     */
    private data class DecodedAudio(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channelCount: Int,
    )

    private fun decodeToPcm(file: File): DecodedAudio {
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

        // Initial format hints — superseded by the decoder's
        // negotiated output format once `INFO_OUTPUT_FORMAT_CHANGED`
        // fires (which is the authoritative source for sample rate
        // + channel count post-decode).
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

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
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        return DecodedAudio(
            pcm          = output.toByteArray(),
            sampleRate   = sampleRate,
            channelCount = channelCount.coerceAtLeast(1),
        )
    }

    /**
     * Convert little-endian 16-bit PCM bytes to a mono FloatArray
     * in [-1.0, 1.0]. Multi-channel input is downmixed by averaging
     * the channels per frame — sherpa-onnx Whisper expects mono and
     * silently mis-interprets interleaved stereo as time-domain
     * samples (twice the duration, half the content).
     */
    private fun pcmBytesToMonoFloats(pcm: ByteArray, channelCount: Int): FloatArray {
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSamples = shorts.remaining()
        if (channelCount <= 1) {
            val samples = FloatArray(totalSamples)
            for (i in samples.indices) {
                samples[i] = shorts.get().toInt() / 32768.0f
            }
            return samples
        }
        val frames = totalSamples / channelCount
        val samples = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channelCount) sum += shorts.get().toInt()
            samples[i] = (sum.toFloat() / channelCount) / 32768.0f
        }
        return samples
    }

    private fun runSherpaRecognizer(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
        sampleRate: Int,
    ): SherpaRecognitionResult {
        val stream = recognizer.createStream()
        return try {
            // `acceptWaveform` takes the actual sample rate of the
            // input — sherpa resamples internally to the recognizer's
            // configured feature rate. Passing the wrong rate
            // produces a time-stretched / nonsense transcription.
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            SherpaRecognitionResult(
                text         = r.text.trim(),
                detectedLang = r.lang.orEmpty(),
            )
        } finally {
            runCatching { stream.release() }
        }
    }
}
