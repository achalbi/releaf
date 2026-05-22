/*
 * VoiceNoteSection.swift
 *
 * Voice-notes panel for the document detail screen. Renders the list
 * of voice notes attached to the current capture, a "Record" CTA that
 * opens the `VoicePageRecorder` in a half-sheet, and per-card playback
 * with a coral play button, deterministic waveform cursor, current /
 * total timestamps, and a delete button. Transcription is offered
 * per-card; the recognized text shows inline under the waveform.
 *
 * Composition mirrors Releaf's `VoiceSection` from
 * `apps/releaf/ios/Releaf/Features/Notepad/Sections/EditorSections.swift`,
 * adapted for QuickInk's capture-centric data model: one `voice_notes`
 * row per clip with a foreign key to the capture, observed live via
 * `VoiceNoteRepository.observeForCapture(...)`.
 */

import SwiftUI
import Combine
import AVFoundation
import Speech

struct VoiceNoteSection: View {

    let captureId: String
    let userId: String
    /// Fires after the Copy-to-notes button on a card writes the
    /// transcript onto `captures.notes`. The parent reloads its
    /// `capture` state so the Notes card refreshes — without this
    /// the column updates but the in-screen Notes card keeps
    /// showing the stale value.
    let onNotesChanged: () -> Void

    @StateObject private var viewModel: VoiceNoteSectionViewModel

    init(
        captureId: String,
        userId: String,
        onNotesChanged: @escaping () -> Void = {}
    ) {
        self.captureId = captureId
        self.userId = userId
        self.onNotesChanged = onNotesChanged
        _viewModel = StateObject(
            wrappedValue: VoiceNoteSectionViewModel(
                captureId: captureId,
                userId:    userId
            )
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Heading on a soft grey strip — matches the Details
            // and Notes cards.
            header
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, QuickInkSpacing.s3)
                .padding(.vertical, QuickInkSpacing.s2)
                .background(QuickInkColors.borderSoft)

            VStack(spacing: QuickInkSpacing.s2) {
                ForEach(viewModel.notes, id: \.id) { note in
                    VoiceNoteCard(
                        note: note,
                        isTranscribing: viewModel.transcribingIds.contains(note.id),
                        unavailableReason: viewModel.unavailable[note.id],
                        onTranscribe: { Task { await viewModel.transcribe(note: note) } },
                        onCopyToNotes: {
                            Task {
                                await viewModel.copyTranscriptToNotes(note: note)
                                onNotesChanged()
                            }
                        },
                        onEditTranscript: { edited in
                            Task { await viewModel.updateTranscript(noteId: note.id, text: edited) }
                        },
                        onDelete: { Task { await viewModel.delete(id: note.id) } }
                    )
                }

                if viewModel.notes.isEmpty {
                    emptyState
                }

                recordCta
            }
            .padding(QuickInkSpacing.s3)
        }
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .sheet(isPresented: $viewModel.showRecorder) {
            recorderSheet
                // Allow both detents: medium fits the recorder's
                // disc + waveform comfortably; large gives the
                // transcript editor room without scrolling.
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .onAppear  { viewModel.start() }
        .onDisappear { viewModel.stop() }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Image(systemName: "mic.fill")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(QuickInkColors.inkSoft)
            Text("Voice notes")
                .font(QuickInkFont.ui(13, weight: .semibold))
                .foregroundStyle(QuickInkColors.ink)
            if !viewModel.notes.isEmpty {
                Text("· \(viewModel.notes.count)")
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }
            Spacer()
        }
    }

    private var emptyState: some View {
        VStack(spacing: QuickInkSpacing.s2) {
            Text("No voice notes yet")
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.inkSoft)
            Text("Tap Record to add audio context for this scan.")
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.muted)
                .multilineTextAlignment(.center)
        }
        .padding(.vertical, QuickInkSpacing.s3)
        .frame(maxWidth: .infinity)
    }

    private var recordCta: some View {
        Button {
            viewModel.showRecorder = true
        } label: {
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "mic.fill")
                    .font(.system(size: 14, weight: .semibold))
                Text(viewModel.notes.isEmpty ? "Record a voice note" : "Record another")
                    .font(QuickInkText.caption)
            }
            .foregroundStyle(QuickInkColors.textOnAccent)
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.vertical, QuickInkSpacing.s2)
            .frame(maxWidth: .infinity)
            .background(QuickInkColors.accent)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
        }
        .buttonStyle(.plain)
        .padding(.top, QuickInkSpacing.s1)
    }

    @ViewBuilder
    private var recorderSheet: some View {
        // Three-stage sheet: record → transcribe spinner → editor.
        // On transcription failure or empty result, the editor stage
        // is skipped and the sheet dismisses straight away, matching
        // the "skip the editor, just save the clip" answer from the
        // flow questions. Stage transitions are driven by the view
        // model so the sheet can advance independently of user taps.
        switch viewModel.stage {
        case .recording:
            VStack {
                VoicePageRecorder(
                    onSave: { clip in
                        Task {
                            await viewModel.commitRecorded(uri: clip.uri, durationMs: clip.durationMs)
                        }
                    },
                    onCancel: { viewModel.showRecorder = false }
                )
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .background(QuickInkColors.bg.ignoresSafeArea())

        case .transcribing:
            transcribingStage

        case .editing(let initialText, let noteId):
            TranscriptEditorView(
                initialText: initialText,
                onCancel: { viewModel.showRecorder = false },
                onSave: { edited in
                    Task {
                        await viewModel.saveEditedTranscript(noteId: noteId, text: edited)
                        onNotesChanged()
                    }
                }
            )
        }
    }

    private var transcribingStage: some View {
        VStack(spacing: QuickInkSpacing.s4) {
            Spacer()
            ProgressView()
                .scaleEffect(1.4)
                .tint(QuickInkColors.accent)
            Text("Transcribing voice note…")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.ink)
            Text("This stays on your device. You can edit the text on the next screen.")
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.muted)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 280)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, QuickInkSpacing.s5)
        .background(QuickInkColors.bg.ignoresSafeArea())
    }
}

