/*
 * CategoryManagementSection.swift
 *
 * Settings card for managing notepad-entry categories. Predefined
 * categories (Home / Work / Personal / Health / Travel / Ideas) are
 * shown read-only — they're built into the app and can't be removed.
 * Custom categories (anything the user has typed in the editor's
 * picker) get rename + delete actions:
 *
 *   - Rename: bulk-updates every active entry that carries the old
 *     label to the new label. If the new label matches a predefined
 *     name (case-insensitive), the canonical-cased form wins so the
 *     chip row deduplicates instead of forking.
 *   - Delete: bulk-clears the label from every active entry that
 *     carries it. The entries themselves stay live; only the label
 *     is dropped.
 *
 * Adding a category is intentionally NOT done here — the user adds
 * one by typing it into the editor's category picker. That keeps
 * "categories the user has invented" in lockstep with "categories
 * actually attached to entries", so a typo on creation can be fixed
 * here later.
 *
 * Mirror of Android's `CategoryManagementSection.kt`.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct CategoryManagementSection: View {
    @EnvironmentObject private var authStore: AuthStore
    @EnvironmentObject private var uiPrefs: UiPreferences
    @StateObject private var vm = CategoryManagementViewModel()

    @State private var renameTarget: String? = nil
    @State private var renameDraft:  String  = ""
    @State private var deleteTarget: String? = nil

    public init() {}

    public var body: some View {
        // Effective display order: predefined + customs merged into a
        // single list, with the user's preferred ordering applied.
        let ordered = NotepadCategory.applyOrder(
            userOrder: uiPrefs.state.notepadCategoryOrder,
            customs:   vm.customs
        )

        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text("NOTEPAD")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                Text("Categories")
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text("Tap the ↑ / ↓ arrows to reorder. Predefined names are built in (no rename / delete). Add a new category by typing it into the picker on any notepad entry.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }

            // Single ordered list. Each row carries up/down to reorder;
            // custom rows additionally get a pencil + trash on the
            // right.
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                if ordered.isEmpty {
                    Text("No categories yet — type one into the picker on a notepad entry to add it.")
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textTertiary)
                        .padding(.vertical, AppSpacing.s1)
                } else {
                    ForEach(Array(ordered.enumerated()), id: \.element) { (index, name) in
                        let editable = !NotepadCategory.isPredefined(name)
                        CategoryRow(
                            name:        name,
                            editable:    editable,
                            canMoveUp:   index > 0,
                            canMoveDown: index < ordered.count - 1,
                            onMoveUp: {
                                guard index > 0 else { return }
                                var next = ordered
                                next.swapAt(index, index - 1)
                                uiPrefs.setNotepadCategoryOrder(next)
                            },
                            onMoveDown: {
                                guard index < ordered.count - 1 else { return }
                                var next = ordered
                                next.swapAt(index, index + 1)
                                uiPrefs.setNotepadCategoryOrder(next)
                            },
                            onRename: {
                                renameDraft  = name
                                renameTarget = name
                            },
                            onDelete: { deleteTarget = name }
                        )
                    }
                }
            }
        }
        .padding(AppSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid)
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .onAppear {
            if let userId = authStore.session?.userId {
                vm.start(userId: userId)
            }
        }
        .onChange(of: authStore.session?.userId) { newId in
            vm.stop()
            if let id = newId { vm.start(userId: id) }
        }
        .onDisappear { vm.stop() }
        // Rename dialog — iOS 16's `alert(_:isPresented:actions:message:)`
        // supports a TextField, which is exactly the shape we want.
        .alert(
            "Rename category",
            isPresented: Binding(
                get: { renameTarget != nil },
                set: { if !$0 { renameTarget = nil } }
            ),
            actions: {
                TextField("New name", text: $renameDraft)
                Button("Rename") {
                    let target = renameTarget
                    let draft  = renameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                    renameTarget = nil
                    if let userId = authStore.session?.userId,
                       let from   = target,
                       !draft.isEmpty,
                       draft.caseInsensitiveCompare(from) != .orderedSame {
                        Task { try? await vm.rename(userId: userId, from: from, to: draft) }
                    }
                }
                Button("Cancel", role: .cancel) { renameTarget = nil }
            },
            message: {
                Text("Every entry currently filed under \"\(renameTarget ?? "")\" will be moved to the new name.")
            }
        )
        // Delete confirm — destructive role tints the button red.
        .alert(
            "Delete \"\(deleteTarget ?? "")\"?",
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            actions: {
                Button("Delete", role: .destructive) {
                    let target = deleteTarget
                    deleteTarget = nil
                    if let userId = authStore.session?.userId, let name = target {
                        Task { try? await vm.delete(userId: userId, name: name) }
                    }
                }
                Button("Cancel", role: .cancel) { deleteTarget = nil }
            },
            message: {
                Text("Every entry currently filed under \"\(deleteTarget ?? "")\" will become uncategorised. The entries themselves stay put.")
            }
        )
    }
}

/// One category row inside the management card. Every row carries
/// up/down chevrons (greyed at the list ends); custom rows
/// additionally get a pencil + trash pair on the right. Predefined
/// rows can be reordered but not renamed or deleted — they're built
/// into the app.
private struct CategoryRow: View {
    let name: String
    let editable: Bool
    let canMoveUp: Bool
    let canMoveDown: Bool
    let onMoveUp: () -> Void
    let onMoveDown: () -> Void
    let onRename: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: AppSpacing.s2) {
            Image(systemName: "tag.fill")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(editable ? AppColors.themeGreenDeep : AppColors.textTertiary)
            Text(name)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            ReorderArrow(systemImage: "arrow.up", enabled: canMoveUp,   onTap: onMoveUp)
            ReorderArrow(systemImage: "arrow.down", enabled: canMoveDown, onTap: onMoveDown)
            if editable {
                Button(action: onRename) {
                    Image(systemName: "pencil")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(AppColors.textSecondary)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                Button(action: onDelete) {
                    Image(systemName: "trash")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(AppColors.danger)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 6)
    }
}

/// Up / down chevron — disabled (faded, no-op) when the row is at
/// the corresponding end of the list.
private struct ReorderArrow: View {
    let systemImage: String
    let enabled: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(enabled ? AppColors.textSecondary : AppColors.textTertiary)
                .frame(width: 32, height: 32)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// Lightweight view model: subscribes to `NotepadRepository.observeActive`
/// for the signed-in user and re-derives the custom-category list on
/// every emission. Owns the rename / delete async calls so the view
/// doesn't have to stamp out a Task on every button tap directly.
@MainActor
private final class CategoryManagementViewModel: ObservableObject {
    @Published private(set) var customs: [String] = []

    private let repository: NotepadRepository
    private var task: Task<Void, Never>?

    init(repository: NotepadRepository = NotepadRepository()) {
        self.repository = repository
    }

    func start(userId: String) {
        stop()
        task = Task { [weak self, repository] in
            guard let self else { return }
            do {
                for try await entries in repository.observeActive(userId: userId) {
                    self.customs = NotepadCategory.deriveCustomCategories(from: entries)
                }
            } catch {}
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    deinit { task?.cancel() }

    func rename(userId: String, from oldName: String, to newName: String) async throws {
        _ = try await repository.renameCategory(
            userId: userId, oldName: oldName, newName: newName
        )
    }

    func delete(userId: String, name: String) async throws {
        _ = try await repository.deleteCategory(userId: userId, name: name)
    }
}
