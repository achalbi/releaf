/*
 * HandwritingTodoJsonTests.swift
 *
 * Guards the `notepad_entries.todos` wire shape: QuickInk adds
 * provenance keys but keeps the `{id, text, done}` core, and never
 * drops fields it doesn't model when it writes. Mirror of Android
 * `HandwritingTodoJsonTest.kt`.
 */

import XCTest
import ReleafCoreNotes
@testable import QuickInkFeatures

final class HandwritingTodoJsonTests: XCTestCase {

    private let scanned = HandwritingTodo(
        id: "t1", text: "buy milk", done: false,
        sourceCaptureId: "cap-1", sourcePageIndex: 0, sourceLineIndex: 4
    )

    private func objects(_ json: String) -> [[String: Any]] {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [[String: Any]] ?? []
    }

    func testAppendWritesCoreKeysPlusProvenance() {
        let out = HandwritingTodoJson.append("[]", items: [scanned])
        XCTAssertEqual(
            out,
            #"[{"done":false,"id":"t1","sourceCaptureId":"cap-1","sourceLineIndex":4,"sourcePageIndex":0,"text":"buy milk"}]"#
        )
    }

    func testAppendKeepsExistingElementsAndUnknownFields() {
        let existing = #"[{"id":"r1","text":"from releaf","done":true,"priority":3}]"#
        let out = objects(HandwritingTodoJson.append(existing, items: [scanned]))
        XCTAssertEqual(out.count, 2)
        XCTAssertEqual(out[0]["priority"] as? Int, 3)
        XCTAssertEqual(out[0]["done"] as? Bool, true)
    }

    func testAppendIsIdempotentPerId() {
        let once = HandwritingTodoJson.append("[]", items: [scanned])
        XCTAssertEqual(HandwritingTodoJson.append(once, items: [scanned]), once)
    }

    func testAppendToleratesNilBlankAndMalformedColumns() {
        for raw in [nil, "", "not json", "{}"] as [String?] {
            XCTAssertEqual(HandwritingTodoJson.parse(HandwritingTodoJson.append(raw, items: [scanned])).count, 1)
        }
    }

    func testParseReadsReleafShapedItemsWithoutProvenance() {
        let parsed = HandwritingTodoJson.parse(#"[{"id":"r1","text":"x","done":true,"priority":2},{"bad":1}]"#)
        XCTAssertEqual(parsed.count, 1)
        XCTAssertTrue(parsed[0].done)
        XCTAssertFalse(parsed[0].isFromScan)
    }

    func testSetDoneFlipsOneElementAndKeepsUnknownFields() {
        let existing = #"[{"id":"r1","text":"x","done":false,"priority":2},{"id":"r2","text":"y","done":false}]"#
        let out = objects(HandwritingTodoJson.setDone(existing, id: "r1", done: true))
        XCTAssertEqual(out[0]["done"] as? Bool, true)
        XCTAssertEqual(out[0]["priority"] as? Int, 2)
        XCTAssertEqual(out[1]["done"] as? Bool, false)
    }

    /// The shared `NotepadTodo` decoder (what Releaf iOS and the
    /// QuickInk note editor use) must read our output and keep the
    /// provenance through a decode → toggle → encode round trip.
    func testNotepadTodoRoundTripKeepsProvenance() {
        let json = HandwritingTodoJson.append("[]", items: [scanned])
        let decoded = json.parseTodos()
        XCTAssertEqual(decoded.count, 1)
        XCTAssertEqual(decoded[0].text, "buy milk")
        let toggled = [decoded[0].with(done: true)].toJsonString()
        let back = HandwritingTodoJson.parse(toggled)
        XCTAssertEqual(back.first?.done, true)
        XCTAssertEqual(back.first?.sourceCaptureId, "cap-1")
        XCTAssertEqual(back.first?.sourceLineIndex, 4)
    }

    /// Rows without provenance must still encode exactly as before
    /// the optional fields were added (nil keys omitted).
    func testNotepadTodoWithoutProvenanceEncodesCoreOnly() {
        let json = [NotepadTodo(id: "a", text: "b", done: false)].toJsonString()
        XCTAssertFalse(json.contains("source"))
    }
}
