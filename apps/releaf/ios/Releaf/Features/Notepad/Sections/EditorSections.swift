/*
 * EditorSections.swift
 *
 * SwiftUI mirror of Android's `ui/components/editor/EditorSections.kt`.
 * Six self-contained views — Contacts, Todos, Location, Photos, Scans,
 * Voice — that slot below the notepad editor's body and handle their
 * own system integrations (PhotosPicker, CoreLocation,
 * VNDocumentCameraViewController, AVAudioRecorder + AVAudioPlayer).
 *
 * Layout is uniform: each section renders an eyebrow title over a
 * bordered content card. The add affordance pins to the top and items
 * render below it, so the entry point stays in the same spot whether
 * the list is empty or full. Destructive actions route through a
 * confirmation alert — tap × to queue the item id, alert fires the
 * actual `onRemove`. Parity with Android keeps the UX identical
 * across platforms.
 *
 * Required Info.plist entries (on the eventual app target, since this
 * package has no target of its own):
 *   - NSLocationWhenInUseUsageDescription  — LOCATION section
 *   - NSCameraUsageDescription             — SCAN DOCUMENTS section
 *   - NSMicrophoneUsageDescription         — VOICE NOTES section
 *   - NSSpeechRecognitionUsageDescription  — VOICE NOTES transcription
 * PhotosPicker uses the system photo picker and needs no permission
 * string because it runs out-of-process.
 */

import SwiftUI
import PhotosUI
import AVFoundation
import ReleafData
import ReleafDesignSystem

// MARK: - Shared shell

struct SectionShell<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text(title)
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coral)

            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                content()
            }
            .padding(AppSpacing.s3)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(AppColors.cardSolid)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Tappable "+ add X" row used inside every section.
struct AddAffordance: View {
    let systemIcon: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: AppSpacing.s3) {
                Image(systemName: systemIcon)
                    .font(.system(size: 18))
                    .foregroundStyle(AppColors.coral)
                Text(label)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textPrimary)
                Spacer()
                Image(systemName: "plus")
                    .font(.system(size: 16))
                    .foregroundStyle(AppColors.coral)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, AppSpacing.s1)
        }
        .buttonStyle(.plain)
    }
}

/// Small × button used alongside chips and rows.
struct DeleteButton: View {
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: "xmark")
                .font(.system(size: 11))
                .foregroundStyle(AppColors.textTertiary)
                .frame(width: 22, height: 22)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Inline single-line text field used by contacts + todos for expansion
/// from the "+ Add X" affordance. Auto-focuses on appear; IME Done commits,
/// an empty submit cancels. Styled like the rest of the editor body.
struct InlineTextInput: View {
    let placeholder: String
    let onSubmit: (String) -> Void
    let onCancel: () -> Void

    @State private var value: String = ""
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: AppSpacing.s2) {
            TextField(
                "",
                text: $value,
                prompt: Text(placeholder).foregroundColor(AppColors.textTertiary)
            )
            .font(AppText.body)
            .foregroundStyle(AppColors.textPrimary)
            .tint(AppColors.coral)
            .submitLabel(.done)
            .focused($focused)
            .onSubmit {
                if value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    onCancel()
                } else {
                    onSubmit(value)
                }
                value = ""
            }

            DeleteButton {
                value = ""
                onCancel()
            }
        }
        .padding(.vertical, AppSpacing.s1)
        .onAppear { focused = true }
    }
}

// MARK: - Flow layout (chip wrapping)

/// Wraps chips across multiple lines within the available width. iOS 16+
/// `Layout` protocol does the measurement; no GeometryReader needed.
struct ChipFlowLayout: Layout {
    var spacingH: CGFloat = 8
    var spacingV: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalWidth: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxWidth, x > 0 {
                x = 0
                y += rowHeight + spacingV
                rowHeight = 0
            }
            x += size.width + spacingH
            rowHeight = max(rowHeight, size.height)
            totalWidth = max(totalWidth, x - spacingH)
        }
        let totalHeight = y + rowHeight
        return CGSize(width: maxWidth.isFinite ? maxWidth : totalWidth,
                      height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x: CGFloat = bounds.minX
        var y: CGFloat = bounds.minY
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x - bounds.minX + size.width > bounds.width, x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacingV
                rowHeight = 0
            }
            subview.place(
                at: CGPoint(x: x, y: y),
                proposal: ProposedViewSize(width: size.width, height: size.height)
            )
            x += size.width + spacingH
            rowHeight = max(rowHeight, size.height)
        }
    }
}

// MARK: - Contacts

struct ContactsSection: View {
    let contacts: [NotepadContact]
    let onAdd: (String) -> Void
    let onRemove: (String) -> Void

    @State private var isAdding = false
    /// Tap × on a chip → set this to the contact id → confirmation
    /// alert opens. `onRemove` only fires on explicit confirm.
    @State private var pendingDeleteId: String?

    /// Pulled into a computed helper so the confirmation message can
    /// show the actual contact name rather than a generic string.
    private var pendingName: String {
        guard let id = pendingDeleteId,
              let match = contacts.first(where: { $0.id == id }) else { return "" }
        return match.name
    }

    var body: some View {
        SectionShell(title: "CONTACTS") {
            // Affordance at the top so the add-entry point is always
            // in the same spot, whether the list is empty or full.
            if isAdding {
                InlineTextInput(
                    placeholder: "Name",
                    onSubmit: { name in
                        onAdd(name)
                        isAdding = false
                    },
                    onCancel: { isAdding = false }
                )
            } else {
                AddAffordance(
                    systemIcon: "person.2.fill",
                    label: "Add contact",
                    action: { isAdding = true }
                )
            }

            if !contacts.isEmpty {
                ChipFlowLayout {
                    ForEach(contacts) { contact in
                        ContactChip(contact: contact) { pendingDeleteId = contact.id }
                    }
                }
            }
        }
        .alert(
            "Remove this contact?",
            isPresented: Binding(
                get: { pendingDeleteId != nil },
                set: { presented in if !presented { pendingDeleteId = nil } }
            )
        ) {
            Button("Cancel", role: .cancel) { }
            Button("Remove", role: .destructive) {
                if let id = pendingDeleteId {
                    onRemove(id)
                    pendingDeleteId = nil
                }
            }
        } message: {
            Text(pendingName.isEmpty
                 ? "They'll be removed from this entry."
                 : "\(pendingName) will be removed from this entry.")
        }
    }
}

