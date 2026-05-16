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
 * Requires `NSSpeechRecognitionUsageDescription` on the app target's
 * Info.plist (same file that already hosts the mic + location
 * strings).
 *
 * Ported from Releaf's `VoiceTranscriber.swift`; behaviour unchanged.
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

    /// Transcribe the audio at `fileURL` and return the recognized
    /// text, or nil if nothing usable came back. All failure modes
    /// fold to nil so the call site can treat "couldn't transcribe"
    /// the same as "nothing to transcribe".
    public static func transcribe(fileURL: URL) async -> Result? {
        guard SFSpeechRecognizer.authorizationStatus() == .authorized else {
            return nil
        }
        let recognizer = SFSpeechRecognizer(locale: Locale.current)
            ?? SFSpeechRecognizer(locale: Locale(identifier: "en_US"))
        guard let recognizer, recognizer.isAvailable else { return nil }

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
}
