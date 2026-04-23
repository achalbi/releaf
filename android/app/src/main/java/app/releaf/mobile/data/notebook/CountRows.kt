/*
 * CountRows.kt
 *
 * Aggregate-query result rows used by the notebook / chapter list screens
 * to render per-row counts without round-tripping through the ViewModel for
 * each parent. Kept as dumb POJOs so Room's cursor mapping stays cheap.
 */

package app.releaf.mobile.data.notebook

/** One entry in the notebook-wide chapter / page count feed. */
data class NotebookCountRow(
    val notebookId: String,
    val count: Int,
)

/** One entry in the chapter-wide page count feed. */
data class ChapterCountRow(
    val chapterId: String,
    val count: Int,
)
