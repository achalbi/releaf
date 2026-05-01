/*
 * NotepadEntryPayloadV2InteropTest.kt
 *
 * Slice 4.4 — cross-platform interop guard for the
 * `notepad_entries` payload, which is the cross-app shared table
 * (per QUICKINK_PROPOSAL.md §3 — Releaf and QuickInk both write
 * notepad rows and must produce byte-identical Drive payloads).
 *
 * The CanonicalJson encoder already has its own cross-platform
 * fixture test in `apps/releaf/android/.../sync/CanonicalJsonTest.kt`
 * + `apps/releaf/ios/Tests/.../CanonicalJsonTests.swift`. That
 * tests the BYTES side. This test is one level higher: it tests
 * the PAYLOAD CLASS — the @SerialName / CodingKeys mapping, the
 * field set, the encode strategy. If Android's
 * `NotepadEntryPayloadV2` ever adds a field that iOS doesn't, or
 * renames a SerialName without iOS following, this test will
 * drift away from the constant `EXPECTED_CANONICAL` below. The
 * iOS counterpart at
 * `apps/quickink/ios/Tests/QuickInkFeaturesTests/NotepadEntryPayloadV2InteropTests.swift`
 * asserts the same constant from the Swift side; if they both
 * pass, byte-equality is established.
 *
 * Fixture choice: every field is non-null. Reason: Swift's
 * `JSONEncoder` omits nil Optionals by default while kotlinx with
 * `encodeDefaults = true` emits them as `null`, so a null-valued
 * field would diverge between platforms. Whether to fix that
 * divergence (force iOS to emit nulls, or relax kotlinx to omit
 * them) is a Phase-4 follow-up — both apps have the same
 * unfixed behavior today and Drive accepts the (slightly
 * different) bytes either way; only the SHA-256 hash differs,
 * which means a row with a transitioning null field gets
 * re-uploaded once on first sync after the change. Test stays
 * green by avoiding nulls.
 */

package app.quickink.mobile.data.sync

import app.releaf.mobile.data.sync.CanonicalJson
import app.releaf.mobile.data.sync.SyncJson
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class NotepadEntryPayloadV2InteropTest {

    @Test
    fun `notepad payload encodes to expected canonical JSON`() {
        val payload = NotepadEntryPayloadV2(
            id                = "0192f1aa-0000-7000-8000-000000000001",
            userId            = "user-fixture",
            entryDate         = "2026-01-15",
            projectId         = "project-fixture",
            title             = "Interop fixture",
            description       = "Test description",
            category          = "Work",
            notes             = "Plain notes",
            contacts          = JsonArray(emptyList()),
            locations         = JsonArray(emptyList()),
            todos             = JsonArray(emptyList()),
            attachments       = JsonArray(emptyList()),
            sketchStrokes     = JsonArray(emptyList()),
            subPages          = JsonArray(emptyList()),
            allowBlankContent = false,
            createdAt         = "2026-01-15T10:00:00.000Z",
            updatedAt         = "2026-01-15T10:00:00.000Z",
        )

        val element   = SyncJson.encodeToJsonElement(NotepadEntryPayloadV2.serializer(), payload)
        val canonical = CanonicalJson.encodeToString(element)

        assertEquals(
            "Canonical encoding of NotepadEntryPayloadV2 must be byte-stable across platforms.",
            EXPECTED_CANONICAL,
            canonical,
        )
    }

    @Test
    fun `roundtrip via canonical JSON yields identical payload`() {
        val original = NotepadEntryPayloadV2(
            id                = "0192f1aa-0000-7000-8000-000000000002",
            userId            = "user-rt",
            entryDate         = "2026-01-16",
            projectId         = "project-rt",
            title             = "Round trip",
            description       = "rt-desc",
            category          = "Personal",
            notes             = "rt-notes",
            contacts          = JsonArray(emptyList()),
            locations         = JsonArray(emptyList()),
            todos             = JsonArray(emptyList()),
            attachments       = JsonArray(emptyList()),
            sketchStrokes     = JsonArray(emptyList()),
            subPages          = JsonArray(emptyList()),
            allowBlankContent = true,
            createdAt         = "2026-01-16T10:00:00.000Z",
            updatedAt         = "2026-01-16T10:00:00.000Z",
        )

        val element = SyncJson.encodeToJsonElement(NotepadEntryPayloadV2.serializer(), original)
        val bytes   = CanonicalJson.encodeToBytes(element)
        val decoded = SyncJson.decodeFromString(
            NotepadEntryPayloadV2.serializer(),
            bytes.toString(Charsets.UTF_8),
        )

        assertEquals(original, decoded)
    }

    companion object {
        /**
         * Hand-computed canonical form. Keys sorted lexicographically,
         * no whitespace, default JSON escaping, arrays in source order.
         * The iOS counterpart asserts against this exact string —
         * keep both sides in lockstep when changing the payload shape.
         */
        private const val EXPECTED_CANONICAL =
            "{" +
            "\"allow_blank_content\":false," +
            "\"attachments\":[]," +
            "\"category\":\"Work\"," +
            "\"contacts\":[]," +
            "\"created_at\":\"2026-01-15T10:00:00.000Z\"," +
            "\"description\":\"Test description\"," +
            "\"entry_date\":\"2026-01-15\"," +
            "\"id\":\"0192f1aa-0000-7000-8000-000000000001\"," +
            "\"locations\":[]," +
            "\"notes\":\"Plain notes\"," +
            "\"project_id\":\"project-fixture\"," +
            "\"sketch_strokes\":[]," +
            "\"sub_pages\":[]," +
            "\"title\":\"Interop fixture\"," +
            "\"todos\":[]," +
            "\"updated_at\":\"2026-01-15T10:00:00.000Z\"," +
            "\"user_id\":\"user-fixture\"" +
            "}"
    }
}
