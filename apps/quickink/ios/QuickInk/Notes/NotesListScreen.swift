/*
 * NotesListScreen.swift
 *
 * QuickInk's notes list — thin SwiftUI wrapper over
 * `ReleafCoreNotes.NotepadListViewModel`. The VM owns search,
 * undo-toast, and the active-entry stream; this screen renders
 * its `entries` and routes taps + the new-note CTA out to
 * `MainShell` via callbacks.
 *
 * Slice 4 deferred bits:
 *   - Search bar (the VM has `query` already; UI lands in Slice 5+)
 *   - Swipe-to-delete + undo toast (VM also has these)
 *   - Recent captures inline alongside notes (Slice 6)
 *
 * Mirror of Android `NotesListScreen.kt`.
 */

import SwiftUI
import ReleafCoreDesignSystem
import ReleafCoreNotes

struct NotesListScreen: View {

    let userId: String
    let onBack: () -> Void
    let onOpenEntry: (_ entryId: String) -> Void

    @StateObject private var vm: NotepadListViewModel

    init(
        userId: String,
        onBack: @escaping () -> Void,
        onOpenEntry: @escaping (_ entryId: String) -> Void
    ) {
        self.userId = userId
        self.onBack = onBack
        self.onOpenEntry = onOpenEntry
        // Construct the shared `NotepadListViewModel` directly off
        // QuickInk's GRDB queue. The repository is passed in;
        // QuickInk doesn't seed entries with an Ayurvedic plant
        // name (that's the Releaf default), so leave `seeder` at
        // its no-op default in `NotepadRepository`.
        let repository = NotepadRepository(dbQueue: QuickInkDatabase.shared.dbQueue)
        _vm = StateObject(
            wrappedValue: NotepadListViewModel(
                repository: repository,
                userId:     userId
            )
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            if vm.entries.isEmpty {
                Spacer()
                emptyState
                Spacer()
            } else {
                List {
                    ForEach(vm.entries, id: \.id) { entry in
                        Button(action: { onOpenEntry(entry.id) }) {
                            row(for: entry)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(AppColors.canvas)
                    }
                }
                .listStyle(.plain)
                .background(AppColors.canvas)
                .scrollContentBackground(.hidden)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.canvas.ignoresSafeArea())
        .task {
            // `start()` opens the FTS observation stream the VM
            // exposes through `entries`. Idempotent if already
            // started — the VM guards re-entry.
            vm.start()
        }
    }

    @ViewBuilder
    private var topBar: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18))
                    .foregroundStyle(AppColors.textPrimary)
                    .padding(AppSpacing.s3)
            }
            .accessibilityLabel("Back")

            Text("Notes")
                .font(AppText.pageTitle)
                .foregroundStyle(AppColors.textPrimary)

            Spacer()

            Button(action: { onOpenEntry(NotepadEditorViewModel.newEntryId) }) {
                Image(systemName: "plus")
                    .font(.system(size: 20))
                    .foregroundStyle(AppColors.textPrimary)
                    .padding(AppSpacing.s3)
            }
            .accessibilityLabel("New note")
        }
        .padding(.horizontal, AppSpacing.s2)
        .padding(.top, AppSpacing.s2)
    }

    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: AppSpacing.s2) {
            Image(systemName: "note.text")
                .font(.system(size: 48))
                .foregroundStyle(AppColors.textTertiary)

            Text("No notes yet")
                .font(AppText.body)
                .foregroundStyle(AppColors.textSecondary)

            Text("Tap + to write your first one.")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textTertiary)
        }
    }

    @ViewBuilder
    private func row(for entry: NotepadEntry) -> some View {
        VStack(alignment: .leading, spacing: AppSpacing.s1) {
            Text(entry.title?.isEmpty == false ? entry.title! : "Untitled")
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
                .lineLimit(1)

            if !entry.notes.isEmpty {
                Text(entry.notes)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
                    .lineLimit(2)
            }

            Text(entry.entryDate)
                .font(AppText.meta)
                .foregroundStyle(AppColors.textTertiary)
        }
        .padding(.vertical, AppSpacing.s2)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