private struct ContactChip: View {
    let contact: NotepadContact
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: AppSpacing.s1) {
            Text(contact.name)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
            DeleteButton(action: onRemove)
        }
        .padding(.leading, AppSpacing.s3)
        .padding(.trailing, 4)
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.coral.opacity(0.15))
        )
    }
}

// MARK: - Todos

struct TodosSection: View {
    let todos: [NotepadTodo]
    let onAdd: (String) -> Void
    let onToggle: (String) -> Void
    let onRemove: (String) -> Void

    @State private var isAdding = false
    /// Tap × on a row → set this to the todo id → confirmation alert
    /// opens. `onRemove` only fires on explicit confirm.
    @State private var pendingDeleteId: String?

    /// Truncated todo text used in the alert body — the confirmation
    /// refers to the specific item rather than a generic placeholder.
    private var pendingText: String {
        guard let id = pendingDeleteId,
              let match = todos.first(where: { $0.id == id }) else { return "" }
        let t = match.text
        if t.count <= 60 { return t }
        return String(t.prefix(60)) + "…"
    }

    var body: some View {
        SectionShell(title: "TODOS") {
            // Affordance pinned to the top so the add-entry point is
            // consistent with every other section.
            if isAdding {
                InlineTextInput(
                    placeholder: "New todo",
                    onSubmit: { text in
                        onAdd(text)
                        isAdding = false
                    },
                    onCancel: { isAdding = false }
                )
            } else {
                AddAffordance(
                    systemIcon: "checklist",
                    label: "Add todo",
                    action: { isAdding = true }
                )
            }

            ForEach(todos) { todo in
                TodoRow(
                    todo: todo,
                    onToggle: { onToggle(todo.id) },
                    onRemove: { pendingDeleteId = todo.id }
                )
            }
        }
        .alert(
            "Delete this todo?",
            isPresented: Binding(
                get: { pendingDeleteId != nil },
                set: { presented in if !presented { pendingDeleteId = nil } }
            )
        ) {
            Button("Cancel", role: .cancel) { }
            Button("Delete", role: .destructive) {
                if let id = pendingDeleteId {
                    onRemove(id)
                    pendingDeleteId = nil
                }
            }
        } message: {
            Text(pendingText.isEmpty
                 ? "It'll be removed from this entry."
                 : "“\(pendingText)” will be removed from this entry.")
        }
    }
}

private struct TodoRow: View {
    let todo: NotepadTodo
    let onToggle: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            Button(action: onToggle) {
                Image(systemName: todo.done ? "checkmark.square.fill" : "square")
                    .font(.system(size: 22))
                    .foregroundStyle(AppColors.coral)
            }
            .buttonStyle(.plain)

            Text(todo.text)
                .font(AppText.body)
                .foregroundStyle(todo.done ? AppColors.textTertiary : AppColors.textPrimary)
                .strikethrough(todo.done, color: AppColors.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)

            DeleteButton(action: onRemove)
        }
    }
}

// MARK: - Location

struct LocationSection: View {
    let locations: [GeoLocation]
    let onAdd: (Double, Double, String?) -> Void
    let onRemove: (String) -> Void

    @StateObject private var probe = LocationProbe()
    @State private var isFetching = false
    @State private var errorMessage: String?
    /// Tap × on a row → set this to the location id → confirmation
    /// alert opens. `onRemove` only fires on explicit confirm.
    @State private var pendingDeleteId: String?

    /// Human-readable label for the alert body — address when we
    /// have one, otherwise fall back to the raw lat/lng pair.
    private var pendingLabel: String {
        guard let id = pendingDeleteId,
              let match = locations.first(where: { $0.id == id }) else { return "" }
        if let address = match.address, !address.isEmpty { return address }
        return String(format: "%.5f, %.5f", match.lat, match.lng)
    }

    var body: some View {
        SectionShell(title: "LOCATION") {
            // Affordance (or in-flight loader) pinned to the top so
            // every section adds from the same spot.
            if isFetching {
                HStack(spacing: AppSpacing.s3) {
                    ProgressView().tint(AppColors.coral).controlSize(.small)
                    Text("Reading GPS…")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textSecondary)
                }
                .padding(.vertical, AppSpacing.s1)
            } else {
                AddAffordance(
                    systemIcon: "location.fill",
                    label: "Use current location",
                    action: {
                        errorMessage = nil
                        isFetching = true
                        probe.requestOnce { fix in
                            isFetching = false
                            if let fix {
                                onAdd(fix.lat, fix.lng, fix.address)
                            } else {
                                errorMessage = "Couldn't read GPS — try again outside."
                            }
                        }
                    }
                )
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.danger)
            }

            ForEach(locations) { loc in
                LocationRow(location: loc) { pendingDeleteId = loc.id }
            }
        }
        .alert(
            "Remove this location?",
            isPresented: Binding(
                get: { pendingDeleteId != nil },
                set: { presented in if !presented { pendingDeleteId = nil } }
            )
        ) {
            Button("Cancel", role: .cancel) { }
            Button("Remove", role: .destructive) {
                if let id = pendingDeleteId {
                    onRemove(id)
                    pendingDeleteId = nil
                }
            }
        } message: {
            Text(pendingLabel.isEmpty
                 ? "It'll be removed from this entry."
                 : "\(pendingLabel) will be removed from this entry.")
        }
    }
}

private struct LocationRow: View {
    let location: GeoLocation
    let onRemove: () -> Void

