/*
 * SpeechTranscriber.kt
 *
 * On-device speech-to-text for voice notes. Runs *after* the recorder
 * stops — mirrors the iOS path, which uses SFSpeechURLRecognitionRequest
 * on the finalized file.
 *
 * Two back-ends, dispatched at call time:
 *
 *   1. sherpa-onnx (preferred), whisper-tiny.en int8. ONNX Runtime +
 *      sherpa-onnx JNI ship as ~34MB of native libs per ABI. The
 *      model itself (~98MB) downloads on first use into the app's
 *      files dir.
 *
 *   2. ML Kit GenAI Speech Recognition (fallback, API 31+). Uses
 *      AICore / Gemini Nano on supported devices. No model bundle in
 *      the APK.
 *
 * Ported verbatim from Releaf's `SpeechTranscriber.kt`, package
 * renamed to live under `app.quickink.mobile.data.voicenote`.
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

        val sherpa = runCatching { transcribeWithSherpa(context, file) }
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

    private const val SHERPA_MODEL_NAME = "sherpa-onnx-whisper-tiny.en"
    private const val SHERPA_MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-whisper-tiny.en.tar.bz2"
    private const val SHERPA_ENCODER = "tiny.en-encoder.int8.onnx"
    private const val SHERPA_DECODER = "tiny.en-decoder.int8.onnx"
    private const val SHERPA_TOKENS  = "tiny.en-tokens.txt"

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

        val decoded = runCatching { decodeToPcm(file) }
            .onFailure { Log.e(TAG, "decode failed", it) }
            .getOrNull()
            ?: return TranscribeResult.Failure("Could not decode audio file")

        if (decoded.pcm.isEmpty()) {
            return TranscribeResult.Failure("Audio file was empty")
        }

        val samples = pcmBytesToMonoFloats(decoded.pcm, decoded.channelCount)
        val text = runCatching {
            runSherpaRecognizer(recognizer, samples, decoded.sampleRate)
        }
            .onFailure { Log.e(TAG, "sherpa-onnx recognizer failed", it) }
            .getOrNull()
            ?: return TranscribeResult.Failure("Transcription failed")

        Log.d(
            TAG,
            "sherpa transcribe: samples=${samples.size} " +
                "rate=${decoded.sampleRate}Hz channels=${decoded.channelCount} " +
                "text='${text.take(80)}'",
        )
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
        return File(modelDir, SHERPA_ENCODER).exists() &&
            File(modelDir, SHERPA_TOKENS).exists()
    }

    private fun downloadAndExtractSherpaModel(modelDir: File) {
        Log.d(TAG, "sherpa model: downloading…")
        if (modelDir.exists()) modelDir.deleteRecursively()
        modelDir.mkdirs()

        val conn = (URL(SHERPA_MODEL_URL).openConnection() as HttpURLConnection).apply {
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
            modelDir.deleteRecursively()
            throw e
        } finally {
            runCatching { conn.disconnect() }
        }
        Log.d(TAG, "sherpa model: extracted to ${modelDir.absolutePath}")
    }

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
    ): String {
        val stream = recognizer.createStream()
        return try {
            // `acceptWaveform` takes the actual sample rate of the
            // input — sherpa resamples internally to the recognizer's
            // configured feature rate. Passing the wrong rate
            // produces a time-stretched / nonsense transcription.
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }
}
