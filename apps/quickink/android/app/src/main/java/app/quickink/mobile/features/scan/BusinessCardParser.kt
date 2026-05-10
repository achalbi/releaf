/*
 * BusinessCardParser.kt
 *
 * Lightweight heuristic parser that pulls a contact name and any
 * mobile numbers out of a business-card OCR blob. Used by the
 * "Add to contact" action on `ScanDetailScreen` when the capture's
 * category is Business Card. Mirror of iOS's
 * `BusinessCardParser.swift`.
 *
 * Phone matching:
 *   - Picks any digit run (with optional inline whitespace, dashes,
 *     dots, parens) that, after stripping non-digits, normalises to
 *     a 10-digit Indian mobile, a +91-prefixed 12-digit number, or
 *     a 0-prefixed 11-digit number (the leading 0 is dropped).
 *   - De-duplicates and preserves first-seen order.
 *
 * Name picking:
 *   - First non-empty line that has at least 2 letters AND no
 *     digits. Business cards typically lead with the contact's
 *     name, so the heuristic is "first line that looks like a name".
 *   - Returns `null` when no line qualifies; the caller hands the
 *     empty name to the system contact UI and the user can fill it
 *     in manually.
 */

package app.quickink.mobile.features.scan

object BusinessCardParser {

    /**
     * Result of parsing a card's OCR blob: the best-guess name (may
     * be null) and the de-duplicated list of phone numbers (in
     * normalised form — 10 digits, or "+91…" for international).
     */
    data class Parsed(
        val name: String?,
        val phones: List<String>,
    )

    fun parse(ocr: String): Parsed {
        val lines = ocr
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return Parsed(
            name   = pickName(lines),
            phones = extractPhones(lines),
        )
    }

    // Regex matches any run starting with an optional `+`, then a
    // digit, then 8–18 chars of digits + common phone separators
    // (space, dash, dot, parens), then a digit. The post-clean step
    // validates the digit count, so the regex can be loose without
    // dragging in junk.
    private val phoneRegex = Regex("""\+?\d[\d\s\-.()]{8,18}\d""")

    private fun extractPhones(lines: List<String>): List<String> {
        val seen   = mutableSetOf<String>()
        val phones = mutableListOf<String>()
        for (line in lines) {
            for (match in phoneRegex.findAll(line)) {
                val normalised = normalisePhone(match.value) ?: continue
                if (seen.add(normalised)) phones.add(normalised)
            }
        }
        return phones
    }

    /**
     * Strip non-digit chars, then accept the result as a phone if:
     *   - 10 digits → Indian mobile (no country code) — return as-is
     *   - 12 digits prefixed by `91` → return as `+<digits>`
     *   - 11 digits prefixed by `0` → drop the leading 0
     * Anything else → null. Matches the user's brief: "10 digit
     * number or prefixed by +91".
     */
    fun normalisePhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 10                                 -> digits
            digits.length == 11 && digits.startsWith("0")       -> digits.drop(1)
            digits.length == 12 && digits.startsWith("91")      -> "+$digits"
            else                                                -> null
        }
    }

    // Heuristic: first line with ≥ 2 letters and no digits.
    // Business cards lead with the contact's name, and digits
    // typically only appear in phone / fax / address lines.
    private fun pickName(lines: List<String>): String? =
        lines.firstOrNull { line ->
            val hasLetter  = line.any { it.isLetter() }
            val hasDigit   = line.any { it.isDigit() }
            val letterCount = line.count { it.isLetter() }
            hasLetter && !hasDigit && letterCount >= 2
        }
}