    private var primary: String {
        if let address = location.address, !address.isEmpty { return address }
        return String(format: "%.5f, %.5f", location.lat, location.lng)
    }

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            Image(systemName: "location.fill")
                .font(.system(size: 14))
                .foregroundStyle(AppColors.coral)
            Text(primary)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            DeleteButton(action: onRemove)
        }
    }
}

// MARK: - Voice

/// Voice-note section. Header carries the eyebrow title with item
/// count plus a Record pill (flips to Stop while the mic is hot);
/// captured notes render as cards below with a large coral play button,
/// a waveform cursor, current / total timestamps, and download + delete
/// affordances.
///
/// Recording is inline (no modal): tap the Record pill in the header →
/// mic permission is requested on first use → a compact red-dot status
/// row appears under the header. Tap Stop (same pill, flipped label)
/// to commit the clip as an `Attachment` with `type = "voice"` and the
/// measured `durationMs`. `.onDisappear` calls `recorder.cancel()` so
/// navigating away drops any partial clip instead of leaving the mic hot.
///
/// Playback happens on each card and manages its own `AVAudioPlayer` so
/// multiple cards don't fight over a shared player. Cards use a small
/// observable wrapper that publishes the current playback position for
/// the waveform cursor and timestamps.
struct VoiceSection: View {
    let notes: [Attachment]
    let onAdd: (String, Int) -> Void
    /// Called once `SFSpeechRecognizer` finishes running against the
    /// saved .m4a. Keyed by uri so the viewmodel can match the
    /// already-persisted attachment and patch its `transcript` without
    /// the section having to track the newly-assigned id across the
    /// async hop.
    let onTranscribed: (String, String?) -> Void
    let onRemove: (String) -> Void

    @StateObject private var recorder = VoiceRecorder()
    /// Tap × on a row → set this to the note id → confirmation alert
    /// opens. `onRemove` only fires on explicit confirm.
    @State private var pendingDeleteId: String?
    /// Toasts for mic-permission denials land here — the section
    /// shows a small red hint beneath the header rather than
    /// throwing a system modal.
    @State private var errorMessage: String?
    /// Chevron expand state — toggles the per-card details panel
    /// (recorded timestamp + duration + file size) above the playback
    /// row. Shared across every card in the section so the chevron
    /// controls the whole list at once.
    @State private var isExpanded: Bool = false
    /// URIs whose transcription is in flight. Inserted when the
    /// recognizer starts, removed when it completes (success OR
    /// failure). The card checks this set to pick between
    /// "Transcribing…" placeholder text and the final state.
    @State private var pendingTranscription: Set<String> = []
    /// URIs where a transcription attempt completed and produced no
    /// text, mapped to a human-readable reason shown on the card
    /// ("Speech recognition denied", "No speech detected", etc.).
    /// The map presence is the "attempted" signal; the value is the
    /// reason string. Successful transcriptions don't land here —
    /// their text goes onto the attachment via `onTranscribed`.
    @State private var attemptedTranscription: [String: String] = [:]
    /// Recording sheet — when the user taps the Record pill we open
    /// a modal sheet that hosts `VoicePageRecorder` (live waveform,
    /// progress ring, slide-up-to-cancel) instead of running the
    /// inline recorder. The sheet's `onSave` callback funnels back
    /// into the existing `onAdd(uri, durationMs)` path so persistence,
    /// transcription, and playback all stay wired the same way.
    @State private var showRecordingSheet: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            VoiceSectionHeader(
                count: notes.count,
                isRecording: recorder.isRecording,
                isExpanded: isExpanded,
                onToggleExpand: {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isExpanded.toggle()
                    }
                },
                onRecordTap: {
                    // Tapping Record now opens the modal-sheet recorder
                    // (live waveform + progress ring + slide-up-to-
                    // cancel). The legacy inline-recording state-machine
                    // above stays in place so any code path that flips
                    // `recorder.isRecording` directly still works, but
                    // the user-facing trigger goes through the sheet.
                    if recorder.isRecording {
                        // Defensive: if for some reason the inline
                        // recorder is still hot, finalise it before
                        // surfacing the sheet so we don't end up with
                        // two recordings in flight.
                        if let result = recorder.stop() {
                            onAdd(result.uri, result.durationMs)
                        }
                    }
                    errorMessage = nil
                    showRecordingSheet = true
                }
            )

            if recorder.isRecording {
                RecordingIndicator(elapsedMs: recorder.elapsedMs)
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.danger)
            }

            ForEach(notes) { att in
                VoiceNoteCard(
                    attachment: att,
                    expanded: isExpanded,
                    isTranscribing: pendingTranscription.contains(att.uri),
                    unavailableReason: attemptedTranscription[att.uri],
                    onTranscribe: {
                        // Clear any prior failure reason on retry so
                        // the row flips cleanly back to "Transcribing…".
                        attemptedTranscription.removeValue(forKey: att.uri)
                        pendingTranscription.insert(att.uri)
                        startTranscription(uri: att.uri)
                    }
                ) {
                    pendingDeleteId = att.id
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // Leaving the section tears down the in-flight recording and
        // drops the partial clip — the StateObject survives navigation
        // to other tabs inside this pane, but we don't want the mic
        // hot if the user has moved on elsewhere.
        .onDisappear { recorder.cancel() }
        .alert(
            "Delete this voice note?",
            isPresented: Binding(
                get: { pendingDeleteId != nil },
                set: { presented in if !presented { pendingDeleteId = nil } }
            )
        ) {
            Button("Cancel", role: .cancel) { }
            Button("Delete", role: .destructive) {
                if let id = pendingDeleteId {
                    onRemove(id)
                    pendingDeleteId = nil
                }
            }
        } message: {
            Text("The clip will be removed from this entry and the file in app storage is cleaned up.")
        }
        .sheet(isPresented: $showRecordingSheet) {
            VoicePageRecorder(
                isEmpty: notes.isEmpty,
                onSave: { clip in
                    // Funnel through the existing add path so
                    // attachment storage, view-model state, and
                    // downstream transcription wiring stay intact.
                    onAdd(clip.uri, clip.durationMs)
                    showRecordingSheet = false
                },
                onCancel: { showRecordingSheet = false }
            )
            .padding(.top, AppSpacing.s4)
            // Use the .large detent only — the recording stage (eyebrow,
            // waveform, counter, cancel-zone slot, stop button, hint
            // copy) is taller than .medium, so the bottom hint and
            // the "Swipe up to cancel" cue would clip out of view at
            // the half-height detent.
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
    }

    /// Kick off speech recognition against the finalized .m4a. Runs on
    /// a detached Task so the UI keeps rendering while SFSpeechRecognizer
    /// chews on the file — typically a second or two for a short clip
    /// on device, longer over the cloud fallback path. If nothing came
    /// back we don't even call `onTranscribed`, so the persisted
    /// attachment keeps its nil transcript rather than getting patched
    /// with an empty string.
    private func startTranscription(uri: String) {
        guard let url = URL(string: uri), url.isFileURL else {
            pendingTranscription.remove(uri)
            attemptedTranscription[uri] = "Audio file not found"
            return
        }
        Task {
            let granted = await VoiceTranscriber.requestPermission()
            if !granted {
                await MainActor.run {
                    pendingTranscription.remove(uri)
                    attemptedTranscription[uri] = "Speech recognition permission denied"
                }
                return
            }
            let text = await VoiceTranscriber.transcribe(fileURL: url)
            await MainActor.run {
                pendingTranscription.remove(uri)
                if let text, !text.isEmpty {
                    onTranscribed(uri, text)
                } else {
                    attemptedTranscription[uri] = "No speech detected"
                }
            }
        }
    }
}

/// Section header: mic glyph + eyebrow title with count on the lead,
/// chevron + Record/Stop pill on the trailing edge. Not wrapped in
/// `SectionShell` because the shell doesn't expose a trailing-action
/// slot — the voice section is the only one that needs one today.
private struct VoiceSectionHeader: View {
    let count: Int
    let isRecording: Bool
    let isExpanded: Bool
    let onToggleExpand: () -> Void
    let onRecordTap: () -> Void

    var body: some View {
        HStack(spacing: AppSpacing.s1) {
            Image(systemName: "mic.fill")
                .font(.system(size: 11))
                .foregroundStyle(AppColors.coral)
            Text(count > 0 ? "VOICE NOTES · \(count)" : "VOICE NOTES")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coral)

            Spacer()

            HeaderChevronButton(isExpanded: isExpanded, action: onToggleExpand)
                .padding(.trailing, AppSpacing.s1)
            RecordPill(isRecording: isRecording, action: onRecordTap)
        }
    }
}

