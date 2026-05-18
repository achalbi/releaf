/*
 * TranscriptionLanguage.kt
 *
 * The catalog of languages QuickInk's voice-note transcription can
 * cover: English plus the major Indian languages Whisper supports.
 * Drives the multi-select chip grid in
 *   - Onboarding "Languages" step,
 *   - Settings → Transcription languages.
 *
 * Storage format (used by `profile_settings.transcription_languages`
 * on both platforms) is a comma-separated list of the ISO 639-1
 * codes below — e.g. "en,hi,kn". Compact, grep-able, no JSON parsing
 * in the payload codec.
 *
 * iOS mirror: `TranscriptionLanguage.swift`. Keep the two lists
 * symmetric — adding a language means adding the same code+name
 * pair on the other side in the same commit.
 */

package app.quickink.mobile.data.voicenote

import java.util.Locale

/**
 * One row in the multi-select catalog.
 *
 * @property code        ISO 639-1 code (e.g. "en", "hi"). Canonical
 *                       storage form — what lands in the comma-
 *                       separated DB column.
 * @property englishName User-visible name when the device is in
 *                       English (or any non-matching locale).
 * @property nativeName  User-visible name in the language itself
 *                       (e.g. "हिन्दी"). Shown as a secondary line on
 *                       the chip so a user who doesn't read English
 *                       can still recognise their language.
 */
data class TranscriptionLanguage(
    val code: String,
    val englishName: String,
    val nativeName: String,
)

object TranscriptionLanguages {

    /**
     * Catalog order = display order in the multi-select. English
     * first (the most common pick across India), then the Indian
     * languages in roughly speaker-count order so the most-picked
     * options are at the top of the grid.
     *
     * `ur` (Urdu) is included for completeness even though Apple +
     * Whisper coverage is weaker on it than e.g. Hindi; the
     * transcriber falls back to cloud / English-only if a picked
     * language has no on-device support.
     */
    val supported: List<TranscriptionLanguage> = listOf(
        TranscriptionLanguage("en", "English",   "English"),
        TranscriptionLanguage("hi", "Hindi",     "हिन्दी"),
        TranscriptionLanguage("bn", "Bengali",   "বাংলা"),
        TranscriptionLanguage("te", "Telugu",    "తెలుగు"),
        TranscriptionLanguage("mr", "Marathi",   "मराठी"),
        TranscriptionLanguage("ta", "Tamil",     "தமிழ்"),
        TranscriptionLanguage("gu", "Gujarati",  "ગુજરાતી"),
        TranscriptionLanguage("kn", "Kannada",   "ಕನ್ನಡ"),
        TranscriptionLanguage("ml", "Malayalam", "മലയാളം"),
        TranscriptionLanguage("pa", "Punjabi",   "ਪੰਜਾਬੀ"),
        TranscriptionLanguage("ur", "Urdu",      "اردو"),
    )

    private val codeIndex: Map<String, TranscriptionLanguage> =
        supported.associateBy { it.code }

    /**
     * Look up a single row by its ISO 639-1 code. Returns null for
     * codes outside the catalog so callers handle "user picked a
     * language we've since removed" gracefully.
     */
    fun find(code: String): TranscriptionLanguage? = codeIndex[code]

    /**
     * Parse the `profile_settings.transcription_languages` column
     * (comma-separated codes) into the matching catalog rows.
     * Unknown codes are dropped silently; whitespace is tolerated.
     * Returns an empty list for null/blank input — caller decides
     * how to apply the default.
     */
    fun parse(stored: String?): List<TranscriptionLanguage> {
        if (stored.isNullOrBlank()) return emptyList()
        return stored.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { codeIndex[it.lowercase(Locale.ROOT)] }
            .distinct()
    }

    /**
     * Encode a list of catalog rows back into the comma-separated
     * storage form. Empty list returns null so the DB column ends
     * up genuinely empty (vs an empty string) — matches the
     * "no preference set" state the receiver expects.
     */
    fun encode(languages: List<TranscriptionLanguage>): String? {
        if (languages.isEmpty()) return null
        return languages.joinToString(",") { it.code }
    }

    /**
     * Default allowlist when the user hasn't picked yet: the device
     * locale's language (if it's in the catalog) plus English,
     * deduped. Onboarding's "Languages" step pre-checks these so
     * users in India see e.g. ["Hindi", "English"] already on.
     */
    fun defaultAllowlist(deviceLocale: Locale = Locale.getDefault()): List<TranscriptionLanguage> {
        val deviceCode = deviceLocale.language.lowercase(Locale.ROOT)
        val deviceRow = codeIndex[deviceCode]
        val english   = codeIndex.getValue("en")
        return if (deviceRow == null || deviceRow.code == "en") {
            listOf(english)
        } else {
            listOf(deviceRow, english)
        }
    }
}