// MARK: - Transcript editor

/// Full-sheet editor shown after a voice note's transcription lands.
/// The user can adjust the recognized text before saving. On save the
/// edited text persists to the voice note's `transcription` field AND
/// is appended to the parent capture's `notes`.
private struct TranscriptEditorView: View {
    let initialText: String
    let onCancel: () -> Void
    let onSave: (String) -> Void

    @State private var text: String

    init(initialText: String, onCancel: @escaping () -> Void, onSave: @escaping (String) -> Void) {
        self.initialText = initialText
        self.onCancel = onCancel
        self.onSave = onSave
        _text = State(initialValue: initialText)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            HStack {
                Button(action: onCancel) {
                    Text("Cancel")
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.inkSoft)
                }
                .buttonStyle(.plain)
                Spacer()
                Text("Edit transcript")
                    .font(QuickInkFont.ui(15, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
                Spacer()
                Button {
                    onSave(text)
                } label: {
                    Text("Save")
                        .font(QuickInkFont.ui(15, weight: .semibold))
                        .foregroundStyle(QuickInkColors.accent)
                }
                .buttonStyle(.plain)
            }
            .padding(.top, QuickInkSpacing.s3)

            Text("This will save with the voice note and add to the document's notes.")
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.muted)

            TextEditor(text: $text)
                .font(QuickInkText.body)
                .scrollContentBackground(.hidden)
                .padding(QuickInkSpacing.s2)
                .background(QuickInkColors.borderSoft.opacity(0.4))
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )
                .frame(minHeight: 200)
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(QuickInkColors.bg.ignoresSafeArea())
    }
}

// MARK: - View model

@MainActor
final class VoiceNoteSectionViewModel: ObservableObject {

    /// Stage of the recorder sheet's lifecycle. Drives which subview
    /// the sheet renders. Resets to `.recording` whenever the sheet
    /// opens again.
    enum Stage: Equatable {
        case recording
        case transcribing
        /// `initialText` is the recognized transcript; `noteId` is the
        /// voice-note row id the edits should persist onto.
        case editing(initialText: String, noteId: String)
    }

    @Published var notes: [VoiceNoteEntity] = []
    @Published var transcribingIds: Set<String> = []
    @Published var unavailable: [String: String] = [:]
    @Published var showRecorder: Bool = false {
        didSet {
            // Re-entering the recorder always starts on the recording
            // stage. Without this reset, dismissing mid-editor and
            // reopening would land on the stale editor.
            if showRecorder, stage != .recording { stage = .recording }
        }
    }
    @Published var stage: Stage = .recording