/// Circular chevron button on the header. Rotates from right (collapsed)
/// to down (expanded) on tap and tints coral-soft when open, so the
/// state change reads at a glance alongside the details panel on each
/// card.
private struct HeaderChevronButton: View {
    let isExpanded: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "chevron.right")
                .font(.system(size: 13))
                .foregroundStyle(isExpanded ? AppColors.coral : AppColors.textSecondary)
                .rotationEffect(.degrees(isExpanded ? 90 : 0))
                .animation(.easeInOut(duration: 0.2), value: isExpanded)
                .frame(width: 32, height: 32)
                .background(
                    Circle().fill(isExpanded ? AppColors.coralSoft : AppColors.cardSolid)
                )
                .overlay(
                    Circle().stroke(AppColors.borderDefault, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isExpanded ? "Hide voice note details" : "Show voice note details")
    }
}

/// Record/Stop pill in the section header. Flips label + icon based on
/// `isRecording` so the user has a single consistent control for both
/// entering and exiting the recording state.
private struct RecordPill: View {
    let isRecording: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: AppSpacing.s1) {
                Image(systemName: isRecording ? "stop.fill" : "mic.fill")
                    .font(.system(size: 12))
                Text(isRecording ? "Stop" : "Record")
                    .font(AppText.button)
            }
            .foregroundStyle(AppColors.coral)
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, AppSpacing.s1)
            .background(
                Capsule().fill(
                    isRecording ? AppColors.coralSoft : AppColors.cardSolid
                )
            )
            .overlay(
                Capsule().stroke(AppColors.borderDefault, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

/// Compact red-dot status row shown under the header while the
/// recorder is hot. The Stop control lives on the header pill, so this
/// is purely status — a subtle card with a red dot and a live mm:ss
/// counter.
private struct RecordingIndicator: View {
    let elapsedMs: Int

    var body: some View {
        HStack(spacing: AppSpacing.s2) {
            Circle()
                .fill(AppColors.danger)
                .frame(width: 10, height: 10)
            Text("Recording · \(formatDurationMs(elapsedMs))")
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
            Spacer()
        }
        .padding(.horizontal, AppSpacing.s3)
        .padding(.vertical, AppSpacing.s2)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
    }
}

/// Voice-note card. Large coral play button, a waveform tracking the
/// current playback position, current/total timestamps beneath it, and
/// a download + delete stack on the trailing edge. Owns its own
/// `VoicePlayer` so state is local to the card — and tears it down
/// on disposal so we don't leak audio sessions across navigation.
private struct VoiceNoteCard: View {
    let attachment: Attachment
    let expanded: Bool
    let isTranscribing: Bool
    /// Non-nil when a transcription attempt completed and produced no
    /// text. Value is the human-readable reason shown on the card
    /// ("Speech recognition denied", "No speech detected", etc.). Nil
    /// means "never attempted" — the row shows a prominent "Transcribe"
    /// button instead.
    let unavailableReason: String?
    let onTranscribe: () -> Void
    let onRemove: () -> Void

    @StateObject private var player = VoicePlayer()

    private var totalMs: Int { max(attachment.durationMs ?? 0, 1) }
    private var progress: Double {
        Double(player.currentMs) / Double(totalMs)
    }

    /// File size is resolved off disk once per card (and again if the
    /// URI changes). Cheap — a single `attributesOfItem` call — but
    /// there's no point doing it on every render while the waveform
    /// cursor ticks.
    private var fileSizeLabel: String? {
        resolveFileSizeLabel(uri: attachment.uri)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if expanded {
                VoiceNoteDetails(
                    capturedAt: attachment.capturedAt,
                    durationMs: attachment.durationMs ?? 0,
                    fileSizeLabel: fileSizeLabel
                )
                .padding(.bottom, AppSpacing.s3)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }

            HStack(spacing: AppSpacing.s3) {
                Button {
                    player.toggle(uri: attachment.uri)
                } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(AppColors.textOnAccent)
                        .frame(width: 48, height: 48)
                        .background(Circle().fill(AppColors.coral))
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: AppSpacing.s1) {
                    Waveform(
                        seed: attachment.id,
                        progress: min(max(progress, 0), 1),
                        playedColor: AppColors.coral,
                        unplayedColor: AppColors.textTertiary
                    )
                    .frame(height: 28)

                    HStack {
                        Text(formatDurationMs(player.currentMs))
                            .font(AppText.meta)
                            .foregroundStyle(AppColors.textSecondary)
                        Spacer()
                        Text(formatDurationMs(attachment.durationMs ?? 0))
                            .font(AppText.meta)
                            .foregroundStyle(AppColors.textSecondary)
                    }
                }
                .frame(maxWidth: .infinity)

                VStack(spacing: AppSpacing.s1) {
                    DownloadButton(attachment: attachment)
                    CardIconButton(systemIcon: "trash", label: "Delete", action: onRemove)
                }
            }

            // Transcript strip — always visible below the playback row.
            // Four states:
            //   idle / never attempted → "Transcribe" pill button
            //   pending                → spinner + "Transcribing…"
            //   success                → "TRANSCRIPT" + body text
            //   failed                 → reason + "Retry" pill
            TranscriptRow(
                transcript: attachment.transcript,
                isPending: isTranscribing,
                unavailableReason: unavailableReason,
                onTranscribe: onTranscribe
            )
        }
        .padding(AppSpacing.s3)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .animation(.easeInOut(duration: 0.2), value: expanded)
        .onDisappear { player.teardown() }
    }
}

