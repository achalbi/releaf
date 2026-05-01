/*
 * ReleafReadEstimate.kt
 *
 * Pure value-type that turns a list of note bodies into a word
 * count and a casual-reading-time estimate. Used by PageDetail to
 * show a small "2 min read · 187 words" chip under the title block.
 *
 * Same target rules as ReleafImpact — no Data layer import; call
 * sites pull the body strings off a Page and pass them in.
 *
 * Reading speed defaults to 200 words/minute, the value most
 * style guides cite for casual prose. Adjustable via the parameter
 * when call sites want a slower (technical) or faster (skimming)
 * cadence.
 *
 * Mirrors `ReleafReadEstimate.swift` — the two platforms compute
 * identical numbers from identical inputs.
 */

package app.releaf.mobile.ui.theme

import kotlin.math.max
import kotlin.math.roundToInt

data class ReleafReadEstimate(
    val words: Int,
    /** Estimated minutes to read at the configured pace. Always
     *  at least 1 when there's any content; 0 when there are no
     *  words. */
    val minutes: Int,
) {
    /** Compact summary for chip display. Returns null when the
     *  page has no text content yet — caller hides the chip. */
    val summary: String?
        get() {
            if (words == 0) return null
            val wordLabel = if (words == 1) "1 word" else "$words words"
            return "$minutes min read · $wordLabel"
        }

    companion object {
        operator fun invoke(
            noteBodies: List<String>,
            wordsPerMinute: Int = 200,
        ): ReleafReadEstimate {
            val total = noteBodies.sumOf { wordsIn(it) }
            val minutes = if (total == 0) 0
            else max(1, (total.toDouble() / wordsPerMinute).roundToInt())
            return ReleafReadEstimate(words = total, minutes = minutes)
        }

        /** Whitespace-tolerant word counter. Splits on whitespace
         *  + newlines and drops empties; close enough for chip
         *  display, doesn't try to be a linguistic tokenizer. */
        private fun wordsIn(body: String): Int =
            body.split(Regex("\\s+")).count { it.isNotBlank() }
    }
}