    private let captureId: String
    private let userId: String
    private let repo: VoiceNoteRepository
    private let captureRepo: CaptureRepository
    private var cancellable: AnyCancellable? = nil

    init(
        captureId: String,
        userId: String,
        repo: VoiceNoteRepository = VoiceNoteRepository(),
        captureRepo: CaptureRepository = CaptureRepository()
    ) {
        self.captureId = captureId
        self.userId = userId
        self.repo = repo
        self.captureRepo = captureRepo
    }

    func start() {
        guard cancellable == nil else { return }
        cancellable = repo.observeForCapture(captureId)
            .receive(on: DispatchQueue.main)
            .sink(
                receiveCompletion: { _ in },
                receiveValue: { [weak self] rows in
                    self?.notes = rows
                }
            )
    }

    func stop() {
        cancellable?.cancel()
        cancellable = nil
    }

    /// Called by the recorder when the user finishes a clip. Inserts
    /// the voice-note row first (so the file is owned by a real DB
    /// row before we touch the recognizer), then runs transcription
    /// and advances the sheet through the flow:
    ///
    ///   - transcription succeeds with text → advance to `.editing`
    ///     so the user can adjust before saving.
    ///   - transcription returns nil / fails / mic-only silence →
    ///     dismiss the sheet straight away; the clip stays saved
    ///     without a transcript (matching the user's "skip the
    ///     editor, just save the clip" preference for failure paths).
    func commitRecorded(uri: String, durationMs: Int) async {
        let inserted: VoiceNoteEntity
        do {
            inserted = try await repo.insert(
                captureId:  captureId,
                userId:     userId,
                audioUri:   uri,
                durationMs: durationMs
            )
        } catch {
            print("VoiceNoteSection.commitRecorded failed: \(error)")
            showRecorder = false
            return
        }

        stage = .transcribing

        guard let fileURL = fileURL(for: uri) else {
            showRecorder = false
            return
        }
        let granted = await VoiceTranscriber.requestPermission()
        guard granted else {
            showRecorder = false
            return
        }

        if let result = await VoiceTranscriber.transcribe(fileURL: fileURL, userId: userId),
           !result.text.isEmpty {
            stage = .editing(initialText: result.text, noteId: inserted.id)
            // Pre-save the recognized transcript on the row so even if
            // the user dismisses the editor without tapping Save, the
            // card still shows what the recognizer heard.
            try? await repo.setTranscription(
                id:            inserted.id,
                transcription: result.text,
                source:        result.source
            )
        } else {
            showRecorder = false
        }
    }

    /// Persist the edited transcript both onto the voice-note row and
    /// as an appended paragraph on the parent capture's notes.
    /// Dismisses the sheet on completion.
    func saveEditedTranscript(noteId: String, text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            do {
                try await repo.setTranscription(
                    id:            noteId,
                    transcription: trimmed,
                    source:        VoiceTranscriber.backend
                )
            } catch {
                print("VoiceNoteSection.saveEditedTranscript transcript failed: \(error)")
            }
            do {
                try await captureRepo.appendNote(captureId: captureId, text: trimmed)
            } catch {
                print("VoiceNoteSection.saveEditedTranscript notes failed: \(error)")
            }
        }
        showRecorder = false
    }

    func delete(id: String) async {
        do {
            try await repo.softDelete(id: id)
        } catch {
            print("VoiceNoteSection.delete failed: \(error)")
        }
    }

    /// Persist a user-edited transcript onto an existing voice note.
    /// Empty input clears the column so the card re-renders the
    /// "Transcribe" CTA. Drives the in-card Edit pill — the post-
    /// recording editor uses [saveEditedTranscript] instead because
    /// that path also appends to the capture's notes.
    func updateTranscript(noteId: String, text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try await repo.setTranscription(
                id:            noteId,
                transcription: trimmed.isEmpty ? nil : trimmed,
                source:        trimmed.isEmpty ? nil : VoiceTranscriber.backend
            )
            // Clear any prior "unavailable" reason so the card shows
            // the new transcript state instead of a lingering error.
            unavailable.removeValue(forKey: noteId)
        } catch {
            print("VoiceNoteSection.updateTranscript failed: \(error)")
        }
    }

    /// Append the note's transcript onto the parent capture's
    /// `notes` field. No-op when the transcript is empty — the card
    /// only renders the affordance when a transcript exists, but
    /// guard here anyway so a stale tap can't write garbage.
    func copyTranscriptToNotes(note: VoiceNoteEntity) async {
        let text = (note.transcription ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        do {
            try await captureRepo.appendNote(captureId: captureId, text: text)
        } catch {
            print("VoiceNoteSection.copyTranscriptToNotes failed: \(error)")
        }
    }

    func transcribe(note: VoiceNoteEntity) async {
        guard !transcribingIds.contains(note.id) else { return }
        guard let url = fileURL(for: note.audioUri) else {
            unavailable[note.id] = "Audio file missing"
            return
        }

        let granted = await VoiceTranscriber.requestPermission()
        guard granted else {
            unavailable[note.id] = "Speech recognition denied"
            return
        }

        transcribingIds.insert(note.id)
        defer { transcribingIds.remove(note.id) }

        if let result = await VoiceTranscriber.transcribe(fileURL: url, userId: userId) {
            do {
                try await repo.setTranscription(
                    id:            note.id,
                    transcription: result.text,
                    source:        result.source
                )
                unavailable.removeValue(forKey: note.id)
            } catch {
                unavailable[note.id] = "Couldn't save transcript"
            }
        } else {
            unavailable[note.id] = "No speech detected"
        }
    }

    private func fileURL(for raw: String) -> URL? {
        if let parsed = URL(string: raw), parsed.isFileURL { return parsed }
        return URL(fileURLWithPath: raw)
    }
}