/// Expanded-state details panel shown above the playback row. Leads with
/// a coral-soft mic tile + human-readable "Recorded …" timestamp, then a
/// meta row with a waveform-glyph + duration and storage-glyph + file
/// size. Separated into its own view so the animated expand/collapse
/// path stays readable. Transcript doesn't live here any more — it got
/// pulled out to `TranscriptRow` below the playback row so the user
/// sees it without having to expand the card first.
private struct VoiceNoteDetails: View {
    let capturedAt: String
    let durationMs: Int
    let fileSizeLabel: String?

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            HStack(spacing: AppSpacing.s2) {
                Image(systemName: "mic.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(AppColors.coral)
                    .frame(width: 32, height: 32)
                    .background(
                        RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                            .fill(AppColors.coralSoft)
                    )
                Text("Recorded \(formatRecordedAt(capturedAt))")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textPrimary)
            }

            HStack(spacing: AppSpacing.s1) {
                Image(systemName: "waveform")
                    .font(.system(size: 11))
                    .foregroundStyle(AppColors.textTertiary)
                Text(formatDurationMs(durationMs))
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)

                if let fileSizeLabel {
                    Spacer().frame(width: AppSpacing.s3 - AppSpacing.s1)
                    Image(systemName: "internaldrive")
                        .font(.system(size: 11))
                        .foregroundStyle(AppColors.textTertiary)
                    Text(fileSizeLabel)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                }

                Spacer()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Inline transcript strip on the voice-note card. Always rendered —
/// the user has a consistent transcription affordance on every voice
/// note, tapping "Transcribe" fires the file-based
/// `SFSpeechURLRecognitionRequest` pipeline and this same strip flips
/// through "Transcribing…" → transcript (or a retry affordance on
/// failure).
private struct TranscriptRow: View {
    let transcript: String?
    let isPending: Bool
    let unavailableReason: String?
    let onTranscribe: () -> Void

    private var hasTranscript: Bool {
        if let t = transcript, !t.isEmpty { return true }
        return false
    }

    private var eyebrow: String {
        switch true {
        case hasTranscript: return "TRANSCRIPT"
        case isPending: return "TRANSCRIBING"
        case unavailableReason != nil: return "TRANSCRIPT UNAVAILABLE"
        default: return "TRANSCRIPT"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text(eyebrow)
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textTertiary)

            if hasTranscript {
                Text(transcript ?? "")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textPrimary)
            } else if isPending {
                HStack(spacing: AppSpacing.s2) {
                    ProgressView()
                        .scaleEffect(0.7)
                        .tint(AppColors.coral)
                    Text("Running on-device recognition…")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textSecondary)
                }
            } else if let reason = unavailableReason {
                Text(reason)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
                TranscribeButton(label: "Retry", action: onTranscribe)
            } else {
                TranscribeButton(label: "Transcribe voice note", action: onTranscribe)
            }
        }
        .padding(AppSpacing.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                .fill(AppColors.subtle)
        )
        .padding(.top, AppSpacing.s3)
    }
}

/// Coral pill that kicks off (or retries) transcription. Same shape as
/// the Android twin. Kept as its own view because the transcript row
/// renders the button in two labeled states ("Transcribe voice note"
/// for untouched notes, "Retry" after a failure) and we want them
/// visually identical.
private struct TranscribeButton: View {
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: AppSpacing.s1) {
                Image(systemName: "captions.bubble")
                    .font(.system(size: 12))
                Text(label)
                    .font(AppText.button)
            }
            .foregroundStyle(AppColors.textOnAccent)
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, AppSpacing.s1)
            .background(Capsule().fill(AppColors.coral))
        }
        .buttonStyle(.plain)
    }
}

