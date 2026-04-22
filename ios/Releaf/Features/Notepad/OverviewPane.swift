/*
 * OverviewPane.swift
 *
 * Alternative notepad-editor layout matching `PageDetailView`
 * (home → notebook → page). Same `CaptureTabBar` + section-per-tab
 * shape, but fed by the editor's own mutable state so add/remove
 * routes through the VM.
 *
 * Notes editing moved off the inline canvas and into
 * `NotesEditorSheet` — tap the notes preview card to open a
 * dedicated full-sheet editor with the format toolbar pinned above
 * the keyboard. Keeps the Overview tab readable and still makes
 * editing a one-tap affordance.
 *
 * All seven capture modes are surfaced now — Voice was re-enabled
 * once `VoiceSection` landed. Each tab gets its own section body;
 * Overview shows the stat grid + notes preview card.
 */

import SwiftUI
import ReleafData
import ReleafDesignSystem

struct OverviewPane: View {
    @Binding var notes: String
    @ObservedObject var richTextController: RichTextEditorController
    let contacts: [NotepadContact]
    let todos: [NotepadTodo]
    let locations: [GeoLocation]
    let attachments: [Attachment]
    let onAddContact: (String) -> Void
    let onRemoveContact: (String) -> Void
    let onAddTodo: (String) -> Void
    let onToggleTodo: (String) -> Void
    let onRemoveTodo: (String) -> Void
    let onAddLocation: (Double, Double, String?) -> Void
    let onRemoveLocation: (String) -> Void
    let onAddPhoto: (String) -> Void
    let onAddScan: (String, String?) -> Void
    let onAddVoiceNote: (String, Int) -> Void
    let onTranscribeVoiceNote: (String, String?) -> Void
    let onRemoveAttachment: (String) -> Void

    @State private var selected: CaptureMode = .overview
    @State private var notesSheetOpen: Bool = false

    /// All seven modes render — Voice was re-enabled once
    /// `VoiceSection` landed. Kept as a computed so we can drop modes
    /// again (e.g. for a future "read-only" flavor) without a second
    /// source of truth.
    private var modes: [CaptureMode] { CaptureMode.allCases }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Title + date row lives at screen level now — this pane
            // starts straight at the CaptureTabBar.
            CaptureTabBar(modes: modes, selected: $selected)

            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s4) {
                    sectionBody
                    Spacer(minLength: AppSpacing.s10)
                }
                .padding(AppSpacing.s4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .sheet(isPresented: $notesSheetOpen) {
            NotesEditorSheet(
                notes:      $notes,
                controller: richTextController,
                onDismiss:  { notesSheetOpen = false }
            )
        }
    }

    // MARK: Tab body

    @ViewBuilder private var sectionBody: some View {
        switch selected {
        case .overview:
            OverviewTab(
                notes:       notes,
                contacts:    contacts,
                todos:       todos,
                locations:   locations,
                attachments: attachments,
                onEditNotes: { notesSheetOpen = true }
            )

        case .photos:
            PhotosSection(
                photos:   attachments.filter { $0.type == Attachment.typePhoto },
                onAdd:    onAddPhoto,
                onRemove: onRemoveAttachment
            )

        case .scans:
            ScansSection(
                scans:    attachments.filter { $0.type == Attachment.typeScan },
                onAdd:    onAddScan,
                onRemove: onRemoveAttachment
            )

        case .voice:
            VoiceSection(
                notes:         attachments.filter { $0.type == Attachment.typeVoice },
                onAdd:         onAddVoiceNote,
                onTranscribed: onTranscribeVoiceNote,
                onRemove:      onRemoveAttachment
            )

        case .todo:
            TodosSection(
                todos:    todos,
                onAdd:    onAddTodo,
                onToggle: onToggleTodo,
                onRemove: onRemoveTodo
            )

        case .contacts:
            ContactsSection(
                contacts: contacts,
                onAdd:    onAddContact,
                onRemove: onRemoveContact
            )

        case .location:
            LocationSection(
                locations: locations,
                onAdd:     onAddLocation,
                onRemove:  onRemoveLocation
            )
        }
    }
}

// MARK: - Overview tab (at-a-glance + tappable notes card)

private struct OverviewTab: View {
    let notes: String
    let contacts: [NotepadContact]
    let todos: [NotepadTodo]
    let locations: [GeoLocation]
    let attachments: [Attachment]
    let onEditNotes: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            Text("AT A GLANCE")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textSecondary)

            let photosCount = attachments.filter { $0.type == Attachment.typePhoto }.count
            let scansCount  = attachments.filter { $0.type == Attachment.typeScan }.count

            StatGrid(items: [
                StatItem(label: "Photos", value: "\(photosCount)",  tone: .coral),
                StatItem(label: "Scans",  value: "\(scansCount)",   tone: .neutral),
                StatItem(label: "To-do",  value: "\(todos.count)",  tone: .green),
            ])
            StatGrid(items: [
                StatItem(label: "Contacts", value: "\(contacts.count)",  tone: .info),
                StatItem(label: "Places",   value: "\(locations.count)", tone: .neutral),
                StatItem(label: "Words",    value: "\(wordCount)",       tone: .neutral),
            ])

            NotesPreviewCard(notes: notes, onTap: onEditNotes)
        }
    }

    private var wordCount: Int {
        let trimmed = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return 0 }
        return trimmed.split(whereSeparator: { $0.isWhitespace }).count
    }
}

/// Tappable card with a rendered-markdown preview. Pencil icon in the
/// corner signals the tap-to-edit affordance.
private struct NotesPreviewCard: View {
    let notes: String
    let onTap: () -> Void

    private var isEmpty: Bool {
        notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var attributedNotes: AttributedString {
        var options = AttributedString.MarkdownParsingOptions()
        options.interpretedSyntax = .inlineOnlyPreservingWhitespace
        return (try? AttributedString(markdown: notes, options: options))
            ?? AttributedString(notes)
    }

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                HStack {
                    Text("NOTES")
                        .font(AppText.eyebrow)
                        .tracking(AppLetterSpacing.eyebrow)
                        .foregroundStyle(AppColors.textSecondary)
                    Spacer()
                    Image(systemName: "pencil")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(AppColors.coral)
                }

                if isEmpty {
                    Text("Tap to write notes…")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textTertiary)
                } else {
                    Text(attributedNotes)
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textPrimary)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(AppSpacing.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(AppColors.cardSolid)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}