// MARK: - Card

private struct VoiceNoteCard: View {

    let note: VoiceNoteEntity
    let isTranscribing: Bool
    let unavailableReason: String?
    let onTranscribe: () -> Void
    /// Append the current transcript to the parent capture's notes.
    /// Only invoked when [note.transcription] is non-empty — the
    /// affordance is hidden otherwise.
    let onCopyToNotes: () -> Void
    /// Replace the voice note's transcription with the user's edit.
    /// Empty/whitespace input is allowed — the repository persists
    /// it and the card re-renders with the no-transcript state +
    /// the "Transcribe" CTA.
    let onEditTranscript: (String) -> Void
    let onDelete: () -> Void

    /// Disables the Copy-to-notes button + flips its label briefly
    /// after a tap so the user sees the append landed.
    @State private var didCopyToNotes: Bool = false
    /// Drives the transcript editor sheet.
    @State private var showTranscriptEditor: Bool = false
    /// Gates destructive deletes behind an explicit confirmation.
    @State private var showDeleteConfirm: Bool = false

    @StateObject private var player = VoicePlayer()

    private var totalMs: Int { max(note.durationMs, 1) }
    private var progress: Double { Double(player.currentMs) / Double(totalMs) }

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack(spacing: QuickInkSpacing.s3) {
                Button {
                    player.toggle(uri: note.audioUri)
                } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(QuickInkColors.textOnAccent)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(QuickInkColors.accent))
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
                    VoiceWaveform(
                        seed: note.id,
                        progress: min(max(progress, 0), 1),
                        playedColor: QuickInkColors.accent,
                        unplayedColor: QuickInkColors.muted
                    )
                    .frame(height: 24)

