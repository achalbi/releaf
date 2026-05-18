/*
 * TranscriptionLanguage.swift
 *
 * The catalog of languages QuickInk's voice-note transcription can
 * cover: English plus the major Indian languages SFSpeechRecognizer
 * (cloud + on-device where available) supports. Drives the multi-
 * select chip grid in
 *   - Onboarding "Languages" step,
 *   - Settings → Transcription languages.
 *
 * Storage format (used by `profile_settings.transcription_languages`
 * on both platforms) is a comma-separated list of the ISO 639-1
 * codes below — e.g. "en,hi,kn". Compact, grep-able, no JSON
 * parsing in the payload codec.
 *
 * Android mirror: `TranscriptionLanguage.kt`. Keep the two lists
 * symmetric — adding a language means adding the same code+name
 * pair on the other side in the same commit.
 */

import Foundation

/// One row in the multi-select catalog.
///
/// - `code`: ISO 639-1 code (e.g. "en", "hi"). Canonical storage
///   form — what lands in the comma-separated DB column.
/// - `englishName`: user-visible name when the device is in English
///   (or any non-matching locale).
/// - `nativeName`: user-visible name in the language itself
///   (e.g. "हिन्दी"). Shown as a secondary line on the chip so a
///   user who doesn't read English can still recognise their
///   language.
public struct TranscriptionLanguage: Equatable, Hashable, Sendable, Identifiable {
    public let code: String
    public let englishName: String
    public let nativeName: String

    public var id: String { code }

    public init(code: String, englishName: String, nativeName: String) {
        self.code = code
        self.englishName = englishName
        self.nativeName = nativeName
    }
}

public enum TranscriptionLanguages {

    /// Catalog order = display order in the multi-select. English
    /// first (the most common pick across India), then the Indian
    /// languages in roughly speaker-count order so the most-picked
    /// options are at the top of the grid. Order matches
    /// `TranscriptionLanguages.supported` on Android.
    public static let supported: [TranscriptionLanguage] = [
        TranscriptionLanguage(code: "en", englishName: "English",   nativeName: "English"),
        TranscriptionLanguage(code: "hi", englishName: "Hindi",     nativeName: "हिन्दी"),
        TranscriptionLanguage(code: "bn", englishName: "Bengali",   nativeName: "বাংলা"),
        TranscriptionLanguage(code: "te", englishName: "Telugu",    nativeName: "తెలుగు"),
        TranscriptionLanguage(code: "mr", englishName: "Marathi",   nativeName: "मराठी"),
        TranscriptionLanguage(code: "ta", englishName: "Tamil",     nativeName: "தமிழ்"),
        TranscriptionLanguage(code: "gu", englishName: "Gujarati",  nativeName: "ગુજરાતી"),
        TranscriptionLanguage(code: "kn", englishName: "Kannada",   nativeName: "ಕನ್ನಡ"),
        TranscriptionLanguage(code: "ml", englishName: "Malayalam", nativeName: "മലയാളം"),
        TranscriptionLanguage(code: "pa", englishName: "Punjabi",   nativeName: "ਪੰਜਾਬੀ"),
        TranscriptionLanguage(code: "ur", englishName: "Urdu",      nativeName: "اردو"),
    ]

    private static let codeIndex: [String: TranscriptionLanguage] = {
        Dictionary(uniqueKeysWithValues: supported.map { ($0.code, $0) })
    }()

    /// Look up a single row by its ISO 639-1 code. Returns nil for
    /// codes outside the catalog so callers handle "user picked a
    /// language we've since removed" gracefully.
    public static func find(_ code: String) -> TranscriptionLanguage? {
        codeIndex[code]
    }

    /// Parse the `profile_settings.transcription_languages` column
    /// (comma-separated codes) into the matching catalog rows.
    /// Unknown codes are dropped silently; whitespace is tolerated.
    /// Returns an empty array for nil/blank input — caller decides
    /// how to apply the default.
    public static func parse(_ stored: String?) -> [TranscriptionLanguage] {
        guard let stored, !stored.trimmingCharacters(in: .whitespaces).isEmpty else {
            return []
        }
        var seen = Set<String>()
        var out: [TranscriptionLanguage] = []
        for raw in stored.split(separator: ",") {
            let code = raw.trimmingCharacters(in: .whitespaces).lowercased()
            guard !code.isEmpty, let row = codeIndex[code], !seen.contains(code) else { continue }
            seen.insert(code)
            out.append(row)
        }
        return out
    }

    /// Encode an array of catalog rows back into the comma-separated
    /// storage form. Empty array returns nil so the DB column ends
    /// up genuinely empty (vs an empty string) — matches the
    /// "no preference set" state the receiver expects.
    public static func encode(_ languages: [TranscriptionLanguage]) -> String? {
        if languages.isEmpty { return nil }
        return languages.map(\.code).joined(separator: ",")
    }

    /// Default allowlist when the user hasn't picked yet: the device
    /// locale's language (if it's in the catalog) plus English,
    /// deduped. Onboarding's "Languages" step pre-checks these so
    /// users in India see e.g. ["Hindi", "English"] already on.
    public static func defaultAllowlist(deviceLocale: Locale = .current) -> [TranscriptionLanguage] {
        let deviceCode = (deviceLocale.language.languageCode?.identifier ?? "en").lowercased()
        let deviceRow = codeIndex[deviceCode]
        let english   = codeIndex["en"]!
        if let deviceRow, deviceRow.code != "en" {
            return [deviceRow, english]
        } else {
            return [english]
        }
    }
}
