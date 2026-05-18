/*
 * WhisperModel.kt
 *
 * The catalog of sherpa-onnx multilingual Whisper variants the user
 * can pick between in Settings. Each entry encodes the disk layout
 * (download URL, extracted dir name, encoder/decoder/tokens
 * filenames) plus user-facing copy (display name, ~MB size, speed
 * blurb).
 *
 * The four variants are the int8-quantised multilingual builds from
 * the k2-fsa/sherpa-onnx releases page:
 *   - `tiny`    ~ 120 MB, fastest. Indian-language quality is rough;
 *                use only on disk-constrained devices.
 *   - `base`    ~ 165 MB, balanced. Hindi / Bengali workable;
 *                Kannada / Malayalam / Punjabi frequently fail.
 *   - `small`   ~ 500 MB, recommended default. Indian-language
 *                accuracy jumps significantly over `base`. Inference
 *                ~3× slower than `base`.
 *   - `medium`  ~ 1.5 GB, best accuracy. Heaviest download, slowest
 *                inference (~3× small). Worth it if `small` still
 *                misses your accent or speech style.
 *
 * Storage: the picked variant id lives in `SettingsPreferences`
 * under a device-local key. Not synced — model choice is a per-
 * device perf trade-off (a tablet vs. a low-end phone might want
 * different sizes), not a per-user preference.
 */

package app.quickink.mobile.data.voicenote

enum class WhisperModel(
    /** Stable string id stored in `SettingsPreferences`. */
    val id: String,
    /** Title-case label shown in the Settings picker. */
    val displayName: String,
    /** Approximate extracted size in MB — shown on the chip and
     *  drives the "Y MB" copy in the download modal. */
    val approxSizeMb: Int,
    /** One-line UX blurb shown under the chip in Settings. */
    val blurb: String,
    /** Sub-directory under `filesDir` where the extracted model files
     *  live. Mirrors the tar.bz2 root folder name on the sherpa-onnx
     *  releases page. */
    val sherpaDirName: String,
    /** Absolute URL of the tar.bz2 archive on the GitHub release. */
    val downloadUrl: String,
    /** Filenames inside [sherpaDirName] after extraction. */
    val encoderFile: String,
    val decoderFile: String,
    val tokensFile: String,
) {
    Tiny(
        id            = "tiny",
        displayName   = "Tiny",
        approxSizeMb  = 120,
        blurb         = "Fastest. Basic quality — usable for English; rough on Indian languages.",
        sherpaDirName = "sherpa-onnx-whisper-tiny",
        downloadUrl   = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
        encoderFile   = "tiny-encoder.int8.onnx",
        decoderFile   = "tiny-decoder.int8.onnx",
        tokensFile    = "tiny-tokens.txt",
    ),
    Base(
        id            = "base",
        displayName   = "Base",
        approxSizeMb  = 165,
        blurb         = "Balanced. Hindi & Bengali workable; Kannada / Malayalam / Punjabi can be patchy.",
        sherpaDirName = "sherpa-onnx-whisper-base",
        downloadUrl   = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
        encoderFile   = "base-encoder.int8.onnx",
        decoderFile   = "base-decoder.int8.onnx",
        tokensFile    = "base-tokens.txt",
    ),
    Small(
        id            = "small",
        displayName   = "Small",
        approxSizeMb  = 500,
        blurb         = "Recommended. Strong Indian-language quality; slower than Base.",
        sherpaDirName = "sherpa-onnx-whisper-small",
        downloadUrl   = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
        encoderFile   = "small-encoder.int8.onnx",
        decoderFile   = "small-decoder.int8.onnx",
        tokensFile    = "small-tokens.txt",
    ),
    Medium(
        id            = "medium",
        displayName   = "Medium",
        approxSizeMb  = 1_500,
        blurb         = "Best accuracy. Heavy download (~1.5 GB) and slow on mid-range phones.",
        sherpaDirName = "sherpa-onnx-whisper-medium",
        downloadUrl   = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-medium.tar.bz2",
        encoderFile   = "medium-encoder.int8.onnx",
        decoderFile   = "medium-decoder.int8.onnx",
        tokensFile    = "medium-tokens.txt",
    );

    companion object {
        /** Default for a fresh install or an unknown stored id. */
        val DEFAULT: WhisperModel = Small

        /** Map a stored id back to the catalog row; falls back to
         *  [DEFAULT] on null or unknown input so a corrupted pref
         *  doesn't wedge the transcriber. */
        fun fromId(id: String?): WhisperModel =
            values().firstOrNull { it.id == id } ?: DEFAULT
    }
}
