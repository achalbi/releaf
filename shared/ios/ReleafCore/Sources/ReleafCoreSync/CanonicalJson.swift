/*
 * CanonicalJson.swift
 *
 * Canonical JSON serializer for Drive checksumming.
 *
 * Per `docs/DRIVE_SCHEMA.md` §"Canonical JSON for checksumming":
 *   1. UTF-8 encoding.
 *   2. Keys sorted lexicographically within every object.
 *   3. No insignificant whitespace.
 *   4. Numbers emit integers when integer-valued, otherwise shortest
 *      round-trip decimal.
 *   5. Default JSON string escaping.
 *
 * iOS and Android must produce byte-identical output on identical
 * input. A cross-platform fixture at
 * `design-system/fixtures/canonical-json-fixture.json` feeds both
 * platforms' test suites; they assert the same SHA-256 bytes.
 *
 * Strategy: take a Foundation JSON tree (NSDictionary / NSArray /
 * NSNumber / NSString / NSNull) and walk it manually. We don't rely on
 * JSONSerialization's .sortedKeys because it doesn't give bit-for-bit
 * control over number formatting or escape selection.
 */

import Foundation
import CommonCrypto

public enum CanonicalJson {

    /// Encode a Foundation JSON tree to canonical UTF-8 bytes.
    public static func encodeToData(_ value: Any) -> Data {
        var out = String()
        write(value, into: &out)
        return Data(out.utf8)
    }

    /// Encode a Foundation JSON tree to a canonical JSON string.
    public static func encodeToString(_ value: Any) -> String {
        var out = String()
        write(value, into: &out)
        return out
    }

    /// Encode an `Encodable` via JSONEncoder, then walk the resulting
    /// tree through the canonicalizer. Convenience for payload types
    /// defined with `Codable`.
    public static func encodeToData<T: Encodable>(encodable: T) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = []
        let data = try encoder.encode(encodable)
        let tree = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        return encodeToData(tree)
    }

    // MARK: - Implementation

    private static func write(_ value: Any, into sb: inout String) {
        if value is NSNull { sb.append("null"); return }

        switch value {
        case let dict as [String: Any]:
            writeObject(dict, into: &sb)

        case let nsdict as NSDictionary:
            var map: [String: Any] = [:]
            for case let (k as String, v) in nsdict { map[k] = v }
            writeObject(map, into: &sb)

        case let array as [Any]:
            writeArray(array, into: &sb)

        case let nsarr as NSArray:
            writeArray(Array(nsarr), into: &sb)

        case let s as String:
            writeString(s, into: &sb)

        case let n as NSNumber:
            writeNumber(n, into: &sb)

        case let b as Bool:
            sb.append(b ? "true" : "false")

        case let i as Int:
            sb.append(String(i))

        case let i as Int64:
            sb.append(String(i))

        case let d as Double:
            sb.append(shortestDecimal(d))

        default:
            sb.append("null")
        }
    }

    private static func writeObject(_ dict: [String: Any], into sb: inout String) {
        sb.append("{")
        var first = true
        for key in dict.keys.sorted() {
            if !first { sb.append(",") }
            writeString(key, into: &sb)
            sb.append(":")
            write(dict[key] as Any, into: &sb)
            first = false
        }
        sb.append("}")
    }

    private static func writeArray(_ arr: [Any], into sb: inout String) {
        sb.append("[")
        var first = true
        for v in arr {
            if !first { sb.append(",") }
            write(v, into: &sb)
            first = false
        }
        sb.append("]")
    }

    /// `NSNumber` double-duties as bool / int / double out of Foundation
    /// JSON decode. Disambiguate via the Objective-C type encoding —
    /// booleans come back as `@"c"` (signed char) on iOS.
    private static func writeNumber(_ n: NSNumber, into sb: inout String) {
        let type = String(cString: n.objCType)
        if type == "c" || type == "B" {
            sb.append(n.boolValue ? "true" : "false")
            return
        }
        // Integer-valued doubles emit as integers per spec §4.
        // Use Int64(exactly:) — the obvious `d <= Double(Int64.max)`
        // bound check is a trap: Double(Int64.max) rounds UP to
        // 9223372036854775808.0, so values right at that boundary
        // pass the bound then crash on Int64(d).
        let d = n.doubleValue
        if let i = Int64(exactly: d) {
            sb.append(String(i))
        } else {
            sb.append(shortestDecimal(d))
        }
    }

    /// Swift's `String(d)` is round-trip-safe for finite doubles and
    /// matches Kotlin's `Double.toString()` on finite values.
    private static func shortestDecimal(_ d: Double) -> String {
        if d.isNaN || d.isInfinite { return "null" }
        return String(d)
    }

    private static func writeString(_ s: String, into sb: inout String) {
        sb.append("\"")
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"":     sb.append("\\\"")
            case "\\":     sb.append("\\\\")
            case "\u{08}": sb.append("\\b")
            case "\u{0C}": sb.append("\\f")
            case "\n":     sb.append("\\n")
            case "\r":     sb.append("\\r")
            case "\t":     sb.append("\\t")
            default:
                if scalar.value < 0x20 {
                    sb.append(String(format: "\\u%04x", scalar.value))
                } else {
                    sb.append(Character(scalar))
                }
            }
        }
        sb.append("\"")
    }
}

/// Hex-encoded SHA-256 of the given bytes. Every caller that hashes a
/// payload wants the same pipeline (canonicalize → hash → lowercase hex).
public func sha256Hex(_ data: Data) -> String {
    var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
    data.withUnsafeBytes { bytes in
        _ = CC_SHA256(bytes.baseAddress, CC_LONG(data.count), &digest)
    }
    let hex = "0123456789abcdef"
    var out = String()
    out.reserveCapacity(digest.count * 2)
    for b in digest {
        let hi = Int(b >> 4)
        let lo = Int(b & 0x0f)
        out.append(hex[hex.index(hex.startIndex, offsetBy: hi)])
        out.append(hex[hex.index(hex.startIndex, offsetBy: lo)])
    }
    return out
}
