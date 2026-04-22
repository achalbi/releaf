/*
 * ScanCategory.kt
 *
 * Seven-way bucketing of scanned documents derived from the OCR'd first
 * word. The scanner section uses it for the per-row category label and
 * the filter chip row above the list.
 *
 * Matching is intentionally loose: the first non-blank line of the
 * recognized text is lowercased, stripped of punctuation, and checked
 * against each category's matcher list. Both single-token ("todo") and
 * two-token ("to do") first-words are considered so OCR quirks around
 * hyphens / spacing don't push a hand-written "To Do" header into
 * General.
 *
 * Anything that doesn't match — including scans with no recognized text
 * at all — lands in [GENERAL]. Order of entries controls the default
 * filter chip ordering in the UI.
 */

package app.releaf.mobile.data.notebook

enum class ScanCategory(
    val label: String,
    internal val matchers: List<String>,
) {
    GENERAL("General", emptyList()),
    TODO("To-Do", listOf("todo", "to-do", "to do", "todos")),
    BRAINSTORMING("Brainstorming", listOf("brainstorming", "brainstorm")),
    SCRIBBLE("Scribble", listOf("scribble", "scribbles")),
    QUADRANT("Quadrant", listOf("quadrant", "quadrants")),
    DAILY("Daily", listOf("daily")),
    PROJECT("Project", listOf("project", "projects"));

    companion object {
        /**
         * Classify a scan by its recognized-text first word. Case-insensitive;
         * strips punctuation from the matched token. Considers both a single
         * first token and the first two tokens joined (space / hyphen /
         * concatenated) so "To Do" and "To-Do" both land on [TODO]. Falls back
         * to [GENERAL] when `text` is null / blank / unrecognized.
         */
        fun fromFirstWord(text: String?): ScanCategory {
            if (text.isNullOrBlank()) return GENERAL
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: return GENERAL
            val normalized = firstLine
                .lowercase()
                .replace(Regex("[^a-z0-9\\s-]"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
            if (normalized.isEmpty()) return GENERAL

            val tokens = normalized.split(' ')
            val first = tokens.first().trim('-').ifBlank { return GENERAL }
            val second = tokens.getOrNull(1)?.trim('-')?.takeIf { it.isNotBlank() }
            val candidates = buildList {
                add(first)
                if (second != null) {
                    add("$first $second")
                    add("$first-$second")
                    add("$first$second")
                }
            }

            return entries.firstOrNull { cat ->
                cat != GENERAL && cat.matchers.any { it in candidates }
            } ?: GENERAL
        }
    }
}
