package app.releaf.mobile.data.notebook

import androidx.room.ColumnInfo

/**
 * One row of the "pages that carry at least one todo" stream, used by
 * the library header's Open-todos modal. Carries the page's todos
 * JSON plus the notebook / chapter labels we need to render a
 * navigable row without an extra round-trip.
 */
data class PageTodosRow(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "todos")
    val todos: String,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: String,
    @ColumnInfo(name = "notebookId")
    val notebookId: String,
    @ColumnInfo(name = "notebookTitle")
    val notebookTitle: String,
    @ColumnInfo(name = "chapterId")
    val chapterId: String,
    @ColumnInfo(name = "chapterTitle")
    val chapterTitle: String,
)