/// Download affordance on the voice card — wraps `ShareLink` so the
/// user gets the system share sheet (Save to Files, AirDrop, Messages,
/// etc.). Distinct from `CardIconButton` because `ShareLink` has its
/// own button shape and ergonomics.
private struct DownloadButton: View {
    let attachment: Attachment

    private var shareURL: URL? {
        guard let url = URL(string: attachment.uri), url.isFileURL else { return nil }
        return url
    }

    var body: some View {
        Group {
            if let url = shareURL {
                ShareLink(item: url) {
                    Image(systemName: "square.and.arrow.down")
                        .font(.system(size: 14))
                        .foregroundStyle(AppColors.textSecondary)
                        .frame(width: 32, height: 32)
                        .background(
                            RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                                .fill(AppColors.subtle)
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Download")
            } else {
                // No file behind the URI — render a disabled-looking
                // affordance rather than skipping the slot so the card
                // layout stays consistent.
                Image(systemName: "square.and.arrow.down")
                    .font(.system(size: 14))
                    .foregroundStyle(AppColors.textTertiary)
                    .frame(width: 32, height: 32)
                    .background(
                        RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                            .fill(AppColors.subtle)
                    )
            }
        }
    }
}

/// Small square icon button — subtle rounded-rect, used for the delete
/// affordance on the voice-note card. Intentionally muted so it doesn't
/// compete with the coral play button.
private struct CardIconButton: View {
    let systemIcon: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemIcon)
                .font(.system(size: 14))
                .foregroundStyle(AppColors.textSecondary)
                .frame(width: 32, height: 32)
                .background(
                    RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                        .fill(AppColors.subtle)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Decorative waveform — 40 vertical bars with heights derived
/// deterministically from the attachment id so the shape stays stable
/// across recompositions but varies between notes. Bars left of
/// `progress` (0..1) render in `playedColor`, the rest in
/// `unplayedColor`, giving a cheap visual cursor while the AVAudioPlayer
/// advances. No real amplitude data yet — AVAudioRecorder doesn't
/// preserve it and we don't want to re-decode the file just for a
/// sparkline.
private struct Waveform: View {
    let seed: String
    let progress: Double
    let playedColor: Color
    let unplayedColor: Color

    private static let barCount = 40

    private var heights: [Double] {
        let base = UInt64(bitPattern: Int64(seed.hashValue))
        return (0..<Self.barCount).map { i in
            // Knuth-style multiplicative mix — good enough for
            // decorative bar heights, no crypto or statistical claims.
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

/// Observable wrapper around `AVAudioRecorder`. Owns the clip file, a
/// tick timer for `elapsedMs`, and a short-recording guard so the
/// caller doesn't have to know about the 500ms floor.
@MainActor
private final class VoiceRecorder: ObservableObject {
    struct Result { let uri: String; let durationMs: Int }

    /// Skip anything under this — almost certainly a double-tap misfire.
    private static let minRecordingMs: Int = 500

    @Published private(set) var isRecording: Bool = false
    @Published private(set) var elapsedMs: Int = 0

    private var recorder: AVAudioRecorder?
    private var outputURL: URL?
    private var startedAt: Date?
    private var timer: Timer?

    /// Mic-permission prompt. Wraps the Objective-C callback in an
    /// async primitive so the call site can `await`. iOS 17 deprecates
    /// `requestRecordPermission`, but iOS 16 (our deployment floor)
    /// still requires it.
    static func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    /// Begin recording. Returns `false` if the recorder couldn't be
    /// prepared (audio-session failure, file write failure). The
    /// section surfaces this as a red hint under the affordance.
    @discardableResult
    func start() -> Bool {
        guard !isRecording else { return false }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playAndRecord,
                mode: .default,
                // HFP = Hands-Free Profile, the Bluetooth category
                // AVAudioSession actually negotiates for record. The
                // older bare-name option was deprecated in iOS 8
                // (SDK only just started flagging it).
                options: [.defaultToSpeaker, .allowBluetoothHFP]
            )
            try session.setActive(true)

            let dir = try AttachmentStorage.directory()
            let url = dir.appendingPathComponent("\(Uuidv7.generate()).m4a")
            let settings: [String: Any] = [
                AVFormatIDKey:             Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey:           44_100,
                AVNumberOfChannelsKey:     1,
                AVEncoderAudioQualityKey:  AVAudioQuality.medium.rawValue,
                AVEncoderBitRateKey:       96_000,
            ]
            let rec = try AVAudioRecorder(url: url, settings: settings)
            guard rec.prepareToRecord(), rec.record() else {
                try? FileManager.default.removeItem(at: url)
                return false
            }
            self.recorder   = rec
            self.outputURL  = url
            self.startedAt  = Date()
            self.isRecording = true
            self.elapsedMs  = 0
            startTicker()
            return true
        } catch {
            return false
        }
    }

    /// Stop + commit. Returns nil if the clip was too short (dropped)
    /// or if we never started. The recorder is fully torn down
    /// either way.
    @discardableResult
    func stop() -> Result? {
        guard isRecording, let rec = recorder, let url = outputURL else {
            tearDown()
            return nil
        }
        let durationMs = Int((Date().timeIntervalSince(startedAt ?? Date())) * 1000)
        rec.stop()
        tearDown()

        let exists = FileManager.default.fileExists(atPath: url.path)
        let size   = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
        guard exists, size > 0, durationMs >= Self.minRecordingMs else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return Result(uri: url.absoluteString, durationMs: durationMs)
    }

    private func startTicker() {
        timer?.invalidate()
        // 10 Hz — enough to animate the mm:ss without burning CPU. Run
        // on main because we mutate @Published state.
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let started = self.startedAt else { return }
                self.elapsedMs = Int(Date().timeIntervalSince(started) * 1000)
            }
        }
    }

    /// Abort the current recording without committing it — drops the
    /// partial file, invalidates the ticker, releases the mic session.
    /// Called from `.onDisappear` so walking away from the section
    /// can't leave the mic hot.
    func cancel() {
        guard isRecording else { return }
        recorder?.stop()
        if let url = outputURL {
            try? FileManager.default.removeItem(at: url)
        }
        tearDown()
    }

    private func tearDown() {
        timer?.invalidate()
        timer       = nil
        recorder    = nil
        outputURL   = nil
        startedAt   = nil
        isRecording = false
        elapsedMs   = 0
        // Drop the recording audio session so playback in other views
        // isn't pinned to the recording route.
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }
}

/// Observable wrapper around `AVAudioPlayer`. Lazy — only builds the
/// player on first `toggle` so cards that never get played don't pay
/// the audio-session cost. Auto-resets `isPlaying` when the clip
/// finishes via a small `VoicePlayerDelegateProxy` forwarder (the
/// delegate has to be an NSObject; the proxy keeps `VoicePlayer`
/// itself a plain class).
///
/// Publishes `currentMs` for the waveform cursor + current-time label.
/// Ticks at 10 Hz while playing — same rate as `VoiceRecorder`'s
/// elapsed counter, cheap enough that we don't worry about it.
@MainActor
private final class VoicePlayer: ObservableObject {
    @Published private(set) var isPlaying: Bool = false
    @Published private(set) var currentMs: Int = 0

    private var player: AVAudioPlayer?
    private var delegateProxy: VoicePlayerDelegateProxy?
    private var timer: Timer?

    func toggle(uri: String) {
        guard let url = URL(string: uri), url.isFileURL else { return }

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
            // can tap again to retry. We don't pollute the section
            // with an error hint for the common case where the audio
            // session is briefly contended.
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
}

/// Tiny delegate forwarder — lets `VoicePlayer` expose a closure to
/// the AVFoundation callback without inheriting from NSObject in a way
/// that fights @MainActor.
private final class VoicePlayerDelegateProxy: NSObject, AVAudioPlayerDelegate {
    let onFinish: () -> Void
    init(onFinish: @escaping () -> Void) {
        self.onFinish = onFinish
    }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        onFinish()
    }
}

/// mm:ss formatter shared by the recording indicator and the voice-card
/// duration labels. Uses `en_US_POSIX` so the colon separator doesn't
/// localize into something the layout can't handle. Zero-padded
/// minutes so the card's current / total timestamps stay a fixed width
/// as the cursor advances.
private func formatDurationMs(_ ms: Int) -> String {
    let totalSeconds = max(0, ms / 1000)
    let minutes = totalSeconds / 60
    let seconds = totalSeconds % 60
    return String(format: "%02d:%02d", locale: Locale(identifier: "en_US_POSIX"), minutes, seconds)
}

/// "Apr 21, 2026 at 10:59 PM"-style label for the expanded details row.
/// Input is the ISO-8601 UTC string stored at capture time; we render in
/// the device's current zone and locale. Falls back to the raw ISO
/// string if parsing fails so nothing in the panel ever blanks out.
private let recordedAtInputFormatter: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
}()

private let recordedAtFallbackFormatter: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    return f
}()

