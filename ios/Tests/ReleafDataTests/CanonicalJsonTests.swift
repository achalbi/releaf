/*
 * CanonicalJsonTests.swift
 *
 * Cross-platform fixture test — iOS half. Mirror of Android's
 * `CanonicalJsonTest.kt`. The fixture at
 * `design-system/fixtures/canonical-json-fixture.json` is shared
 * between platforms; so are `expectedCanonical` and `expectedSha256`.
 *
 * If either constant is updated, the Android test must be updated in
 * the same PR — CI should fail loudly when the two drift.
 */

import XCTest
@testable import ReleafData

final class CanonicalJsonTests: XCTestCase {

    func testFixtureCanonicalFormMatchesSharedExpectation() throws {
        let url = try fixtureURL()
        let data = try Data(contentsOf: url)
        let tree = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])

        let canonical = CanonicalJson.encodeToString(tree)
        XCTAssertEqual(canonical, expectedCanonical,
                       "Canonical output must be byte-identical across platforms.")

        let hex = sha256Hex(Data(canonical.utf8))
        XCTAssertEqual(hex, expectedSha256,
                       "SHA-256 of canonical bytes must match expected.")
    }

    func testRepeatedCanonicalizationIsIdempotent() throws {
        let url = try fixtureURL()
        let data = try Data(contentsOf: url)
        let tree = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])

        let once  = CanonicalJson.encodeToString(tree)
        let treeAgain = try JSONSerialization.jsonObject(
            with: Data(once.utf8), options: [.fragmentsAllowed])
        let twice = CanonicalJson.encodeToString(treeAgain)

        XCTAssertEqual(once, twice)
    }

    // MARK: - Helpers

    private func fixtureURL() throws -> URL {
        // SwiftPM copies the fixture into the test bundle via the
        // `resources: [.copy(...)]` directive in Package.swift.
        guard let url = Bundle.module.url(
            forResource: "canonical-json-fixture",
            withExtension: "json"
        ) else {
            XCTFail("Fixture not found in Bundle.module")
            throw CocoaError(.fileReadNoSuchFile)
        }
        return url
    }

    // Hand-computed from the fixture; must match the Android test's
    // EXPECTED_CANONICAL / EXPECTED_SHA256 constants.
    private let expectedCanonical =
        "{\"alpha\":\"first-alphabetical\"," +
        "\"array_with_objects\":[{\"key\":\"b\",\"order\":1},{\"key\":\"a\",\"order\":2}]," +
        "\"booleans\":[true,false]," +
        "\"empty_array\":[]," +
        "\"empty_object\":{}," +
        "\"escapes\":\"quote:\\\" backslash:\\\\ newline:\\n tab:\\t\"," +
        "\"integers\":[0,1,-1,1024,9007199254740991]," +
        "\"nested\":{\"a_key\":1,\"m_key\":2,\"z_key\":3}," +
        "\"nulls\":[null,null,null]," +
        "\"unicode\":\"héron + naïve + 日本語\"," +
        "\"zulu\":\"last-alphabetical\"}"

    private let expectedSha256 =
        "f5af33b6b766125cc4cc6026a41130be6129e2f5a697d8e161b94f631a5b02a6"
}
