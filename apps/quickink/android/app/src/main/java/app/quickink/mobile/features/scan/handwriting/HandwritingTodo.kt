/*
 * HandwritingTodo.kt
 *
 * To-do items extracted from a scanned page, and the JSON plumbing
 * that stores them in `notepad_entries.todos`.
 *
 * Wire shape (one array element):
 *
 *   {"id": "...", "text": "...", "done": false,
 *    "sourceCaptureId": "...", "sourcePageIndex": 0, "sourceLineIndex": 3}
 *
 * `id` / `text` / `done` are the cross-app core every reader
 * understands (Releaf Android `TodoItem`, iOS `NotepadTodo`). The
 * three `source*` keys are QuickInk additions so a later migration
 * into a dedicated tasks table can trace each item back to its page
 * and line. They are safe to add because both Releaf decoders ignore
 * unknown keys: Android parses with `Json { ignoreUnknownKeys = true }`
 * (`PageAttachments.kt`), and Swift `Codable` skips keys the struct
 * doesn't declare. Caveat: when Releaf rewrites a todos array after
 * an edit it re-encodes only its own fields, so the `source*` keys
 * are dropped for that entry — acceptable for provenance metadata.
 *
 * Merging never re-encodes existing elements: they are carried over
 * as raw JSON so fields this app doesn't model (Releaf's `priority`)
 * survive a QuickInk write untouched.
 *
 * Mirror of iOS `HandwritingTodo.swift`.
 */

package app.quickink.mobile.features.scan.handwriting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class HandwritingTodo(
    val id: String,
    val text: String,
    val done: Boolean,
    /** Capture the item was read from. Null for items not created by a scan. */
    val sourceCaptureId: String? = null,
    val sourcePageIndex: Int? = null,
    val sourceLineIndex: Int? = null,
) {
    val isFromScan: Boolean get() = sourceCaptureId != null
}

object HandwritingTodoJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** Encode one item. Source keys are omitted when null. */
    fun toElement(todo: HandwritingTodo): JsonObject {
        val map = linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(todo.id),
            "text" to JsonPrimitive(todo.text),
            "done" to JsonPrimitive(todo.done),
        )
        todo.sourceCaptureId?.let { map["sourceCaptureId"] = JsonPrimitive(it) }
        todo.sourcePageIndex?.let { map["sourcePageIndex"] = JsonPrimitive(it) }
        todo.sourceLineIndex?.let { map["sourceLineIndex"] = JsonPrimitive(it) }
        return JsonObject(map)
    }

    /**
     * Parse a `todos` column. Elements without a string `id` and
     * `text` are skipped (not an error); a malformed column reads as
     * empty so a bad row can never crash the editor.
     */
    fun parse(raw: String?): List<HandwritingTodo> = parseArray(raw).mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val id = obj.stringOrNull("id") ?: return@mapNotNull null
        val text = obj.stringOrNull("text") ?: return@mapNotNull null
        HandwritingTodo(
            id = id,
            text = text,
            done = (obj["done"] as? JsonPrimitive)?.booleanOrNull ?: false,
            sourceCaptureId = obj.stringOrNull("sourceCaptureId"),
            sourcePageIndex = (obj["sourcePageIndex"] as? JsonPrimitive)?.intOrNull,
            sourceLineIndex = (obj["sourceLineIndex"] as? JsonPrimitive)?.intOrNull,
        )
    }

    /**
     * Append [items] to an existing `todos` JSON array. Existing
     * elements are kept byte-for-byte as parsed JSON (unknown fields
     * included). An item whose `id` already exists replaces nothing —
     * re-committing the same review is a no-op.
     */
    fun append(existing: String?, items: List<HandwritingTodo>): String {
        val current = parseArray(existing)
        val ids = current.mapNotNull { (it as? JsonObject)?.stringOrNull("id") }.toMutableSet()
        val added = items.filter { ids.add(it.id) }.map { toElement(it) }
        return JsonArray(current + added).toString()
    }

    /**
     * Set `done` on the element with [id], leaving every other field
     * (including ones this app doesn't model) unchanged.
     */
    fun setDone(existing: String?, id: String, done: Boolean): String {
        val updated = parseArray(existing).map { el ->
            val obj = el as? JsonObject
            if (obj != null && obj.stringOrNull("id") == id) {
                JsonObject(obj.toMutableMap().apply { put("done", JsonPrimitive(done)) })
            } else {
                el
            }
        }
        return JsonArray(updated).toString()
    }

    private fun parseArray(raw: String?): List<JsonElement> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            (json.parseToJsonElement(raw) as? JsonArray)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        return if (p.isString) p.jsonPrimitive.content else null
    }
}