private let recordedAtOutputFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "MMM d, yyyy 'at' h:mm a"
    return f
}()

private func formatRecordedAt(_ iso: String) -> String {
    let date = recordedAtInputFormatter.date(from: iso)
        ?? recordedAtFallbackFormatter.date(from: iso)
    guard let date else { return iso }
    return recordedAtOutputFormatter.string(from: date)
}

/// Read the underlying file's byte size and format it via the
/// platform's `ByteCountFormatter` (short style — "153 KB" beats
/// "153,456 bytes"). Returns nil if the URI doesn't point to a
/// readable file so the details panel can drop the field entirely
/// rather than showing a confusing 0 B.
private let fileSizeFormatter: ByteCountFormatter = {
    let f = ByteCountFormatter()
    f.allowedUnits = [.useKB, .useMB]
    f.countStyle = .file
    return f
}()

private func resolveFileSizeLabel(uri: String) -> String? {
    guard let url = URL(string: uri), url.isFileURL else { return nil }
    guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
          let size = attrs[.size] as? NSNumber, size.int64Value > 0 else {
        return nil
    }
    return fileSizeFormatter.string(fromByteCount: size.int64Value)
}

// MARK: - Photos

struct PhotosSection: View {
    let photos: [Attachment]
    let onAdd: (String) -> Void
    let onRemove: (String) -> Void

    /// Array-backed so the PhotosPicker runs in multi-select mode.
    /// Users can tap several images in the sheet at once and we
    /// import each into the attachments list.
    @State private var pickerItems: [PhotosPickerItem] = []
    /// Set when the user taps × on a photo tile. Non-nil drives the
    /// confirmation alert. `onRemove` only fires on explicit confirm.
    @State private var pendingDeleteId: String?

    var body: some View {
        SectionShell(title: "PHOTOS") {
            // Add affordance sits at the top of the section so users
            // always know where to add, even with a full grid below.
            PhotosPicker(
                selection: $pickerItems,
                matching: .images,
                photoLibrary: .shared()
            ) {
                AddAffordanceLabel(systemIcon: "photo.fill", label: "Add photo")
            }
            .onChange(of: pickerItems) { items in
                // Skip the no-op clear we do at the end of a successful
                // import. Only act when the user actually picked items.
                guard !items.isEmpty else { return }
                Task { @MainActor in
                    for item in items {
                        if let data = try? await item.loadTransferable(type: Data.self),
                           let url = AttachmentStorage.write(data, ext: "jpg") {
                            onAdd(url.absoluteString)
                        }
                    }
                    // Reset so re-picking the same photos fires again.
                    pickerItems = []
                }
            }

            if !photos.isEmpty {
                // 3-column grid sits below the affordance. Every tile
                // is tappable for removal via the × overlay, but the
                // actual delete is gated by the confirmation alert.
                AttachmentGrid(
                    attachments:     photos,
                    onRemoveRequest: { id in pendingDeleteId = id },
                    fallbackIcon:    "photo.fill"
                )
            }
        }
        .alert(
            "Delete this photo?",
            isPresented: Binding(
                get: { pendingDeleteId != nil },
                set: { presented in if !presented { pendingDeleteId = nil } }
            )
        ) {
            Button("Cancel", role: .cancel) { }
            Button("Delete", role: .destructive) {
                if let id = pendingDeleteId {
                    onRemove(id)
                    pendingDeleteId = nil
                }
            }
        } message: {
            Text("It'll be removed from this entry and the file in app storage is cleaned up.")
        }
    }
}

