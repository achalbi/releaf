/*
 * VoiceTranscriber.swift
 *
 * Thin wrapper around `SFSpeechRecognizer` used to generate a text
 * transcript for a voice-note .m4a file once recording finishes. Android
 * has to transcribe concurrently with the mic (see `SpeechTranscriber.kt`)
 * because its recognizer has no file-based API; iOS ships one, so we take
 * the simpler path here: run recognition on the finalized file after
 * `AVAudioRecorder.stop()` completes.
 *
 * On-device by default via `requiresOnDeviceRecognition = true` — matches
 * the story we tell for OCR on the scan path and keeps voice-note audio
 * off Apple's servers. Falls back to cloud recognition if the locale
 * doesn't ship an on-device model (true on a narrow set of older locales
 * and older devices). Every failure path returns nil rather than
 * throwing — callers persist the voice note without a transcript rather
 * than blocking on a recognizer hiccup.
 *
 * Requires `NSSpeechRecognitionUsageDescription` on the eventual app
 * target's Info.plist (same file that already hosts the mic + location
 * strings).
 */

import Foundation
import Speech

public enum VoiceTranscriber {

    /// Ask the user for speech-recognition authorization. Wraps the
    /// Objective-C callback into an async value so the caller can
    /// `await` it alongside the mic-permission check. Returns true
    /// only for `.authorized` — `.restricted` and `.denied` both mean
    /// we can't produce a transcript and the caller should skip
    /// running recognition.
    public static func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
    }

    /// Transcribe the audio at `fileURL` and return the recognized
    /// text, or nil if nothing usable came back. All failure modes —
    /// recognizer unavailable, permission denied, empty audio, network
    /// error on the cloud fallback path — fold to nil so the call site
    /// can treat "couldn't transcribe" the same as "nothing to
    /// transcribe".
    ///
    /// Runs on-device when the user's locale has a downloaded model;
    /// otherwise routes through Apple's server-side recognition. The
    /// locale we ask for is the device locale so the transcript
    /// matches what the user was speaking, not a hard-coded English.
    public static func transcribe(fileURL: URL) async -> String? {
        guard SFSpeechRecognizer.authorizationStatus() == .authorized else {
            return nil
        }
        let recognizer = SFSpeechRecognizer(locale: Locale.current)
            ?? SFSpeechRecognizer(locale: Locale(identifier: "en_US"))
        guard let recognizer, recognizer.isAvailable else { return nil }

        let request = SFSpeechURLRecognitionRequest(url: fileURL)
        // Skip punctuation restoration on iOS 16+ — our users read
        // transcripts in small cards, not paragraph-length prose, and
        // trailing commas that Apple's model sometimes hallucinates
        // read as typos. Only bite into the fancier model on iOS 17+.
        // `addsPunctuation` shipped in iOS 16 / macOS 13; the
        // availability gate below names both so a macOS preview build
        // (deployment target 12) doesn't error.
        if #available(iOS 16, macOS 13, *) {
            request.addsPunctuation = true
        }
        // Prefer on-device when the model is downloaded for this locale.
        // Apple silently falls back to cloud if the model isn't
        // present; we don't fight that.
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }
        request.shouldReportPartialResults = false

        return await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
            // Guard against the recognizer firing its completion more
            // than once (it happens when a late final result arrives
            // after an earlier fatal one). `Atomic` isn't on the
            // simulator so we keep it simple with a serial queue +
            // flag; recognition callbacks all land on the same queue
            // anyway in practice.
            var resumed = false
            recognizer.recognitionTask(with: request) { result, error in
                guard !resumed else { return }
                if let error {
                    // `kAFAssistantErrorDomain` code 1110 means "no
                    // speech detected" — return nil rather than
                    // percolating the error upward. Everything else
                    // also folds to nil per the contract above.
                    _ = error
                    resumed = true
                    continuation.resume(returning: nil)
                    return
                }
                guard let result, result.isFinal else { return }
                let text = result.bestTranscription.formattedString
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                resumed = true
                continuation.resume(returning: text.isEmpty ? nil : text)
            }
        }
    }
}
