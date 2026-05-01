/*
 * Uuidv7.kt
 *
 * RFC 9562 UUIDv7 generator. Uses the first 48 bits for the Unix epoch ms
 * timestamp so that string-sorted ids are also chronologically sorted —
 * which matters because our schema uses TEXT ids and we often ORDER BY id
 * as a cheap "insertion order" proxy.
 *
 * There's no `java.util.UUID.randomUUID()` variant for v7 on JVM/Android,
 * and pulling a whole dependency for 20 lines of bit-twiddling isn't worth
 * it. Kept tiny and testable.
 *
 * Layout (128 bits total):
 *   bits  0-47  : unix_ts_ms     (48 bits, big-endian)
 *   bits 48-51  : version (0111) (4 bits)
 *   bits 52-63  : rand_a         (12 bits)
 *   bits 64-65  : variant (10)   (2 bits)
 *   bits 66-127 : rand_b         (62 bits)
 *
 * Canonical string form: 8-4-4-4-12 hex chars, lowercase.
 *
 * PR #4b moved this from `apps/releaf/android/.../data/common/Uuidv7.kt`
 * into :shared:data. Behavior unchanged. Existing
 * `import app.releaf.mobile.data.common.Uuidv7` callers keep working —
 * same Kotlin package, just lives in a different Gradle module now.
 */

package app.releaf.mobile.data.common

import java.security.SecureRandom

object Uuidv7 {

    private val random = SecureRandom()

    fun generate(nowMs: Long = System.currentTimeMillis()): String {
        // 48-bit timestamp
        val ts = nowMs and 0xFFFF_FFFF_FFFFL

        // 74 bits of randomness drawn together (12 for rand_a, 62 for rand_b).
        val buf = ByteArray(10)
        random.nextBytes(buf)

        // rand_a: 12 bits from the first 2 bytes
        val randA = ((buf[0].toInt() and 0x0F) shl 8) or (buf[1].toInt() and 0xFF)

        // rand_b: 62 bits packed into the remaining 8 bytes (top 2 bits go to
        // the variant, so mask them off).
        var randB = 0L
        for (i in 2 until 10) {
            randB = (randB shl 8) or (buf[i].toLong() and 0xFF)
        }
        randB = randB and 0x3FFF_FFFF_FFFF_FFFFL  // clear top 2 bits

        // Assemble the 16 bytes.
        val out = ByteArray(16)
        out[0]  = (ts ushr 40).toByte()
        out[1]  = (ts ushr 32).toByte()
        out[2]  = (ts ushr 24).toByte()
        out[3]  = (ts ushr 16).toByte()
        out[4]  = (ts ushr 8).toByte()
        out[5]  = ts.toByte()
        // version (0111) in high nibble of byte 6 + top 4 bits of randA
        out[6]  = (0x70 or ((randA ushr 8) and 0x0F)).toByte()
        out[7]  = (randA and 0xFF).toByte()
        // variant (10) in top 2 bits of byte 8 + top 6 bits of randB
        out[8]  = (0x80 or ((randB ushr 56).toInt() and 0x3F)).toByte()
        out[9]  = (randB ushr 48).toByte()
        out[10] = (randB ushr 40).toByte()
        out[11] = (randB ushr 32).toByte()
        out[12] = (randB ushr 24).toByte()
        out[13] = (randB ushr 16).toByte()
        out[14] = (randB ushr 8).toByte()
        out[15] = randB.toByte()

        return buildString(36) {
            for (i in 0 until 16) {
                if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
                val b = out[i].toInt() and 0xFF
                append(HEX[b ushr 4])
                append(HEX[b and 0x0F])
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
