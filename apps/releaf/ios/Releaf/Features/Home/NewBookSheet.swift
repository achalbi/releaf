/*
 * NewBookSheet.swift
 *
 * Modal sheet used by the shelves view "+ New notebook" action.
 * Asks for a book name and a shelf. A "+ New shelf…" row opens a
 * nested sheet that creates a shelf and preselects it.
 *
 * The sheet itself holds no schema knowledge — it returns
 * `(title, shelfId)` via the `onConfirm` callback and lets
 * `ShelvesViewModel` persist.
 */

import SwiftUI
import ReleafData
import ReleafDesignSystem

struct NewBookSheet: View {
    let shelves: [Shelf]
    let onConfirm: (String, String) -> Void
    let onCreateShelf: (String, @escaping (String) -> Void) -> Void
    let onDismiss: () -> Void

    @State private var title: String = ""
    @State private var selectedShelfId: String
    @State private var isPickingShelf: Bool = false
    @State private var isCreatingShelf: Bool = false
    @State private var newShelfName: String = ""

    init(
        shelves: [Shelf],
        onConfirm: @escaping (String, String) -> Void,
        onCreateShelf: @escaping (String, @escaping (String) -> Void) -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.shelves = shelves
        self.onConfirm = onConfirm
        self.onCreateShelf = onCreateShelf
        self.onDismiss = onDismiss
        _selectedShelfId = State(initialValue: shelves.first?.id ?? "shelf-general")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Book name") {
                    TextField("e.g. Plant log", text: $title)
                        .textInputAutocapitalization(.sentences)
                }

                Section("Shelf") {
                    Picker("Shelf", selection: $selectedShelfId) {
                        ForEach(shelves) { shelf in
                            Text(shelf.name).tag(shelf.id)
                        }
                    }
                    Button {
                        isCreatingShelf = true
                        newShelfName = ""
                    } label: {
                        Label("New shelf…", systemImage: "plus")
                            .foregroundStyle(AppColors.coral)
                    }
                }
            }
            .navigationTitle("New notebook")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onDismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
                        let resolved = trimmed.isEmpty ? "Untitled notebook" : trimmed
                        onConfirm(resolved, selectedShelfId)
                        onDismiss()
                    }
                }
            }
            .sheet(isPresented: $isCreatingShelf) {
                NewShelfSheet(
                    initialName: newShelfName,
                    onConfirm:   { name in
                        onCreateShelf(name) { id in
                            selectedShelfId = id
                            isCreatingShelf = false
                        }
                    },
                    onDismiss: { isCreatingShelf = false }
                )
                .presentationDetents([.medium])
            }
        }
    }
}

private struct NewShelfSheet: View {
    let initialName: String
    let onConfirm: (String) -> Void
    let onDismiss: () -> Void

    @State private var name: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Shelf name") {
                    TextField("e.g. Garden", text: $name)
                        .textInputAutocapitalization(.sentences)
                }
            }
            .navigationTitle("New shelf")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onDismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        onConfirm(name.trimmingCharacters(in: .whitespacesAndNewlines))
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear { name = initialName }
        }
    }
}
