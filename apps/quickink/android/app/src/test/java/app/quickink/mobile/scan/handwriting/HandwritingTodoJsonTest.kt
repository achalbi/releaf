/*
 * HandwritingTodoJsonTest.kt
 *
 * Guards the `notepad_entries.todos` wire shape: QuickInk adds
 * provenance keys but must keep the `{id, text, done}` core Releaf
 * reads, and must not drop fields it doesn't model when it writes.
 * Mirror of iOS `HandwritingTodoJsonTests.swift`.
 */

package app.quickink.mobile.scan.handwriting

import app.quickink.mobile.features.scan.handwriting.HandwritingTodo
import app.quickink.mobile.features.scan.handwriting.HandwritingTodoJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingTodoJsonTest {

    private val scanned = HandwritingTodo(
        id = "t1", text = "buy milk", done = false,
        sourceCaptureId = "cap-1", sourcePageIndex = 0, sourceLineIndex = 4,
    )

    @Test
    fun `append writes the core keys plus provenance`() {
        val out = HandwritingTodoJson.append("[]", listOf(scanned))
        assertEquals(
            """[{"id":"t1","text":"buy milk","done":false,"sourceCaptureId":"cap-1","sourcePageIndex":0,"sourceLineIndex":4}]""",
            out,
        )
    }

    @Test
    fun `append keeps existing elements and their unknown fields`() {
        val existing = """[{"id":"r1","text":"from releaf","done":true,"priority":3}]"""
        val out = Json.parseToJsonElement(HandwritingTodoJson.append(existing, listOf(scanned))) as JsonArray
        assertEquals(2, out.size)
        val first = out[0] as JsonObject
        assertEquals(3, first["priority"]!!.jsonPrimitive.int)
        assertTrue(first["done"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `append is idempotent per id`() {
        val once = HandwritingTodoJson.append("[]", listOf(scanned))
        val twice = HandwritingTodoJson.append(once, listOf(scanned))
        assertEquals(once, twice)
    }

    @Test
    fun `append tolerates null blank and malformed columns`() {
        for (raw in listOf(null, "", "not json", "{}")) {
            assertEquals(1, HandwritingTodoJson.parse(HandwritingTodoJson.append(raw, listOf(scanned))).size)
        }
    }

    @Test
    fun `parse reads releaf-shaped items without provenance`() {
        val parsed = HandwritingTodoJson.parse("""[{"id":"r1","text":"x","done":true,"priority":2},{"bad":1}]""")
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].done)
        assertFalse(parsed[0].isFromScan)
    }

    @Test
    fun `setDone flips one element and keeps unknown fields`() {
        val existing = """[{"id":"r1","text":"x","done":false,"priority":2},{"id":"r2","text":"y","done":false}]"""
        val out = Json.parseToJsonElement(HandwritingTodoJson.setDone(existing, "r1", true)) as JsonArray
        val first = out[0] as JsonObject
        assertTrue(first["done"]!!.jsonPrimitive.boolean)
        assertEquals(2, first["priority"]!!.jsonPrimitive.int)
        assertFalse((out[1] as JsonObject)["done"]!!.jsonPrimitive.boolean)
    }

    /**
     * Releaf decodes todos with `ignoreUnknownKeys = true`
     * (`apps/releaf/.../PageAttachments.kt`). Decode our output the
     * same way into a Releaf-shaped type to prove the provenance keys
     * don't break it.
     */
    @Test
    fun `releaf-style lenient decoder accepts the provenance keys`() {
        val lenient = Json { ignoreUnknownKeys = true }
        val arr = lenient.parseToJsonElement(HandwritingTodoJson.append("[]", listOf(scanned))) as JsonArray
        val obj = arr.single() as JsonObject
        assertEquals("t1", obj["id"]!!.jsonPrimitive.content)
        assertEquals("buy milk", obj["text"]!!.jsonPrimitive.content)
        assertFalse(obj["done"]!!.jsonPrimitive.boolean)
    }
}
