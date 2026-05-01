/*
 * NotepadEntryPayloadV2InteropTests.swift
 *
 * Slice 4.4 — cross-platform interop guard for the
 * `notepad_entries` payload (the cross-app shared table per
 * QUICKINK_PROPOSAL.md §3). Mirror of Android's
 * `apps/quickink/android/.../sync/NotepadEntryPayloadV2InteropTest.kt`.
 *
 * Both tests assert the same `EXPECTED_CANONICAL` string. If
 * Android adds a field that iOS doesn't (or renames a CodingKey),
 * one side drifts and both tests start failing. CI catches the
 * divergence before sync payload hashes start mismatching across
 * devices.
 *
 * Fixture choice: every field is non-null. Reason: Swift's
 * `JSONEncoder` omits nil Optionals by default while Android's
 * kotlinx with `encodeDefaults = true` emits them as `null`, so a
 * null-valued field would diverge between platforms (a
 * Phase-4 follow-up — see the Android test's header for
 * details). Test stays green by avoiding nulls.
 */

import XCTest
@testable import QuickInkFeatures
import ReleafCoreSync

final class NotepadEntryPayloadV2InteropTests: XCTestCase {

    func testNotepadPayloadEncodesToExpectedCanonicalJSON() throws {
        let payload = NotepadEntryPayloadV2(
            id:                "0192f1aa-0000-7000-8000-000000000001",
            userId:            "user-fixture",
            entryDate:         "2026-01-15",
            projectId:         "project-fixture",
            title:             "Interop fixture",
            description:       "Test description",
            category:          "Work",
            notes:             "Plain notes",
            contacts:          JSONAny([Any]()),
            locations:         JSONAny([Any]()),
            todos:             JSONAny([Any]()),
            attachments:       JSONAny([Any]()),
            sketchStrokes:     JSONAny([Any]()),
            subPages:          JSONAny([Any]()),
            allowBlankContent: false,
            createdAt:         "2026-01-15T10:00:00.000Z",
            updatedAt:         "2026-01-15T10:00:00.000Z"
        )

        let bytes     = try CanonicalJson.encodeToData(encodable: payload)
        let canonical = String(data: bytes, encoding: .utf8) ?? ""

        XCTAssertEqual(
            canonical,
            Self.expectedCanonical,
            "Canonical encoding of NotepadEntryPayloadV2 must be byte-stable across platforms."
        )
    }

    func testRoundtripViaCanonicalJSONYieldsIdenticalPayload() throws {
        let original = NotepadEntryPayloadV2(
            id:                "0192f1aa-0000-7000-8000-000000000002",
            userId:            "user-rt",
            entryDate:         "2026-01-16",
            projectId:         "project-rt",
            title:             "Round trip",
            description:       "rt-desc",
            category:          "Personal",
            notes:             "rt-notes",
            contacts:          JSONAny([Any]()),
            locations:         JSONAny([Any]()),
            todos:             JSONAny([Any]()),
            attachments:       JSONAny([Any]()),
            sketchStrokes:     JSONAny([Any]()),
            subPages:          JSONAny([Any]()),
            allowBlankContent: true,
            createdAt:         "2026-01-16T10:00:00.000Z",
            updatedAt:         "2026-01-16T10:00:00.000Z"
        )

        let bytes   = try CanonicalJson.encodeToData(encodable: original)
        let decoded = try JSONDecoder().decode(NotepadEntryPayloadV2.self, from: bytes)

        XCTAssertEqual(original, decoded)
    }

    /// Hand-computed canonical form. Keys sorted lexicographically,
    /// no whitespace, default JSON escaping, arrays in source order.
    /// The Android counterpart asserts against this exact string —
    /// keep both sides in lockstep when changing the payload shape.
    private static let expectedCanonical: String = ""
        + "{"
        + "\"allow_blank_content\":false,"
        + "\"attachments\":[],"
        + "\"category\":\"Work\","
        + "\"contacts\":[],"
        + "\"created_at\":\"2026-01-15T10:00:00.000Z\","
        + "\"description\":\"Test description\","
        + "\"entry_date\":\"2026-01-15\","
        + "\"id\":\"0192f1aa-0000-7000-8000-000000000001\","
        + "\"locations\":[],"
        + "\"notes\":\"Plain notes\","
        + "\"project_id\":\"project-fixture\","
        + "\"sketch_strokes\":[],"
        + "\"sub_pages\":[],"
        + "\"title\":\"Interop fixture\","
        + "\"todos\":[],"
        + "\"updated_at\":\"2026-01-15T10:00:00.000Z\","
        + "\"user_id\":\"user-fixture\""
        + "}"
}
