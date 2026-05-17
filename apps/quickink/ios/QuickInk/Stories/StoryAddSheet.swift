/*
 * StoryAddSheet.swift
 *
 * The "+ Add" bottom sheet from §7.3a of the v3 mockup. Three
 * sections — capture / library / layout — each with icon + label +
 * hint rows. The italic subtitle anchors the insertion point with
 * "after — \"<preceding caption>\"".
 *
 * Phase 2 scope:
 *   - Inline kinds (text_block, handwritten_note, date_divider,
 *     place_pin): fully functional — `onPickInlineKind`.
 *   - Voice clip: opens an in-sheet tap-and-hold recorder
 *     (`StoryVoiceClipRecorderView`); on release commits via
 *     `onPickVoiceClip`. Local-only AAC-LC 64 kbps mono, max 10 s.
 *     Drive sync wires up via the dirty flag set on insert.
 *   - Capture (scan / take photo) + library pickers stub to
 *     `onPickStubbed` (a no-op closer) — Phase 2 follow-up.
 *
 * Mirror of Android `StoryAddSheet.kt`.
 */

import SwiftUI

struct StoryAddSheet: View {

    let precedingItemCaption: String?
    /// User ID — threaded down so the library picker can scope its
    /// capture query.
    let userId: String
    var onPickInlineKind: (StoryItem.Kind) -> Void
    var onPickVoiceClip: (_ audioUri: String, _ durationMs: Int) -> Void
    /// Phase 2 follow-up — fires after the user picks a capture from
    /// the library picker. `kind` discriminates "photo"
    /// (`StoryItem.Kind.photo`) vs "document" (`.document`); both
    /// flow into the same story_item row but carry their semantic
    /// kind for renderer dispatch.
    var onPickCapture: (_ captureId: String, _ kind: StoryItem.Kind) -> Void
    var onPickStubbed: () -> Void = {}

    @State private var showVoiceRecorder = false
    @State private var pickerFilter: StoryLibraryPickerSheet.Filter? = nil
    @State private var showNotePicker = false
    @State private var stubToast: String? = nil

    var body: some View {
        VStack(spacing: 0) {
            handle
            header
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
                    sectionHeader("CAPTURE NEW")
                    row(icon: "camera",                 label: "Scan a page",          hint: "camera",       action: stub("Scan a page coming soon"))
                    row(icon: "photo",                  label: "Take a photo",         hint: "camera",       action: stub("Take a photo coming soon"))
                    row(icon: "mic",                    label: "Record a voice note",  hint: "tap & hold",   action: { showVoiceRecorder = true })

                    sectionDivider()
                    sectionHeader("FROM YOUR LIBRARY")
                    row(icon: "photo.on.rectangle",     label: "Choose a photo",       hint: "picker",       action: { pickerFilter = .photo })
                    row(icon: "doc.text",               label: "Choose a document",    hint: "picker",       action: { pickerFilter = .document })
                    row(icon: "note.text",              label: "Choose a note",        hint: "picker",       action: { showNotePicker = true })

                    sectionDivider()
                    sectionHeader("LAYOUT")
                    row(icon: "textformat",             label: "Write a paragraph",    hint: "serif",        action: { onPickInlineKind(.textBlock) })
                    row(icon: "pencil",                 label: "Handwritten note",     hint: "Caveat",       action: { onPickInlineKind(.handwrittenNote) })
                    row(icon: "calendar",               label: "Date divider",         hint: "— May 5 —",    action: { onPickInlineKind(.dateDivider) })
                    row(icon: "mappin.and.ellipse",     label: "Place pin",            hint: "Shibuya",      action: { onPickInlineKind(.placePin) })
                }
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s6)
            }
            if let toast = stubToast {
                Text(toast)
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .padding(.horizontal, QuickInkSpacing.s4)
                    .padding(.vertical, QuickInkSpacing.s2)
            }
        }
        .background(QuickInkColors.surface)
        .sheet(isPresented: $showVoiceRecorder) {
            StoryVoiceClipRecorderView(
                onSave: { uri, durationMs in
                    showVoiceRecorder = false
                    onPickVoiceClip(uri, durationMs)
                },
                onCancel: { showVoiceRecorder = false }
            )
            .presentationDetents([.medium])
        }
        .sheet(item: Binding(
            get: { pickerFilter.map { PickerFilterIdentifier(filter: $0) } },
            set: { newValue in pickerFilter = newValue?.filter }
        )) { identifier in
            StoryLibraryPickerSheet(
                userId: userId,
                filter: identifier.filter,
                onPick:   { captureId in
                    let kind: StoryItem.Kind = identifier.filter == .photo ? .photo : .document
                    pickerFilter = nil
                    onPickCapture(captureId, kind)
                },
                onCancel: { pickerFilter = nil }
            )
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showNotePicker) {
            StoryNotePickerSheet(
                userId: userId,
                onPick:   { entryId in
                    showNotePicker = false
                    onPickCapture(entryId, .note)
                },
                onCancel: { showNotePicker = false }
            )
            .presentationDetents([.medium, .large])
        }
    }

    /// SwiftUI's `.sheet(item:)` needs an Identifiable wrapper; the
    /// raw enum can't be `Identifiable` cleanly (no stable `id` for
    /// the case payload). This thin struct gives us one.
    private struct PickerFilterIdentifier: Identifiable {
        let filter: StoryLibraryPickerSheet.Filter
        var id: String { filter.rawValue }
    }

    // MARK: - Pieces

    private var handle: some View {
        Capsule()
            .fill(QuickInkColors.border)
            .frame(width: 38, height: 4)
            .padding(.top, QuickInkSpacing.s2)
            .padding(.bottom, QuickInkSpacing.s3)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Add")
                .font(QuickInkText.editorial)
                .foregroundStyle(QuickInkColors.ink)
            if let caption = precedingItemCaption, !caption.isEmpty {
                (Text("after — ") +
                 Text("\"\(caption)\"").italic())
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
            } else {
                Text("at the start of the story")
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s3)
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11, weight: .medium))
            .tracking(1.5)
            .foregroundStyle(QuickInkColors.muted)
            .padding(.top, QuickInkSpacing.s2)
    }

    private func sectionDivider() -> some View {
        Rectangle()
            .fill(QuickInkColors.borderSoft)
            .frame(height: 0.5)
            .padding(.vertical, QuickInkSpacing.s1)
    }

    private func row(icon: String, label: String, hint: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: QuickInkSpacing.s3) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8).fill(QuickInkColors.borderSoft)
                        .frame(width: 36, height: 36)
                    Image(systemName: icon)
                        .font(.system(size: 16))
                        .foregroundStyle(QuickInkColors.ink)
                }
                Text(label)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                Spacer()
                Text(hint)
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.muted)
            }
            .padding(.vertical, QuickInkSpacing.s2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func stub(_ message: String) -> () -> Void {
        return {
            stubToast = message
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 1_800_000_000)
                stubToast = nil
            }
        }
    }
}
