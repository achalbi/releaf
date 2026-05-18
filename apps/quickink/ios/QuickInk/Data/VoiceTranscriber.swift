/*
 * VoiceTranscriber.swift
 *
 * Thin wrapper around `SFSpeechRecognizer` used to generate a text
 * transcript for a voice-note .m4a after recording finishes. Android
 * has to transcribe concurrently with the mic (see Android's
 * `SpeechTranscriber.kt`) because its recognizer has no file-based
 * API; iOS ships one, so we take the simpler path here: run
 * recognition on the finalized file after `AVAudioRecorder.stop()`
 * completes.
 *
 * On-device by default via `requiresOnDeviceRecognition = true` —
 * matches the story we tell for OCR on the scan path and keeps voice-
 * note audio off Apple's servers. Falls back to cloud recognition if
 * the locale doesn't ship an on-device model. Every failure path
 * returns nil rather than throwing — callers persist the voice note
 * without a transcript rather than blocking on a recognizer hiccup.
 *
 * Language picking: callers pass `userId` so the transcriber can read
 * `profile_settings.transcription_languages` and pick the first
 * allowlisted language as the SFSpeechRecognizer locale. Until a
 * proper LID step lands (deferred — needs a CoreML model), "first in
 * allowlist" is the heuristic. Users who speak multiple languages can
 * re-record after switching the primary in Settings.
 *
 * Requires `NSSpeechRecognitionUsageDescription` on the app target's
 * Info.plist (same file that already hosts the mic + location
 * strings).
 */

import Foundation
import Speech

public enum VoiceTranscriber {

    /// Stable string id for the iOS recognizer. Matches the value
    /// Android's SpeechTranscriber stamps on its results so the
    /// transcript_source column round-trips between platforms.
    public static let backend = "sfspeech"

    public struct Result {
        public let text: String
        public let source: String
    }

    /// Ask the user for speech-recognition authorization.
    public static func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
    }

    /// Transcribe the audio at `fileURL` using the user's picked
    /// primary transcription language. Returns nil on any failure
    /// — missing authorization, unsupported locale, recognition
    /// error, or empty result — so the call site treats "couldn't
    /// transcribe" the same as "nothing to transcribe."
    ///
    /// `userId` is required to read the allowlist; pass `nil` (or
    /// the empty string) to fall back to `Locale.current`, useful
    /// for previews or signed-out smoke tests.
    public static func transcribe(fileURL: URL, userId: String?) async -> Result? {
        let locale = await resolveLocale(userId: userId)
        return await transcribe(fileURL: fileURL, locale: locale)
    }

    /// Back-compat shim — the no-userId form falls back to
    /// `Locale.current`. Kept so any in-flight callsite (or future
    /// preview-only code path) that doesn't have a userId in scope
    /// still compiles.
    public static func transcribe(fileURL: URL) async -> Result? {
        await transcribe(fileURL: fileURL, locale: Locale.current)
    }

    // MARK: - Internals

    private static func transcribe(fileURL: URL, locale: Locale) async -> Result? {
        guard SFSpeechRecognizer.authorizationStatus() == .authorized else {
            return nil
        }
        // Try the picked locale first; if the device doesn't ship a
        // recognizer for that locale, return nil rather than falling
        // back to a different language (an English fallback on Hindi
        // audio yields garbage text, which is worse than no
        // transcript — the user can re-record after switching
        // primary in Settings, or wait for Apple to ship the model).
        guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable else {
            return nil
        }

        let request = SFSpeechURLRecognitionRequest(url: fileURL)
        if #available(iOS 16, macOS 13, *) {
            request.addsPunctuation = true
        }
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }
        request.shouldReportPartialResults = false

        return await withCheckedContinuation { (continuation: CheckedContinuation<Result?, Never>) in
            var resumed = false
            recognizer.recognitionTask(with: request) { result, error in
                guard !resumed else { return }
                if error != nil {
                    resumed = true
                    continuation.resume(returning: nil)
                    return
                }
                guard let result, result.isFinal else { return }
                let text = result.bestTranscription.formattedString
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                resumed = true
                continuation.resume(returning: text.isEmpty ? nil : Result(text: text, source: backend))
            }
        }
    }

    /// Read the user's transcription-language allowlist and turn the
    /// primary pick into the Apple locale we'll hand to
    /// `SFSpeechRecognizer`. Falls back through:
    ///   1. user's primary allowlist pick (first entry)
    ///   2. catalog default (device locale + English, deduped)
    ///   3. `Locale.current` as the absolute fallback.
    private static func resolveLocale(userId: String?) async -> Locale {
        let primaryCode: String? = await {
            guard let userId, !userId.isEmpty else { return nil }
            let row = try? await ProfileSettingsRepository().find(userId: userId)
            let parsed = TranscriptionLanguages.parse(row?.transcriptionLanguages)
            return parsed.first?.code
        }()
        let resolvedCode = primaryCode
            ?? TranscriptionLanguages.defaultAllowlist().first?.code
        guard let resolvedCode else { return Locale.current }
        return appleLocale(forCode: resolvedCode)
    }

    /// Map an ISO 639-1 catalog code (e.g. "hi", "kn") to the Apple
    /// locale identifier SFSpeechRecognizer expects. Indian-language
    /// codes get the "-IN" region — QuickInk's primary audience is
    /// India and `{code}-IN` is the variant Apple ships on-device
    /// models for. English falls back to the device's variant when
    /// it's an English locale (so a US user keeps en-US), else
    /// en-IN.
    private static func appleLocale(forCode code: String) -> Locale {
        let lower = code.lowercased()
        if lower == "en" {
            if Locale.current.language.languageCode?.identifier == "en" {
                return Locale.current
            }
            return Locale(identifier: "en-IN")
        }
        return Locale(identifier: "\(lower)-IN")
    }
}
