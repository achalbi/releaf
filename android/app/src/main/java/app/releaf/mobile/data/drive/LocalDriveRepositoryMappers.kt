/*
 * LocalDriveRepositoryMappers.kt
 *
 * Bidirectional mappers between Room persistence shapes
 * (`*Entity`) and the Drive-facing domain shapes (`Page`,
 * `Notebook`, `Chapter`, etc. in `data/domain/Models.kt`).
 *
 * The persistence layer uses the Notepad's JSON-in-column
 * convention (`contacts`, `locations`, `todos`, `attachments`,
 * `tags` are TEXT columns containing serialized JSON arrays).
 * The domain layer is richer and typed — `List<Contact>`,
 * `List<LocationPin>`, `List<TodoItem>`, separate `List<Photo>` /
 * `List<VoiceNote>` / `List<ScannedDocument>` arrays demuxed from
 * the one `attachments` JSON column by `Attachment.type`.
 *
 * Naming clash: `data.notebook.Contact` (the JSON shape) and
 * `data.domain.Contact` (the Drive domain shape) coexist in the
 * tree. The mappers import them with aliases (`StoredContact` for
 * the persistence shape, plain `Contact` for the domain) so call
 * sites read clearly. Same for TodoItem.
 *
 * Mapping is intentionally lossy on a few decoration fields the
 * persistence Attachment doesn't carry (no Photo.caption, etc.).
 * The capture UI doesn't expose them for editing today, so the
 * round-trip stays consistent in practice.
 *
 * Mirrors `LocalDriveRepositoryMappers.swift` on iOS.
 */

package app.releaf.mobile.data.drive

import app.releaf.mobile.data.domain.Chapter
import app.releaf.mobile.data.domain.Contact
import app.releaf.mobile.data.domain.LocationPin
import app.releaf.mobile.data.domain.Note
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.data.domain.NotebookStatus
import app.releaf.mobile.data.domain.Page
import app.releaf.mobile.data.domain.PageCounts
import app.releaf.mobile.data.domain.PageSummary
import app.releaf.mobile.data.domain.Photo
import app.releaf.mobile.data.domain.ScannedDocument
import app.releaf.mobile.data.domain.TodoItem
import app.releaf.mobile.data.domain.VoiceNote
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.GeoLocation
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notebook.StoredPageNote
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parsePageNotes
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notebook.toJsonString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale
import app.releaf.mobile.data.notebook.Contact as StoredContact
import app.releaf.mobile.data.notebook.TodoItem as StoredTodo

// MARK: - Date helpers

/**
 * Parse the ISO timestamps the schema persists. The Room column
 * defaults emit the `strftime('%Y-%m-%dT%H:%M:%fZ', 'now')` form
 * (with millisecond fractions) — `Instant.parse` handles both that
 * and the plain second-precision form.
 */
internal fun parseInstant(s: String?): Instant? {
    if (s.isNullOrBlank()) return null
    return try {
        Instant.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }
}

private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/** Format an Instant for storage. Always emits the ISO form so
 *  reads round-trip through `parseInstant`. */
internal fun formatInstant(at: Instant): String = isoFormatter.format(at)

// MARK: - Color token <-> hex

/**
 * The four leaf-theme primary hexes that the design system
 * resolves through `ShelfTheme.palette(...)`. Keeping the lookup
 * here so the data layer doesn't depend on the design system.
 */
internal object LeafTokenHex {
    const val CORAL  = "#E07856"
    const val GREEN  = "#7AA874"
    const val YELLOW = "#F4C430"
    const val DRY    = "#B8956A"

    /**
     * Inverse map: hex string → token name. Returns null when the
     * hex doesn't match one of the four canonical leaf themes —
     * caller falls through to a null colorToken (UI defaults to
     * the global accent palette).
     */
    fun tokenForHex(hex: String?): String? {
        val normalized = hex?.uppercase()?.removePrefix("#") ?: return null
        return when (normalized) {
            "E07856" -> "coral"
            "7AA874" -> "green"
            "F4C430" -> "yellow"
            "B8956A" -> "dry"
            else     -> null
        }
    }

