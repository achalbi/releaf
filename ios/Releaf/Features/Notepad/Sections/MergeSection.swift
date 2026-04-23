/*
 * MergeSection.swift
 *
 * Notepad-page merge panel. Rendered at the bottom of the notepad
 * editor once there's any content to merge.
 *
 * Contract:
 *   - Tap "Choose another notepad page" → opens a sheet listing the
 *     user's other live entries; selecting one fills the row.
 *   - Radio picks which side stays primary. Primary keeps title +
 *     entry-date; secondary's notes / todos / contacts / locations /
 *     attachments are appended.
 *   - Tap "Merge pages" → calls `onMerge(otherId, keepThisAsPrimary)`.
 *     Caller is responsible for navigating when the current page
 *     becomes the secondary (and therefore gets soft-deleted).
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct MergeSection: View {
    @Environment(\.accentPalette) private var accent

    /// One-shot source of picker options. The section calls this lazily
    /// when the user taps the picker row — avoids eagerly loading every
    /// other entry for editors that never touch merge.
    let loadOtherEntries: () async -> [NotepadEntry]

    /// Gates the Merge button. The editor passes `false` while the row
    /// is still a fresh draft — the VM flushes before committing but
    /// there's nothing to flush for an untouched new entry.
    let enabled: Bool

    /// Fires when the user confirms the merge. Second arg is `true`
    /// when the current page (the editor's own entry) stays primary.
    let onMerge: (_ otherId: String, _ keepThisAsPrimary: Bool) -> Void

    @State private var selected: NotepadEntry?
    @State private var keepThisAsPrimary: Bool = true
    @State private var showPicker: Bool = false
    @State private var options: [NotepadEntry] = []

    public init(
        loadOtherEntries: @escaping () async -> [NotepadEntry],
        enabled: Bool,
        onMerge: @escaping (String, Bool) -> Void
    ) {
        self.loadOtherEntries = loadOtherEntries
        self.enabled = enabled
        self.onMerge = onMerge
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text("MERGE")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(accent.primary)

            VStack(spacing: 0) {
                header
                Divider().overlay(AppColors.borderDefault)
                bodyCard
            }
            .background(AppColors.cardSolid)
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.md))
        }
        .sheet(isPresented: $showPicker) {
            PickerSheet(
                options: options,
                selected: selected,
                onPick: { entry in
                    selected = entry
                    showPicker = false
                }
            )
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            Text("Merge pages")
                .font(AppText.sectionTitle)
                .foregroundStyle(AppColors.textPrimary)
            Spacer()
            Text("Notepad merge")
                .font(AppText.tag)
                .foregroundStyle(AppColors.textSecondary)
                .padding(.horizontal, AppSpacing.s3)
                .padding(.vertical, 6)
                .background(AppColors.borderDefault.opacity(0.5))
                .clipShape(Capsule())
        }
        .padding(AppSpacing.s4)
    }

    private var bodyCard: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            Text("Merge this page with another notepad page")
                .font(AppText.body.weight(.semibold))
                .foregroundStyle(AppColors.textPrimary)

            Text("Choose the other page, then decide which one stays primary. The primary page keeps its title and date, while notes, photos, voice notes, to-dos, and scans from the secondary page are appended into it.")
                .font(AppText.body)
                .foregroundStyle(AppColors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            Text("Other page")
                .font(AppText.body.weight(.semibold))
                .foregroundStyle(AppColors.textPrimary)
                .padding(.top, AppSpacing.s1)

            Button {
                Task {
                    options = await loadOtherEntries()
                    showPicker = true
                }
            } label: {
                HStack {
                    Text(pickerLabel)
                        .font(AppText.body)
                        .foregroundStyle(selected == nil
                                         ? AppColors.textSecondary
                                         : AppColors.textPrimary)
                    Spacer()
                }
                .padding(AppSpacing.s4)
                .frame(maxWidth: .infinity)
                .background(AppColors.canvas)
                .overlay(
                    RoundedRectangle(cornerRadius: AppRadius.md)
                        .stroke(AppColors.borderDefault, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: AppRadius.md))
            }
            .buttonStyle(.plain)

            Text("Primary page")
                .font(AppText.body.weight(.semibold))
                .foregroundStyle(AppColors.textPrimary)
                .padding(.top, AppSpacing.s1)

            RadioRow(
                label: "Keep this page as primary",
                selected: keepThisAsPrimary,
                onTap: { keepThisAsPrimary = true }
            )
            RadioRow(
                label: "Keep the selected page as primary",
                selected: !keepThisAsPrimary,
                onTap: { keepThisAsPrimary = false }
            )

            Text("The secondary page is removed after its content is merged into the primary page.")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack {
                Spacer()
                let canMerge = enabled && selected != nil
                Button {
                    if let s = selected { onMerge(s.id, keepThisAsPrimary) }
                } label: {
                    Text("Merge pages")
                        .font(AppText.button)
                        .foregroundStyle(canMerge ? .white : AppColors.textSecondary)
                        .padding(.horizontal, AppSpacing.s5)
                        .padding(.vertical, AppSpacing.s3)
                        .background(canMerge ? Color(hex: 0x1B1B1D) : AppColors.borderDefault)
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(!canMerge)
            }
        }
        .padding(AppSpacing.s4)
    }

    private var pickerLabel: String {
        if let s = selected {
            return rowLabel(s)
        }
        return "Choose another notepad page"
    }
}

// MARK: - Radio row

private struct RadioRow: View {
    @Environment(\.accentPalette) private var accent
    let label: String
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: AppSpacing.s3) {
                ZStack {
                    Circle()
                        .stroke(
                            selected ? accent.primary : AppColors.borderStrong,
                            lineWidth: 2
                        )
                        .frame(width: 20, height: 20)
                    if selected {
                        Circle()
                            .fill(accent.primary)
                            .frame(width: 10, height: 10)
                    }
                }
                Text(label)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textPrimary)
                Spacer()
            }
            .contentShape(Rectangle())
            .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Picker sheet

private struct PickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accentPalette) private var accent

    let options: [NotepadEntry]
    let selected: NotepadEntry?
    let onPick: (NotepadEntry) -> Void

    var body: some View {
        NavigationStack {
            Group {
                if options.isEmpty {
                    VStack(spacing: AppSpacing.s3) {
                        Text("No other notepad pages yet")
                            .font(AppText.body)
                            .foregroundStyle(AppColors.textSecondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(options) { entry in
                            Button {
                                onPick(entry)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(rowLabel(entry))
                                            .font(AppText.body.weight(.semibold))
                                            .foregroundStyle(AppColors.textPrimary)
                                        Text(entry.entryDate)
                                            .font(AppText.meta)
                                            .foregroundStyle(AppColors.textSecondary)
                                    }
                                    Spacer()
                                    if selected?.id == entry.id {
                                        Text("✓")
                                            .font(.system(size: 16, weight: .bold))
                                            .foregroundStyle(accent.primary)
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Choose page")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Helpers

private func rowLabel(_ entry: NotepadEntry) -> String {
    if let title = entry.title, !title.isEmpty { return title }
    return "Untitled (\(entry.entryDate))"
}