                    HStack {
                        Text(formatDurationMs(player.currentMs))
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.inkSoft)
                            .monospacedDigit()
                        Spacer()
                        Text(formatDurationMs(note.durationMs))
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.inkSoft)
                            .monospacedDigit()
                    }
                }
                .frame(maxWidth: .infinity)

                VStack(spacing: QuickInkSpacing.s1) {
                    if let url = shareURL {
                        ShareLink(item: url) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 14))
                                .foregroundStyle(QuickInkColors.inkSoft)
                                .frame(width: 32, height: 32)
                                .background(
                                    RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                                        .fill(QuickInkColors.borderSoft)
                                )
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Share voice note")
                    }

                    Button { showDeleteConfirm = true } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 14))
                            .foregroundStyle(QuickInkColors.inkSoft)
                            .frame(width: 32, height: 32)
                            .background(
                                RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                                    .fill(QuickInkColors.borderSoft)
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Delete voice note")
                }
            }

            transcriptStrip
        }
        .padding(QuickInkSpacing.s3)
        .background(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .fill(QuickInkColors.borderSoft.opacity(0.5))
        )
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .onDisappear { player.teardown() }
        .sheet(isPresented: $showTranscriptEditor) {
            TranscriptEditorView(
                initialText: note.transcription ?? "",
                onCancel:    { showTranscriptEditor = false },
                onSave:      { edited in
                    onEditTranscript(edited)
                    showTranscriptEditor = false
                }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .alert("Delete this voice note?", isPresented: $showDeleteConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                player.teardown()
                onDelete()
            }
        } message: {
            Text("The audio and transcript will be removed from this scan. This can't be undone.")
        }
    }

    private var shareURL: URL? {
        guard let url = resolvedFileURL(for: note.audioUri),
              FileManager.default.fileExists(atPath: url.path)
        else { return nil }
        return url
    }

    private func resolvedFileURL(for raw: String) -> URL? {
        if let parsed = URL(string: raw), parsed.isFileURL { return parsed }
        return URL(fileURLWithPath: raw)
    }

    @ViewBuilder
    private var transcriptStrip: some View {
        let hasTranscript = !(note.transcription ?? "").isEmpty
        let hasReason = unavailableReason != nil

        VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
            Text(eyebrowLabel(hasTranscript: hasTranscript, hasReason: hasReason))
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)

            if hasTranscript {
                Text(note.transcription!)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.ink)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: QuickInkSpacing.s2) {
                    // Copy to notes — appends the transcript text as
                    // a new paragraph on `captures.notes`. Flips its
                    // label to "Copied" briefly so the tap acknowledges
                    // even when the parent re-renders.
                    Button {
                        onCopyToNotes()
                        didCopyToNotes = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) {
                            didCopyToNotes = false
                        }
                    } label: {
                        HStack(spacing: QuickInkSpacing.s1) {
                            Image(systemName: didCopyToNotes ? "checkmark" : "doc.on.doc")
                                .font(.system(size: 11, weight: .semibold))
                            Text(didCopyToNotes ? "Copied" : "Copy to notes")
                                .font(QuickInkText.caption)
                        }
                        .foregroundStyle(QuickInkColors.accent)
                        .padding(.horizontal, QuickInkSpacing.s2)
                        .padding(.vertical, 4)
                        .background(QuickInkColors.accentSoft)
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .disabled(didCopyToNotes)
                    .accessibilityLabel("Copy transcript to notes")

                    // Edit — opens a sheet with the current
                    // transcript pre-filled. Save persists via the
                    // parent's `onEditTranscript` callback (writes
                    // to `voice_notes.transcription`).
                    Button { showTranscriptEditor = true } label: {
                        HStack(spacing: QuickInkSpacing.s1) {
                            Image(systemName: "pencil")
                                .font(.system(size: 11, weight: .semibold))
                            Text("Edit")
                                .font(QuickInkText.caption)
                        }
                        .foregroundStyle(QuickInkColors.accent)
                        .padding(.horizontal, QuickInkSpacing.s2)
                        .padding(.vertical, 4)
                        .background(QuickInkColors.accentSoft)
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Edit transcript")

                    Button(action: onTranscribe) {
                        Text("Try again")
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.accent)
                    }
                    .buttonStyle(.plain)
                }
            } else if isTranscribing {
                HStack(spacing: QuickInkSpacing.s2) {
                    ProgressView()
                        .scaleEffect(0.7)
                        .tint(QuickInkColors.accent)
                    Text("Transcribing…")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.inkSoft)
                }
            } else if hasReason {
                Text(unavailableReason!)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.inkSoft)
                Button(action: onTranscribe) {
                    Text("Retry")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.accent)
                }
                .buttonStyle(.plain)
            } else {
                Button(action: onTranscribe) {
                    HStack(spacing: QuickInkSpacing.s1) {
                        Image(systemName: "text.bubble")
                            .font(.system(size: 11, weight: .semibold))
                        Text("Transcribe")
                            .font(QuickInkText.caption)
                    }
                    .foregroundStyle(QuickInkColors.accent)
                    .padding(.horizontal, QuickInkSpacing.s2)
                    .padding(.vertical, 4)
                    .background(QuickInkColors.accentSoft)
                    .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.top, QuickInkSpacing.s1)
    }

    private func eyebrowLabel(hasTranscript: Bool, hasReason: Bool) -> String {
        if hasTranscript { return "TRANSCRIPT" }
        if isTranscribing { return "TRANSCRIBING" }
        if hasReason { return "TRANSCRIPT UNAVAILABLE" }
        return "TRANSCRIPT"
    }
}