    /**
     * Forward map: token name → canonical hex with leading `#`.
     * Null token round-trips as null so unknown tokens don't
     * silently land in the persisted column.
     */
    fun hexForToken(token: String?): String? = when (token?.lowercase(Locale.ROOT)) {
        "coral"  -> CORAL
        "green"  -> GREEN
        "yellow" -> YELLOW
        "dry"    -> DRY
        else     -> null
    }
}

// MARK: - Tags JSON

private val tagsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

internal fun parseTagsJson(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return try {
        tagsJson.decodeFromString(ListSerializer(String.serializer()), json)
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}

internal fun encodeTagsJson(tags: List<String>): String =
    tagsJson.encodeToString(ListSerializer(String.serializer()), tags)

// MARK: - PageEntity ↔ Page

/**
 * Decode the JSON child columns + scalar fields into the
 * Drive-facing `Page` shape. Caller passes `notebookId` because
 * PageEntity stores only `chapterId` — the parent notebook is
 * resolved one level up via the chapter row.
 */
internal fun PageEntity.toDomainPage(notebookId: String): Page {
    val storedContacts = contacts.parseContacts()
    val storedLocations = locations.parseLocations()
    val storedTodos = todos.parseTodos()
    val storedAttachments = attachments.parseAttachments()
    val tagList = parseTagsJson(tags)

    // Demux the single `attachments` array into the three typed
    // domain collections by `type`. Unknown types fall through and
    // are silently dropped — better to lose an exotic attachment
    // than crash the page load.
    val photos = mutableListOf<Photo>()
    val voices = mutableListOf<VoiceNote>()
    val scans = mutableListOf<ScannedDocument>()
    for (att in storedAttachments) {
        val captured = parseInstant(att.capturedAt) ?: Instant.now()
        when (att.type) {
            Attachment.TYPE_PHOTO -> photos.add(
                Photo(
                    id = att.id,
                    driveFileId = att.uri,
                    caption = null,
                    capturedAt = captured,
                    width = null,
                    height = null,
                ),
            )
            Attachment.TYPE_VOICE -> voices.add(
                VoiceNote(
                    id = att.id,
                    driveFileId = att.uri,
                    durationMs = att.durationMs ?: 0L,
                    recordedAt = captured,
                    transcription = att.transcript,
                ),
            )
            Attachment.TYPE_SCAN -> scans.add(
                ScannedDocument(
                    id = att.id,
                    driveFileId = att.uri,
                    title = att.title ?: att.previewUri ?: "Scan",
                    pageCount = 1,
                    scannedAt = captured,
                ),
            )
        }
    }

    // Notes have two representations on disk:
    //   - `notes` — the markdown blob FTS indexes against. Joined
    //     bodies; loses individual identity.
    //   - `pageNotesJson` — the typed array (id + body + createdAt
    //     per element). Source of truth as of v18.
    // Prefer the typed array when populated; fall back to wrapping
    // the legacy markdown as one synthesized Note for rows written
    // before v18 (the migration backfills the column to `'[]'` so
    // legacy rows look "empty" here and trigger the fallback).
    val storedNotes = pageNotesJson.parsePageNotes()
    val noteList: List<Note> = when {
        storedNotes.isNotEmpty() -> storedNotes.map { stored ->
            Note(
                id = stored.id,
                body = stored.body,
                createdAt = parseInstant(stored.createdAt) ?: Instant.now(),
            )
        }
        notes.isBlank() -> emptyList()
        else -> listOf(
            Note(
                id = "$id-note",
                body = notes,
                createdAt = parseInstant(updatedAt) ?: Instant.now(),
            ),
        )
    }

    return Page(
        id = id,
        notebookId = notebookId,
        chapterId = chapterId,
        title = title.orEmpty(),
        capturedOn = capturedOnDisplay(),
        updatedAt = parseInstant(updatedAt) ?: Instant.now(),
        notes = noteList,
        photos = photos,
        voiceNotes = voices,
        todoItems = storedTodos.mapIndexed { idx, todo ->
            TodoItem(
                id = todo.id,
                body = todo.text,
                done = todo.done,
                position = idx,
            )
        },
        scannedDocuments = scans,
        contacts = storedContacts.map { c ->
            // The persistence Contact is wider than the domain
            // Contact — fold the rich fields (role, organization,
            // title, location, website) into the single domain
            // `notes` slot so nothing is silently lost.
            Contact(
                id = c.id,
                name = c.name,
                phone = c.phone,
                email = c.email,
                notes = listOfNotNull(c.title, c.organization, c.role, c.location, c.website)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" · "),
            )
        },
        locations = storedLocations.map { loc ->
            LocationPin(
                id = loc.id,
                name = loc.address.orEmpty(),
                latitude = loc.lat,
                longitude = loc.lng,
                capturedAt = parseInstant(loc.capturedAt) ?: Instant.now(),
                notes = null,
            )
        },
        tags = tagList,
        archivedAt = parseInstant(archivedAt),
    )
}

