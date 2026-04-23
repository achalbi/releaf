/*
 * CanonicalJsonTest.kt
 *
 * Cross-platform fixture test. Feeds the fixture at
 * `design-system/fixtures/canonical-json-fixture.json` through
 * [CanonicalJson.encodeToString] and asserts:
 *
 *   1. The output is byte-identical to a hand-written canonical form
 *      (`EXPECTED_CANONICAL`).
 *   2. The SHA-256 hex of the canonical UTF-8 bytes matches
 *      `EXPECTED_SHA256`.
 *
 * The iOS `CanonicalJsonTests.swift` test asserts against the same
 * constants. If one platform drifts, both tests start failing and
 * we catch the divergence in CI before sync payload hashes start
 * mismatching across devices.
 */

package app.releaf.mobile.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CanonicalJsonTest {

    @Test
    fun `fixture canonical form matches shared expectation`() {
        val raw = fixtureFile().readText(Charsets.UTF_8)
        val element: JsonElement = Json.parseToJsonElement(raw)

        val canonical = CanonicalJson.encodeToString(element)
        assertEquals(
            "Canonical output must be byte-identical across platforms.",
            EXPECTED_CANONICAL,
            canonical,
        )

        val hex = sha256Hex(canonical.toByteArray(Charsets.UTF_8))
        assertEquals(
            "SHA-256 of canonical bytes must match expected.",
            EXPECTED_SHA256,
            hex,
        )
    }

    @Test
    fun `repeated canonicalization is idempotent`() {
        val raw = fixtureFile().readText(Charsets.UTF_8)
        val once  = CanonicalJson.encodeToString(Json.parseToJsonElement(raw))
        val twice = CanonicalJson.encodeToString(Json.parseToJsonElement(once))
        assertEquals(once, twice)
    }

    /** Walk up from the module dir to find the repo-relative fixture. */
    private fun fixtureFile(): File {
        // Gradle runs tests with the module dir as cwd.
        val candidates = listOf(
            File("../design-system/fixtures/canonical-json-fixture.json"),
            File("../../design-system/fixtures/canonical-json-fixture.json"),
            File("design-system/fixtures/canonical-json-fixture.json"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("canonical-json-fixture.json not found. Looked in: ${candidates.map(File::getAbsolutePath)}")
    }

    companion object {
        /**
         * Hand-computed canonical form of `canonical-json-fixture.json`.
         *
         * Rules applied: keys sorted lexicographically per object,
         * no whitespace, default JSON escapes, arrays in source order.
         *
         * If the fixture changes, regenerate this via
         *   python3 design-system/fixtures/compute_canonical.py
         * or equivalent. iOS tests must be updated in lockstep.
         */
        private const val EXPECTED_CANONICAL =
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

        /**
         * SHA-256 of `EXPECTED_CANONICAL` encoded as UTF-8.
         * Computed once via Python stdlib; both platforms assert this
         * identical value. If this changes, both Android and iOS
         * tests must be updated in the same PR.
         */
        private const val EXPECTED_SHA256 =
            "f5af33b6b766125cc4cc6026a41130be6129e2f5a697d8e161b94f631a5b02a6"
    }
}
