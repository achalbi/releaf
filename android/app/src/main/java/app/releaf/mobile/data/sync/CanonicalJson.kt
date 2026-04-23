/*
 * CanonicalJson.kt
 *
 * Canonical JSON serializer for Drive checksumming.
 *
 * Per `docs/DRIVE_SCHEMA.md` §"Canonical JSON for checksumming":
 *   1. UTF-8 encoding.
 *   2. Keys sorted lexicographically within every object.
 *   3. No insignificant whitespace (no newlines, no trailing spaces).
 *   4. Numbers serialize as JSON integers when integer-valued, otherwise
 *      shortest round-tripping decimal.
 *   5. Strings use JSON default escaping.
 *
 * iOS and Android must produce byte-identical output on identical input.
 * A cross-platform fixture lives at `design-system/fixtures/canonical-
 * json-fixture.json`; both platforms' test suites canonicalize it and
 * assert the same SHA-256 bytes.
 *
 * Callers pass in a kotlinx `JsonElement` — the caller is responsible
 * for constructing the element using kotlinx.serialization (which emits
 * `Long` as integer and `Double` as shortest decimal). The canonicalizer
 * never invents numeric forms; it sorts keys, strips whitespace, and
 * re-escapes strings.
 */

package app.releaf.mobile.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object CanonicalJson {

    /** Encode an element to canonical UTF-8 bytes. */
    fun encodeToBytes(element: JsonElement): ByteArray =
        encodeToString(element).toByteArray(Charsets.UTF_8)

    /** Encode an element to a canonical JSON string. */
    fun encodeToString(element: JsonElement): String =
        buildString { writeElement(this, element) }

    private fun writeElement(sb: StringBuilder, e: JsonElement) {
        when (e) {
            is JsonNull      -> sb.append("null")
            is JsonPrimitive -> writePrimitive(sb, e)
            is JsonArray     -> {
                sb.append('[')
                var first = true
                for (child in e) {
                    if (!first) sb.append(',')
                    writeElement(sb, child)
                    first = false
                }
                sb.append(']')
            }
            is JsonObject    -> {
                sb.append('{')
                var first = true
                for (key in e.keys.sorted()) {
                    if (!first) sb.append(',')
                    writeString(sb, key)
                    sb.append(':')
                    writeElement(sb, e.getValue(key))
                    first = false
                }
                sb.append('}')
            }
        }
    }

    private fun writePrimitive(sb: StringBuilder, p: JsonPrimitive) {
        if (p.isString) {
            writeString(sb, p.content)
        } else {
            // Numeric / boolean / null-as-primitive — emit kotlinx's
            // stringified form verbatim. Callers must pass numbers via
            // the typed `JsonPrimitive(Long)` / `JsonPrimitive(Double)`
            // constructors so the text matches what a number literal
            // would produce on the other platform.
            sb.append(p.content)
        }
    }

    /**
     * Default JSON string escaping. Matches the set RFC 8259 requires
     * and what Apple's JSONSerialization emits under .sortedKeys, so
     * Android and iOS agree byte-for-byte on escapes.
     */
    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"'      -> sb.append("\\\"")
                '\\'     -> sb.append("\\\\")
                '\b'     -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                '\n'     -> sb.append("\\n")
                '\r'     -> sb.append("\\r")
                '\t'     -> sb.append("\\t")
                else     -> if (c.code < 0x20) {
                    sb.append("\\u")
                    sb.append("%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }
}

/**
 * Hex-encoded SHA-256 of the canonical-JSON bytes for [element].
 * Convenience — every caller that checksums a payload wants the
 * same pipeline (canonicalize → SHA-256 → lowercase hex).
 */
fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2) {
        for (b in digest) {
            val v = b.toInt() and 0xff
            append(HEX[v ushr 4])
            append(HEX[v and 0x0f])
        }
    }
}

private const val HEX = "0123456789abcdef"