// MARK: - Waveform

/// Decorative waveform — 40 vertical bars with heights derived
/// deterministically from the note id so the shape stays stable
/// across recompositions but varies between notes. Bars left of
/// `progress` render in `playedColor`, the rest in `unplayedColor`.
/// No real amplitude data: AVAudioRecorder doesn't preserve it and
/// we don't want to re-decode the file just for a sparkline.
struct VoiceWaveform: View {
    let seed: String
    let progress: Double
    let playedColor: Color
    let unplayedColor: Color

    private static let barCount = 40

    private var heights: [Double] {
        let base = UInt64(bitPattern: Int64(seed.hashValue))
        return (0..<Self.barCount).map { i in
            let mixed = base &* 2_654_435_761 &+ UInt64(i) &* 1_779_033_703
            let unit = Double(mixed % 10_000) / 10_000.0
            return 0.2 + unit * 0.8
        }
    }

    var body: some View {
        Canvas { context, size in
            let gap: CGFloat = 3
            let count = Self.barCount
            let barWidth = max(1, (size.width - gap * CGFloat(count - 1)) / CGFloat(count))
            let centerY = size.height / 2
            let progressX = size.width * CGFloat(progress)
            var x = barWidth / 2
            let hs = heights
            for i in 0..<count {
                let h = size.height * CGFloat(hs[i])
                let color = x <= progressX ? playedColor : unplayedColor
                var path = Path()
                path.move(to: CGPoint(x: x, y: centerY - h / 2))
                path.addLine(to: CGPoint(x: x, y: centerY + h / 2))
                context.stroke(
                    path,
                    with: .color(color),
                    style: StrokeStyle(lineWidth: barWidth, lineCap: .round)
                )
                x += barWidth + gap
            }
        }
    }
}

// MARK: - Player

/// Observable wrapper around AVAudioPlayer. Lazy — only builds the
/// player on first toggle so cards that never get played don't pay
/// the audio-session cost.
@MainActor
final class VoicePlayer: ObservableObject {
    @Published private(set) var isPlaying: Bool = false
    @Published private(set) var currentMs: Int = 0

    private var player: AVAudioPlayer?
    private var delegateProxy: VoicePlayerDelegateProxy?
    private var timer: Timer?

    func toggle(uri: String) {
        guard let url = parsedURL(for: uri) else { return }

        if let existing = player {
            if isPlaying {
                existing.pause()
                isPlaying = false
                stopTicker()
            } else {
                existing.play()
                isPlaying = true
                startTicker()
            }
            return
        }

        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
            let p = try AVAudioPlayer(contentsOf: url)
            let proxy = VoicePlayerDelegateProxy { [weak self] in
                Task { @MainActor in
                    self?.isPlaying = false
                    self?.player?.currentTime = 0
                    self?.currentMs = 0
                    self?.stopTicker()
                }
            }
            p.delegate = proxy
            self.delegateProxy = proxy
            guard p.prepareToPlay(), p.play() else { return }
            self.player = p
            self.isPlaying = true
            startTicker()
        } catch {
            // Silent — the card still renders its duration; the user
            // can tap again to retry.
        }
    }

    func teardown() {
        stopTicker()
        player?.stop()
        player = nil
        delegateProxy = nil
        isPlaying = false
        currentMs = 0
    }

    private func startTicker() {
        stopTicker()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let p = self.player else { return }
                self.currentMs = Int(p.currentTime * 1000)
            }
        }
    }

    private func stopTicker() {
        timer?.invalidate()
        timer = nil
    }

    private func parsedURL(for uri: String) -> URL? {
        if let parsed = URL(string: uri), parsed.isFileURL { return parsed }
        return URL(fileURLWithPath: uri)
    }
}

private final class VoicePlayerDelegateProxy: NSObject, AVAudioPlayerDelegate {
    let onFinish: () -> Void
    init(onFinish: @escaping () -> Void) {
        self.onFinish = onFinish
    }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        onFinish()
    }
}

// MARK: - Format helper

func formatDurationMs(_ ms: Int) -> String {
    let total = max(ms, 0) / 1000
    let m = total / 60
    let s = total % 60
    return String(format: "%d:%02d", m, s)
}
