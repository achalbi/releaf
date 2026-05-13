/*
 * SmartCollectionEditorView.swift
 *
 * Workspace v1 minimum-viable smart-collection editor (iOS). Lets
 * the user combine:
 *   - a name
 *   - an optional folder filter (folder_is)
 *   - an optional date-range preset (date_range over created_at)
 *
 * Mirror of `SmartCollectionEditorDialog.kt` (Android).
 */

import SwiftUI
import GRDB
import ReleafCoreData
import ReleafCoreDesignSystem

@MainActor
struct SmartCollectionEditorView: View {
    let folders: [FolderEntity]
    let onSubmit: (_ name: String, _ folderId: String?, _ datePreset: String?) -> Void
    let onCancel: () -> Void

    @State private var name: String = ""
    @State private var folderId: String? = nil
    @State private var datePreset: String? = nil

    private struct Option<T: Hashable>: Hashable {
        let id: T?
        let label: String
    }

    private var folderOptions: [Option<String>] {
        [Option(id: nil, label: "Any")] +
            folders.map { Option(id: $0.id, label: $0.name) }
    }

    private let dateOptions: [Option<String>] = [
        Option(id: nil,             label: "Any time"),
        Option(id: "this_week",     label: "This week"),
        Option(id: "this_month",    label: "This month"),
        Option(id: "last_30_days",  label: "Last 30 days"),
        Option(id: "this_quarter",  label: "This quarter"),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s4) {
                    TextField("Name", text: $name)
                        .textFieldStyle(.roundedBorder)

                    Text("FOLDER")
                        .font(.system(size: 10, weight: .bold))
                        .tracking(1.2)
                        .foregroundColor(QuickInkColors.muted)
                    chipRow(options: folderOptions, selected: folderId) { folderId = $0 }

                    Text("WHEN CREATED")
                        .font(.system(size: 10, weight: .bold))
                        .tracking(1.2)
                        .foregroundColor(QuickInkColors.muted)
                    chipRow(options: dateOptions, selected: datePreset) { datePreset = $0 }
                }
                .padding(QuickInkSpacing.s4)
            }
            .navigationTitle("New smart collection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSubmit(trimmed, folderId, datePreset)
                    }
                    .disabled(folderId == nil && datePreset == nil)
                }
            }
        }
    }

    @ViewBuilder
    private func chipRow<T: Hashable>(
        options: [Option<T>],
        selected: T?,
        onSelect: @escaping (T?) -> Void
    ) -> some View {
        FlowChipsRow {
            ForEach(options, id: \.self) { option in
                let isActive = option.id == selected
                Button(action: { onSelect(option.id) }) {
                    Text(option.label)
                        .font(.system(size: 11.5))
                        .foregroundColor(isActive ? .white : QuickInkColors.inkSoft)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 5)
                        .background(isActive ? QuickInkColors.ink : QuickInkColors.surface,
                                    in: Capsule())
                        .overlay(
                            Capsule().stroke(
                                isActive ? QuickInkColors.ink : QuickInkColors.border,
                                lineWidth: 1
                            )
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Lightweight wrapping HStack stand-in for SwiftUI Layout APIs.
/// Bounded option count keeps a single-row HStack acceptable for
/// v1; iOS 16+ `Layout`-based wrapping is a follow-up if the
/// option list grows.
struct FlowChipsRow<Content: View>: View {
    let content: () -> Content
    init(@ViewBuilder _ content: @escaping () -> Content) { self.content = content }
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) { content() }
        }
    }
}
