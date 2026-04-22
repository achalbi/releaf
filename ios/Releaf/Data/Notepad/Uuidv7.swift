/*
 * Uuidv7.swift
 *
 * RFC 9562 UUIDv7 generator. The first 48 bits are a Unix-epoch-ms timestamp
 * so string-sorted ids are also chronologically sorted — our schema uses TEXT
 * primary keys and sometimes ORDER BY id as a cheap insertion-order proxy, so
 * this matters.
 *
 * Kept as a Swift port of the Kotlin equivalent (android/.../Uuidv7.kt) so
 * ids produced on either platform are indistinguishable when the row
 * eventually lands on the other side of Drive.
 *
 * Layout (128 bits total):
 *   bits  0-47  : unix_ts_ms     (48 bits, big-endian)
 *   bits 48-51  : version (0111) (4 bits)
 *   bits 52-63  : rand_a         (12 bits)
 *   bits 64-65  : variant (10)   (2 bits)
 *   bits 66-127 : rand_b         (62 bits)
 */

import Foundation

public enum Uuidv7 {

    /// Returns the canonical 8-4-4-4-12 hex form, lowercased.
    public static func generate(nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
        // 48-bit timestamp (mask in case a weirdly-clocked device feeds us a
        // value wider than 48 bits).
        let ts = UInt64(bitPattern: Int64(nowMs)) & 0x0000_FFFF_FFFF_FFFF

        // 10 bytes of randomness: 2 bytes feed rand_a (12 bits used), the
        // remaining 8 bytes feed rand_b (62 bits used — top 2 bits go to
        // the variant and are masked off).
        var buf = [UInt8](repeating: 0, count: 10)
        let status = SecRandomCopyBytes(kSecRandomDefault, buf.count, &buf)
        precondition(status == errSecSuccess, "Uuidv7: SecRandomCopyBytes failed")

        // rand_a: 12 bits from the first 2 bytes
        let randA: UInt32 = (UInt32(buf[0] & 0x0F) << 8) | UInt32(buf[1])

        // rand_b: pack 8 bytes big-endian, then mask top 2 bits for variant.
        var randB: UInt64 = 0
        for i in 2..<10 {
            randB = (randB << 8) | UInt64(buf[i])
        }
        randB &= 0x3FFF_FFFF_FFFF_FFFF

        // Assemble 16 bytes.
        var out = [UInt8](repeating: 0, count: 16)
        out[0]  = UInt8((ts >> 40) & 0xFF)
        out[1]  = UInt8((ts >> 32) & 0xFF)
        out[2]  = UInt8((ts >> 24) & 0xFF)
        out[3]  = UInt8((ts >> 16) & 0xFF)
        out[4]  = UInt8((ts >>  8) & 0xFF)
        out[5]  = UInt8( ts        & 0xFF)
        // version (0111) in high nibble of byte 6 + top 4 bits of randA
        out[6]  = UInt8(0x70 | ((randA >> 8) & 0x0F))
        out[7]  = UInt8(randA & 0xFF)
        // variant (10) in top 2 bits of byte 8 + top 6 bits of randB
        out[8]  = UInt8(0x80 | (UInt32((randB >> 56) & 0x3F)))
        out[9]  = UInt8((randB >> 48) & 0xFF)
        out[10] = UInt8((randB >> 40) & 0xFF)
        out[11] = UInt8((randB >> 32) & 0xFF)
        out[12] = UInt8((randB >> 24) & 0xFF)
        out[13] = UInt8((randB >> 16) & 0xFF)
        out[14] = UInt8((randB >>  8) & 0xFF)
        out[15] = UInt8( randB        & 0xFF)

        let hex = "0123456789abcdef"
        let hexChars = Array(hex)
        var s = ""
        s.reserveCapacity(36)
        for i in 0..<16 {
            if i == 4 || i == 6 || i == 8 || i == 10 { s.append("-") }
            let b = Int(out[i])
            s.append(hexChars[b >> 4])
            s.append(hexChars[b & 0x0F])
        }
        return s
    }
}
