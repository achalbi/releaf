/*
 * FtsQuery.kt
 *
 * Shared sanitizer that turns a free-form user query into an FTS5 MATCH
 * expression. Factored out of NotepadRepository so both notepad and page
 * search go through the same grammar and edge cases (empty string → null,
 * punctuation stripped, prefix wildcards appended).
 *
 * Intentionally simple — no phrase queries, no NEAR, no column filters. We
 * can grow the grammar when the UI needs it. Returns null if the query
 * degenerates to nothing usable; callers should treat that as "empty result
 * set" rather than passing the empty string to SQLite (which would raise
 * a MATCH error).
 *
 * PR #4b moved this from `apps/releaf/android/.../data/common/FtsQuery.kt`
 * into :shared:data. Behavior unchanged.
 */

package app.releaf.mobile.data.common

object FtsQuery {
    fun build(rawQuery: String): String? {
        val terms = rawQuery
            .lowercase()
            .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { "$it*" }

        return terms.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