/// The picker wraps its own label, so we extract the inner layout into a
/// reusable view that mirrors `AddAffordance` visually (but without its
/// own Button wrapper — PhotosPicker is the button here).
private struct AddAffordanceLabel: View {
    let systemIcon: String
    let label: String

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            Image(systemName: systemIcon)
                .font(.system(size: 18))
                .foregroundStyle(AppColors.coral)
            Text(label)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
            Spacer()
            Image(systemName: "plus")
                .font(.system(size: 16))
                .foregroundStyle(AppColors.coral)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, AppSpacing.s1)
        .contentShape(Rectangle())
    }
}

// MARK: - Scans

struct ScansSection: View {
    let scans: [Attachment]
    let onAdd: (String, String?) -> Void
    let onRemove: (String) -> Void

    @State private var showScanner = false
    /// Delete guard — tap × → set this to the tile id → alert opens.
    /// `onRemove` only fires on explicit confirm.
    @State private var pendingDeleteId: String?

    var body: some View {
        SectionShell(title: "SCAN DOCUMENTS") {
            // Affordance at the top so users always know where to
            // start a new scan, even with a full grid below.
            AddAffordance(
                systemIcon: "doc.viewfinder",
                label: "Scan document",
                action: { showScanner = true }
            )

            if !scans.isEmpty {
                AttachmentGrid(
                    attachments:     scans,
                    onRemoveRequest: { id in pendingDeleteId = id },
                    fallbackIcon:    "doc.fill"
                )
            }
        }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentScannerView(
                onComplete: { pdfURL, previewURL in
                    onAdd(pdfURL.absoluteString, previewURL?.absoluteString)
                    showScanner = false
                },
                onCancel: { showScanner = false }
            )
            .ignoresSafeArea()
        }
        .alert(
            "Delete this scan?",
            isPresented: Binding(
                get: { pendingDeleteId != nil },
                set: { presented in if !presented { pendingDeleteId = nil } }
            )
        ) {
            Button("Cancel", role: .cancel) { }
            Button("Delete", role: .destructive) {
                if let id = pendingDeleteId {
                    onRemove(id)
                    pendingDeleteId = nil
                }
            }
        } message: {
            Text("It'll be removed from this entry. The underlying scan files in app storage are cleaned up too.")
        }
    }
}

// MARK: - Attachment grid

/// 3-column vertical grid shared by Photos + Scans. `LazyVGrid`
/// inside the OverviewPane's ScrollView — SwiftUI sizes each cell
/// to an equal column width and the tile's internal
/// `aspectRatio(1)` keeps it square. Delete intent routes through
/// `onRemoveRequest` so the enclosing section can gate the actual
/// removal on a confirmation alert.
private struct AttachmentGrid: View {
    let attachments: [Attachment]
    let onRemoveRequest: (String) -> Void
    let fallbackIcon: String

    private let columns: [GridItem] = [
        GridItem(.flexible(), spacing: AppSpacing.s2),
        GridItem(.flexible(), spacing: AppSpacing.s2),
        GridItem(.flexible(), spacing: AppSpacing.s2),
    ]

    var body: some View {
        LazyVGrid(columns: columns, spacing: AppSpacing.s2) {
            ForEach(attachments) { att in
                AttachmentTile(
                    attachment: att,
                    fallbackIcon: fallbackIcon,
                    onRemove: { onRemoveRequest(att.id) }
                )
            }
        }
    }
}

/// Square thumbnail tile. Size is controlled by the caller — either
/// via an explicit outer `.frame(...)` (scans strip) or by the grid
/// cell's width (photos grid). Internal `.aspectRatio(1)` keeps the
/// tile square regardless of how width is resolved.
private struct AttachmentTile: View {
    let attachment: Attachment
    let fallbackIcon: String
    let onRemove: () -> Void

    private var thumbURL: URL? {
        let source = attachment.previewUri ?? attachment.uri
        return URL(string: source)
    }

    /// Photo: uri is the image itself. Scan: previewUri is an image, uri
    /// is the PDF — only render if we have a non-PDF source. Don't trust
    /// file extensions blindly; check against the known attachment types.
    private var canRender: Bool {
        attachment.type == Attachment.typePhoto || attachment.previewUri != nil
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            // Background card fills the ZStack.
            RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                .fill(AppColors.cardSolid)
                .overlay(
                    RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                        .stroke(AppColors.borderDefault, lineWidth: 1)
                )

            // Image or placeholder fills the same layout slot.
            if canRender, let url = thumbURL {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                    default:
                        placeholder
                    }
                }
                .clipShape(
                    RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                )
            } else {
                placeholder
            }

            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.system(size: 10))
                    .foregroundStyle(.white)
                    .frame(width: 22, height: 22)
                    .background(
                        Circle().fill(Color.black.opacity(0.55))
                    )
            }
            .buttonStyle(.plain)
            .padding(4)
        }
        .aspectRatio(1, contentMode: .fit)
    }

    private var placeholder: some View {
        Image(systemName: fallbackIcon)
            .font(.system(size: 24))
            .foregroundStyle(AppColors.textTertiary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