/** Friendlier "Sat · Apr 25 · 2026" style for the
 *  `capturedOn` field, derived from `created_at`. */
private fun PageEntity.capturedOnDisplay(): String? {
    val instant = parseInstant(createdAt) ?: return null
    val formatter = SimpleDateFormat("EEE · MMM d · yyyy", Locale.getDefault())
    return formatter.format(Date.from(instant))
}

/**
 * Build a `PageSummary` for chapter list rows. Counts come from
 * the JSON child arrays without fully decoding into rich domain
 * types — we just need the array lengths.
 */
internal fun PageEntity.toPageSummary(): PageSummary {
    val attachmentList = attachments.parseAttachments()
    val photoCount = attachmentList.count { it.type == Attachment.TYPE_PHOTO }
    val voiceCount = attachmentList.count { it.type == Attachment.TYPE_VOICE }
    val scanCount  = attachmentList.count { it.type == Attachment.TYPE_SCAN  }
    return PageSummary(
        id = id,
        title = title.orEmpty(),
        capturedOn = capturedOnDisplay(),
        updatedAt = parseInstant(updatedAt) ?: Instant.now(),
        counts = PageCounts(
            photos = photoCount,
            voiceNotes = voiceCount,
            todoItems = todos.parseTodos().size,
            scannedDocuments = scanCount,
            contacts = contacts.parseContacts().size,
            locations = locations.parseLocations().size,
        ),
        tags = parseTagsJson(tags),
    )
}

/**
 * Build a `PageEntity` ready to upsert, encoding the typed domain
 * collections back into the Notepad-shape JSON columns. Lossy on
 * a few decoration fields the persistence layer doesn't carry —
 * see file header for rationale.
 */
