/*
 * CategoriesSettingsScreen.swift
 *
 * Settings → Categories. CRUD list of the user's categories.
 * Backed by `TagRepository` — same data source the scan-
 * review chip picker reads, so an add/remove here flows back into
 * the picker on the next scan with no extra wiring.
 *
 * Phase-3 scope: list + add (text field) + soft-delete (swipe).
 * Reorder is in the repository surface but doesn't have UI yet —
 * lands in a follow-up alongside drag-handles.
 *
 * Mirror of Android `CategoriesSettingsScreen.kt`.
 */

import SwiftUI

struct CategoriesSettingsScreen: View {

    let userId: String
    let onBack: () -> Void

    @StateObject private var vm: TagListViewModel
    @State private var newCategoryName: String = ""
    @State private var addError: String?
    @State private var renameTarget: TagEntity?
    @State private var renameDraft: String = ""

    init(userId: String, onBack: @escaping () -> Void) {
        self.userId = userId
        self.onBack = onBack
        _vm = StateObject(wrappedValue: TagListViewModel(userId: userId))
    }

    private let repository = TagRepository()

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                VStack(spacing: QuickInkSpacing.s5) {
                    addRow

                    if vm.categories.isEmpty {
                        emptyState
                    } else {
                        categoriesList
                    }
                }
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s8)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task { vm.start() }
        .alert("Rename category", isPresented: Binding(
            get: { renameTarget != nil },
            set: { if !$0 { renameTarget = nil } }
        )) {
            TextField("Name", text: $renameDraft)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
            Button("Cancel", role: .cancel) { renameTarget = nil }
            Button("Save") { commitRename() }
        } message: {
            Text("Existing scans tagged with this category will be updated to the new name.")
        }
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Back")

            Text("Categories")
                .font(QuickInkText.pageTitle)
                .foregroundStyle(QuickInkColors.ink)

            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
    }

    // MARK: - Add

    @ViewBuilder
    private var addRow: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack(spacing: QuickInkSpacing.s2) {
                TextField("New category", text: $newCategoryName)
                    .font(QuickInkText.body)
                    .padding(.horizontal, QuickInkSpacing.s3)
                    .padding(.vertical, QuickInkSpacing.s3)
                    .background(QuickInkColors.borderSoft)
                    .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()

                Button(action: addCategory) {
                    Image(systemName: "plus")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(QuickInkColors.textOnAccent)
                        .frame(width: 44, height: 44)
                        .background(
                            Circle().fill(canAdd ? QuickInkColors.accent : QuickInkColors.muted)
                        )
                }
                .buttonStyle(.plain)
                .disabled(!canAdd)
                .accessibilityLabel("Add category")
            }

            if let addError {
                Text(addError)
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.danger)
            }
        }
    }

    private var canAdd: Bool {
        !trimmedNewName.isEmpty
    }

    private var trimmedNewName: String {
        newCategoryName.trimmingCharacters(in: .whitespaces)
    }

    private func addCategory() {
        let name = trimmedNewName
        guard !name.isEmpty else { return }
        let position = (vm.categories.map(\.position).max() ?? -1) + 1
        Task {
            do {
                let inserted = try await repository.insert(
                    userId:   userId,
                    name:     name,
                    position: position
                )
                if inserted {
                    newCategoryName = ""
                    addError = nil
                } else {
                    addError = "“\(name)” already exists."
                }
            } catch {
                addError = "Couldn't add: \(error.localizedDescription)"
            }
        }
    }

    // MARK: - List

    @ViewBuilder
    private var categoriesList: some View {
        VStack(spacing: QuickInkSpacing.s2) {
            ForEach(vm.categories, id: \.id) { cat in
                categoryRow(cat: cat)
            }
        }
    }

    @ViewBuilder
    private func categoryRow(cat: TagEntity) -> some View {
        let isPredefined = TagRepository.isPredefined(cat.name)
        HStack(spacing: QuickInkSpacing.s2) {
            Text(cat.name)
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.ink)

            if isPredefined {
                // Lock badge — system-managed seeds aren't
                // delete/renameable from the UI.
                Image(systemName: "lock.fill")
                    .font(.system(size: 11))
                    .foregroundStyle(QuickInkColors.muted)
                    .accessibilityLabel("Predefined category — read only")
            }

            Spacer()

            if !isPredefined {
                Button(action: { startRename(cat: cat) }) {
                    Image(systemName: "pencil")
                        .font(.system(size: 14))
                        .foregroundStyle(QuickInkColors.muted)
                        .padding(QuickInkSpacing.s2)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Rename \(cat.name)")

                Button(action: { delete(cat: cat) }) {
                    Image(systemName: "trash")
                        .font(.system(size: 14))
                        .foregroundStyle(QuickInkColors.muted)
                        .padding(QuickInkSpacing.s2)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Delete \(cat.name)")
            }
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.vertical, QuickInkSpacing.s3)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    private func delete(cat: TagEntity) {
        Task {
            try? await repository.softDelete(id: cat.id)
        }
    }

    private func startRename(cat: TagEntity) {
        renameDraft = cat.name
        renameTarget = cat
    }

    private func commitRename() {
        guard let target = renameTarget else { return }
        let trimmed = renameDraft.trimmingCharacters(in: .whitespaces)
        renameTarget = nil
        guard !trimmed.isEmpty, trimmed != target.name else { return }
        Task {
            try? await repository.renameAndPropagate(
                id:      target.id,
                oldName: target.name,
                newName: trimmed,
                userId:  userId
            )
        }
    }

    // MARK: - Empty state

    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: QuickInkSpacing.s2) {
            Text("No categories yet")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.ink)
            Text("Add one above so you can tag scans on the review screen.")
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.inkSoft)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(QuickInkSpacing.s4)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }
}
