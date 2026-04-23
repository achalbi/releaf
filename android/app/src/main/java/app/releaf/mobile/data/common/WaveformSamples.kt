/*
 * WaveformSamples.kt
 *
 * Turns a saved voice-note .m4a into a small fixed-size array of
 * per-bucket amplitudes so the voice-note card can draw a waveform
 * that reflects the actual audio — not a decorative hash-seeded shape.
 *
 * Decoding is expensive (MediaExtractor + MediaCodec → 16-bit PCM) so
 * results are cached by uri-and-bucket-count in a process-lifetime map.
 * First render of a card triggers the decode on Dispatchers.IO; later
 * renders (scroll, recomposition, re-open the editor in the same
 * process) pull straight from the cache.
 *
 * The output is normalized to a [0.08, 1.0] range — a minimum of 0.08
 * keeps even silent stretches visually present as tiny bars, which
 * reads better than perfectly flat nothingness.
 *
 * Silent recordings are intentionally easy to spot: they render as a
 * row of uniform 0.08 bars, so the user can see at a glance that the
 * mic captured nothing and know why transcription said "No speech
 * detected".
 */

package app.releaf.mobile.data.common

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WaveformSamples {

    // Shares the `SpeechTranscriber` tag so one logcat filter
    // (`adb logcat -s SpeechTranscriber`) catches both the
    // transcription path and these amplitude-diagnostic lines.
    private const val TAG = "SpeechTranscriber"

    /** Keyed by `"$uri#$barCount"` — lets the same file support multiple
     *  bucket resolutions if future callers need them. Process-lifetime;
     *  cleared when the app process dies. Bounded only by the number of
     *  voice notes the user has ever opened in this process, which is
     *  small enough that LRU eviction hasn't been necessary. */
    private val cache = ConcurrentHashMap<String, FloatArray>()

    /**
     * Extract `barCount` amplitude samples from the audio behind `uri`.
     * Returns null if the file is missing or unreadable — callers should
     * fall back to a decorative waveform in that case.
     *
     * Safe to call many times with the same uri; subsequent calls hit
     * the in-memory cache without touching the decoder.
     */
    suspend fun extract(uri: String, barCount: Int): FloatArray? {
        if (barCount <= 0) return null
        val cacheKey = "$uri#$barCount"
        cache[cacheKey]?.let { return it }

        return withContext(Dispatchers.IO) {
            val file = runCatching { File(Uri.parse(uri).path ?: "") }.getOrNull()
            if (file == null || !file.exists() || file.length() == 0L) {
                Log.d(TAG, "extract: file missing for uri=$uri")
                return@withContext null
            }

            val pcm = runCatching { decodePcm(file) }
                .onFailure { Log.w(TAG, "extract: decode failed for ${file.name}", it) }
                .getOrNull()
                ?: return@withContext null

            if (pcm.isEmpty()) {
                Log.d(TAG, "extract: decoded pcm was empty for ${file.name}")
                return@withContext null
            }

            val samples = amplitudesFromPcm(pcm, barCount)
            // Log a rough RMS-peak proxy so it's trivial to confirm
            // via logcat whether a clip that transcription calls
            // "silent" really is silent. 16-bit PCM has ±32767 range,
            // so a peak near 1.0 means a hot signal, near 0.0 means
            // dead air.
            val peak = samples.maxOrNull() ?: 0f
            Log.d(
                TAG,
                "extract: file=${file.name} pcmBytes=${pcm.size} peak=${"%.3f".format(peak)} " +
                    if (peak < 0.1f) "(clip reads as silent)" else "(clip has audio)",
            )
            cache[cacheKey] = samples
            samples
        }
    }

    /**
     * Decode an AAC-in-MP4 file to 16-bit signed little-endian PCM.
     * Same general shape as the path in `SpeechTranscriber` — kept
     * separate here so the waveform can load even when the recognizer
     * back-ends are unavailable / unused.
     */
    private fun decodePcm(file: File): ByteArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIdx = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIdx = i
                inputFormat = fmt
                break
            }
        }
        if (trackIdx < 0 || inputFormat == null) {
            extractor.release()
            throw IOException("No audio track")
        }
        extractor.selectTrack(trackIdx)

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
                                inIdx, 0, sampleSize, extractor.sampleTime, 0,
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
     * Compress an arbitrary-length PCM buffer down to `barCount` buckets.
     * Each output bar is the root-mean-square amplitude of its bucket,
     * then the whole vector is rescaled so the loudest bar sits at 1.0
     * and the rest are proportional — the absolute dB level of the
     * quietest recording is irrelevant on a waveform, what matters is
     * the *shape* of the signal.
     *
     * PCM is assumed 16-bit little-endian signed (MediaCodec's default
     * output for AAC on Android). Channel count doesn't matter for RMS
     * — interleaved stereo just gets averaged across both channels.
     */
    private fun amplitudesFromPcm(pcm: ByteArray, barCount: Int): FloatArray {
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return FloatArray(barCount) { MIN_BAR }

        val bucketSize = (sampleCount / barCount).coerceAtLeast(1)
        val result = FloatArray(barCount)
        var peak = 0f

        for (bucket in 0 until barCount) {
            val startSample = bucket * bucketSize
            val endSample = min(startSample + bucketSize, sampleCount)
            if (endSample <= startSample) {
                result[bucket] = 0f
                continue
            }
            var sumSquares = 0.0
            var count = 0
            var i = startSample
            while (i < endSample) {
                val byteIdx = i * 2
                if (byteIdx + 1 >= pcm.size) break
                // 16-bit little-endian signed sample
                val low = pcm[byteIdx].toInt() and 0xFF
                val high = pcm[byteIdx + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toInt()
                sumSquares += (sample.toDouble() * sample.toDouble())
                count++
                i++
            }
            val rms = if (count > 0) sqrt(sumSquares / count).toFloat() else 0f
            result[bucket] = rms
            if (rms > peak) peak = rms
        }

        // Normalize — bars map to [MIN_BAR, 1.0] of the peak. A signal
        // that's all silence ends up as a row of MIN_BAR bars, which is
        // the diagnostic-friendly behaviour we want.
        if (peak <= 0f) {
            return FloatArray(barCount) { MIN_BAR }
        }
        for (i in result.indices) {
            val normalized = result[i] / peak
            result[i] = (MIN_BAR + normalized * (1f - MIN_BAR)).coerceIn(MIN_BAR, 1f)
        }
        return result
    }

    /** Minimum rendered bar height (of track height) for any bucket —
     *  silent buckets still show a hairline so the row reads as "empty
     *  audio" rather than "no waveform data". */
    private const val MIN_BAR = 0.08f
}
