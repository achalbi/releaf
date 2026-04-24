package app.releaf.mobile.data.notebook

import androidx.room.ColumnInfo

/**
 * Lightweight notebook-tab search result. Carries only the fields the UI
 * needs to disambiguate a page hit inside the global notebook search.
 */
data class PageSearchHit(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "notes")
    val notes: String,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: String,
    @ColumnInfo(name = "notebookTitle")
    val notebookTitle: String,
    @ColumnInfo(name = "chapterTitle")
    val chapterTitle: String,
)
