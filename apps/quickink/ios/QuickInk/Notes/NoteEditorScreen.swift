/*
 * NoteEditorScreen.swift
 *
 * QuickInk's note editor — thin SwiftUI wrapper over
 * `ReleafCoreNotes.NotepadEditorViewModel`. The VM owns the
 * bootstrap (load existing or set up a draft), the bound title +
 * notes strings, and the persistence side-effects.
 *
 * Slice 4 deferred bits:
 *   - Rich text — Slice 4 uses plain `TextEditor` for the body.
 *     Wiring `RichTextEditor` (UITextView bridge) is a Slice 5
 *     polish item; the VM's `notes` is a plain `String` either
 *     way, so this is a UI swap, not a data-shape change.
 *   - Section sub-editors (contacts / todos / locations /
 *     attachments) — Releaf surfaces, not QuickInk's.
 *   - Category picker, calendar bloom, etc. — Releaf-flavored UX.
 *
 * Mirror of Android `NoteEditorScreen.kt`.
 */

import SwiftUI
import ReleafCoreDesignSystem
import ReleafCoreNotes

struct NoteEditorScreen: View {

    let entryId: String
    let userId: String
    let onBack: () -> Void

    @StateObject private var vm: NotepadEditorViewModel

    init(entryId: String, userId: String, onBack: @escaping () -> Void) {
        self.entryId = entryId
        self.userId = userId
        self.onBack = onBack

        let repository = NotepadRepository(dbQueue: QuickInkDatabase.shared.dbQueue)
        _vm = StateObject(
            wrappedValue: NotepadEditorViewModel(
                repository: repository,
                entryId:    entryId,
                userId:     userId
            )
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            if vm.isLoading {
                Spacer()
                ProgressView()
                    .tint(AppColors.themeGreenPrimary)
                Spacer()
            } else {
                editorBody
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.canvas.ignoresSafeArea())
        .task {
            await vm.bootstrap()
        }
    }

    @ViewBuilder
    private var topBar: some View {
        HStack {
            Button(action: backAndPersist) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18))
                    .foregroundStyle(AppColors.textPrimary)
                    .padding(AppSpacing.s3)
            }
            .accessibilityLabel("Back")

            Spacer()

            // Delete is hidden for new drafts (vm.entry == nil
            // until first save). Same convention Releaf uses on
            // the editor — no delete CTA when there's no row to
            // delete yet.
            if vm.entry != nil {
                Button(action: deleteAndDismiss) {
                    Image(systemName: "trash")
                        .font(.system(size: 18))
                        .foregroundStyle(AppColors.coralDeep)
                        .padding(AppSpacing.s3)
                }
                .accessibilityLabel("Delete note")
            }
        }
        .padding(.horizontal, AppSpacing.s2)
        .padding(.top, AppSpacing.s2)
    }

    @ViewBuilder
    private var editorBody: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            TextField("Title", text: $vm.title)
                .font(AppText.pageTitle)
                .foregroundStyle(AppColors.textPrimary)
                .padding(.horizontal, AppSpacing.s5)

            Text(vm.entryDate)
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
                .padding(.horizontal, AppSpacing.s5)

            // Plain TextEditor — rich text comes in a follow-up.
            // The VM's `notes` is a `String` either way; this is
            // a UI-only swap when RichTextEditor wires through.
            TextEditor(text: $vm.notes)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
                .scrollContentBackground(.hidden)
                .padding(.horizontal, AppSpacing.s5)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .padding(.top, AppSpacing.s3)
    }

    // MARK: - Actions

    private func backAndPersist() {
        // Save-on-back matches Releaf's editor flow — no separate
        // Save button. The VM's `save()` is a no-op when there's
        // nothing to commit (canSave == false), so it's safe to
        // always call.
        if vm.canSave {
            vm.save()
            // Slice 4.2c — kick an immediate sync so the just-
            // saved row pushes to Drive in seconds rather than
            // waiting for the 15-min periodic. Hook lives here
            // (not inside the VM) because `NotepadEditorViewModel`
            // is shared with Releaf via `ReleafCoreNotes` —
            // adding sync coupling there would force Releaf to
            // adopt QuickInk's mutation-site requestImmediate
            // pattern, which it doesn't currently use.
            //
            // Race note: `vm.save()` spawns a background Task and
            // returns synchronously, so this requestImmediate
            // races the GRDB write. In practice the worker is
            // `.utility` priority (50-100ms cold start) and the
            // GRDB write completes in <10ms, so the row is dirty
            // by the time the worker reads. If the worker DID
            // win the race the pass would no-op and the next
            // 15-min periodic catches up. Net: at most one wasted
            // pass on a vanishingly rare schedule, never a missed
            // sync. (The delete path below is async-callback-
            // shaped and so doesn't have this race.)
            QuickInkSyncEnvironment.shared.scheduler.requestImmediate()
        }
        onBack()
    }

    private func deleteAndDismiss() {
        // Soft-delete also marks the row dirty (deleted_at +
        // updated_at), so the next sync pass pushes the
        // tombstone. The VM's delete is async — onDeleted fires
        // on the main actor AFTER the GRDB write completes — so
        // requesting an immediate sync from inside that callback
        // guarantees the worker reads a dirty row, not an empty
        // dirty set.
        vm.delete(onDeleted: {
            QuickInkSyncEnvironment.shared.scheduler.requestImmediate()
            onBack()
        })
    }
}
