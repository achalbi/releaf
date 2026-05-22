/*
 * VoiceNoteTranscriptionPane.swift
 *
 * Pre-review transcript editor for a freshly recorded voice note,
 * including the audio note extracted from video capture. The user can
 * edit the recognized text before moving into the normal scan review
 * surface, where the transcript feeds suggested tags.
 */

import Combine
import SwiftUI
import ReleafCoreDesignSystem

struct VoiceNoteTranscriptionPane: View {

    let captureId: String
    let voiceNoteId: String
    let showCancelButton: Bool
    let placeContinueBelowEditor: Bool
    let continueLabel: String
    let onContinue: () -> Void
    let onCancel: () -> Void

    @StateObject private var model: VoiceNoteTranscriptionPaneModel
    @State private var editedText: String = ""
    @State private var seeded: Bool = false
    @State private var userEdited: Bool = false
    @State private var canceling: Bool = false

    init(
        captureId: String,
        voiceNoteId: String,
        showCancelButton: Bool = true,
        placeContinueBelowEditor: Bool = false,
        continueLabel: String = "Continue to review",
        onContinue: @escaping () -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.captureId = captureId
        self.voiceNoteId = voiceNoteId
        self.showCancelButton = showCancelButton
        self.placeContinueBelowEditor = placeContinueBelowEditor
        self.continueLabel = continueLabel
        self.onContinue = onContinue
        self.onCancel = onCancel
        _model = StateObject(
            wrappedValue: VoiceNoteTranscriptionPaneModel(
                captureId: captureId,
                voiceNoteId: voiceNoteId
            )
        )
    }

    private var transcribing: Bool {
        ((model.note?.transcription ?? "").isEmpty) && !seeded
    }

    private var editorBinding: Binding<String> {
        Binding(
            get: { editedText },
            set: { next in
                editedText = next
                userEdited = true
            }
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, QuickInkSpacing.s6)

            bodyContent
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, QuickInkSpacing.s3)

            if placeContinueBelowEditor {
                Spacer()
            } else {
                Spacer(minLength: QuickInkSpacing.s5)
                bottomBar
                    .padding(.horizontal, QuickInkSpacing.s5)
                    .padding(.bottom, QuickInkSpacing.s5)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task { model.start() }
        .onReceive(model.$note) { note in
            seedTranscript(note?.transcription)
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .center) {
            if showCancelButton {
                Button(action: cancelTranscriptReview) {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .padding(8)
                        .background(Color.white.opacity(0.8), in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(canceling)
                .accessibilityLabel("Close")
            } else {
                Color.clear.frame(width: 50, height: 1)
            }

            Spacer()

            VStack(spacing: 2) {
                Text("VOICE NOTE")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(QuickInkColors.muted)
                Text("Review transcript")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
            }

            Spacer()
            Color.clear.frame(width: 50, height: 1)
        }
    }

    // MARK: Body

    private var bodyContent: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            Text(transcribing ? "Transcribing your note..." : "Edit the transcript before sending it to review.")
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.muted)

            editorSurface

            if placeContinueBelowEditor {
                continueButton
            }
        }
    }

    private var editorSurface: some View {
        ZStack(alignment: .topLeading) {
            TextEditor(text: editorBinding)
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.ink)
                .scrollContentBackground(.hidden)
                .textInputAutocapitalization(.sentences)
                .autocorrectionDisabled(false)
                .padding(QuickInkSpacing.s2)
                .frame(minHeight: 180, maxHeight: 320)

            if editedText.isEmpty {
                Text(transcribing ? "Listening for your words..." : "No transcript was generated. You can type notes here.")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.muted)
                    .padding(QuickInkSpacing.s3)
                    .allowsHitTesting(false)
            }
        }
        .background(Color.white.opacity(0.85))
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    // MARK: Continue

    private var bottomBar: some View {
        continueButton
    }

    private var continueButton: some View {
        Button(action: continueReview) {
            Text(continueLabel)
                .font(AppText.body)
                .fontWeight(.semibold)
                .foregroundStyle(QuickInkColors.textOnAccent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, AppSpacing.s3)
                .background(QuickInkColors.accent)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private func seedTranscript(_ text: String?) {
        guard !seeded, !userEdited else { return }
        guard let text, !text.isEmpty else { return }
        editedText = text
        seeded = true
    }

    private func continueReview() {
        let original = model.note?.transcription ?? ""
        let source = model.note?.transcriptionSource ?? "manual"
        guard editedText != original else {
            onContinue()
            return
        }
        Task {
            await model.saveTranscript(text: editedText, source: source)
            await MainActor.run { onContinue() }
        }
    }

    private func cancelTranscriptReview() {
        canceling = true
        Task {
            await model.deleteVoiceNote()
            await MainActor.run { onCancel() }
        }
    }
}

@MainActor
private final class VoiceNoteTranscriptionPaneModel: ObservableObject {

    @Published var note: VoiceNoteEntity? = nil

    private let captureId: String
    private let voiceNoteId: String
    private let repo: VoiceNoteRepository
    private var cancellable: AnyCancellable? = nil

    init(
        captureId: String,
        voiceNoteId: String,
        repo: VoiceNoteRepository = VoiceNoteRepository()
    ) {
        self.captureId = captureId
        self.voiceNoteId = voiceNoteId
        self.repo = repo
    }

    func start() {
        guard cancellable == nil else { return }
        cancellable = repo.observeForCapture(captureId)
            .receive(on: DispatchQueue.main)
            .sink(
                receiveCompletion: { _ in },
                receiveValue: { [weak self] rows in
                    guard let self else { return }
                    note = rows.first { $0.id == self.voiceNoteId }
                }
            )
    }

    func saveTranscript(text: String, source: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        try? await repo.setTranscription(
            id: voiceNoteId,
            transcription: trimmed.isEmpty ? nil : text,
            source: trimmed.isEmpty ? nil : source
        )
    }

    func deleteVoiceNote() async {
        try? await repo.softDelete(id: voiceNoteId)
    }
}