internal fun Page.toEntity(): PageEntity {
    val contactsJson: String = contacts.map { c ->
        // Contact.notes folds back into role/title/organization
        // would lose the " · "-separated context. Acceptable since
        // the capture UI doesn't expose those as separate fields.
        StoredContact(
            id = c.id,
            name = c.name,
            role = c.notes,
            phone = c.phone,
            landline = null,
            email = c.email,
            title = null,
            organization = null,
            location = null,
            website = null,
        )
    }.toJsonString()

    val locationsJson: String = locations.map { l ->
        GeoLocation(
            id = l.id,
            lat = l.latitude,
            lng = l.longitude,
            address = l.name.takeIf { it.isNotEmpty() },
            capturedAt = formatInstant(l.capturedAt),
        )
    }.toJsonString()

    val todosJson: String = todoItems
        .sortedBy { it.position }
        .map { t -> StoredTodo(id = t.id, text = t.body, done = t.done) }
        .toJsonString()

    // Re-mux the three typed attachment domain types into one
    // attachments JSON, tagged by `type`. Order is photos →
    // voice → scans so callers that didn't sort by capturedAt
    // get a deterministic on-disk shape.
    val attachmentJson: String = (
        photos.map { p ->
            Attachment(
                id = p.id,
                type = Attachment.TYPE_PHOTO,
                uri = p.driveFileId.orEmpty(),
                previewUri = p.caption,
                capturedAt = formatInstant(p.capturedAt),
            )
        } +
        voiceNotes.map { v ->
            Attachment(
                id = v.id,
                type = Attachment.TYPE_VOICE,
                uri = v.driveFileId.orEmpty(),
                previewUri = null,
                capturedAt = formatInstant(v.recordedAt),
                durationMs = v.durationMs,
                transcript = v.transcription,
            )
        } +
        scannedDocuments.map { s ->
            Attachment(
                id = s.id,
                type = Attachment.TYPE_SCAN,
                uri = s.driveFileId.orEmpty(),
                previewUri = s.title,
                capturedAt = formatInstant(s.scannedAt),
                title = s.title,
            )
        }
    ).toJsonString()

    // Notes have two representations on disk:
    //   - `pageNotesJson` — typed array (id + body + createdAt per
    //     element). Source of truth for note identity.
    //   - `notes` — markdown blob, joined bodies. Kept in sync so
    //     the FTS triggers index against it; the join is a derived
    //     view, not the canonical store.
    val pageNotesJsonText: String = notes.map { n ->
        StoredPageNote(
            id = n.id,
            body = n.body,
            createdAt = formatInstant(n.createdAt),
        )
    }.toJsonString()
    val ftsNotesText: String = notes.joinToString("\n\n") { it.body }

    val nowStr = formatInstant(updatedAt)
    return PageEntity(
        id = id,
        chapterId = chapterId,
        projectId = null,
        templateId = null,
        title = title.takeIf { it.isNotEmpty() },
        notes = ftsNotesText,
        contacts = contactsJson,
        locations = locationsJson,
        todos = todosJson,
        attachments = attachmentJson,
        sketchStrokes = "[]",
        subPages = "[]",
        position = 1024L,
        conflictStub = null,
        driveFileId = null,
        createdAt = nowStr,
        updatedAt = nowStr,
        dirty = true,
        deletedAt = null,
        tags = encodeTagsJson(tags),
        pageNotesJson = pageNotesJsonText,
        archivedAt = archivedAt?.let(::formatInstant),
    )
}

// MARK: - NotebookEntity ↔ Notebook

/**
 * Decode an entity into the Drive-facing `Notebook` domain shape.
 * Counts default to 0 — the count-aware reads should go through
 * `LocalDriveRepository.fetchNotebooksWithCounts(...)` which joins
 * live page + chapter counts. This single-row mapper is only
 * suitable when the caller already has the counts elsewhere or
 * doesn't need them (e.g. notebook-detail header).
 */
internal fun NotebookEntity.toDomainNotebook(
    chapterCount: Int = 0,
    pageCount: Int = 0,
    shelfName: String? = null,
): Notebook {
    val archived = parseInstant(archivedAt)
    return Notebook(
        id = id,
        title = title,
        description = description,
        colorToken = LeafTokenHex.tokenForHex(colorHex),
        position = position.toInt(),
        archivedAt = archived,
        updatedAt = parseInstant(updatedAt) ?: Instant.now(),
        chapterCount = chapterCount,
        pageCount = pageCount,
        shelfName = shelfName,
        volumeNumber = if (volumeNumber > 1) volumeNumber else null,
        status = if (archived == null) NotebookStatus.Active else NotebookStatus.Archived,
        iconKey = null,
        shelfId = shelfId,
        seriesId = seriesId,
        seriesVolumeNumber = volumeNumber,
        volumeLabel = volumeName,
    )
}

// MARK: - ChapterEntity ↔ Chapter

/**
 * Decode the chapter row into the Drive-facing `Chapter` shape.
 * Page summaries default to empty — the call site (typically
 * `loadNotebook(id)`) is expected to fetch the page rows and
 * build the summaries via `PageEntity.toPageSummary()`.
 */
internal fun ChapterEntity.toDomainChapter(pages: List<PageSummary> = emptyList()): Chapter =
    Chapter(
        id = id,
        notebookId = notebookId,
        title = title,
        position = position.toInt(),
        updatedAt = parseInstant(updatedAt) ?: Instant.now(),
        pages = pages,
        archivedAt = parseInstant(archivedAt),
    )
