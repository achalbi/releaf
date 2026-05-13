/*
 * FolderEditorComponents.swift
 *
 * Action sheet + editor + delete confirmation for Workspace home
 * folder CRUD (Phase B.1). Mirror of `FolderEditorComponents.kt`
 * in QuickInk's Android target.
 *
 * SwiftUI presentation flow (driven from `WorkspaceHomeScreen`):
 *   - Long-press a folder row → `FolderActionSheet` opens with
 *     Rename / Change color / Delete options. Unfiled (the
 *     `is_default = true` row) shows only a read-only header.
 *   - Tapping the "NEW FOLDER" link or one of Rename / Change
 *     color presents `FolderEditorView` in the matching mode.
 *   - Tapping Delete presents a SwiftUI `.alert` confirming the
 *     move-to-Unfiled behaviour.
 */

import SwiftUI
import ReleafCoreDesignSystem

// MARK: - Action sheet (long-press)

struct FolderActionSheet: View {
    let folder: FolderEntity
    let onRename: () -> Void
    let onChangeColor: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: AppSpacing.s3) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(colorFromHex(folder.color) ?? QuickInkColors.accent)
                    .frame(width: 24, height: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(folder.name)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(QuickInkColors.ink)
                    if folder.isDefault {
                        Text("Default folder · can't be edited or deleted")
                            .font(.system(size: 11.5))
                            .foregroundColor(QuickInkColors.muted)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, AppSpacing.s4)
            .padding(.vertical, AppSpacing.s3)

            if !folder.isDefault {
                Divider().background(QuickInkColors.border)
                Button(action: onRename) {
                    HStack {
                        Text("Rename").font(.system(size: 15))
                            .foregroundColor(QuickInkColors.ink)
                        Spacer()
                    }
                    .padding(.horizontal, AppSpacing.s4)
                    .padding(.vertical, 14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Button(action: onChangeColor) {
                    HStack {
                        Text("Change color").font(.system(size: 15))
                            .foregroundColor(QuickInkColors.ink)
                        Spacer()
                    }
                    .padding(.horizontal, AppSpacing.s4)
                    .padding(.vertical, 14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Button(action: onDelete) {
                    HStack {
                        Text("Delete folder").font(.system(size: 15))
                            .foregroundColor(QuickInkColors.danger)
                        Spacer()
                    }
                    .padding(.horizontal, AppSpacing.s4)
                    .padding(.vertical, 14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 24)
        }
        .background(QuickInkColors.surface)
    }
}

// MARK: - Editor (create / rename / recolor)

struct FolderEditorView: View {
    let mode: FolderEditorMode
    let onSubmit: (_ name: String, _ color: String) -> Void
    let onCancel: () -> Void

    @State private var nameDraft: String
    @State private var colorDraft: String

    init(
        mode: FolderEditorMode,
        onSubmit: @escaping (String, String) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.mode = mode
        self.onSubmit = onSubmit
        self.onCancel = onCancel
        switch mode {
        case .create:
            _nameDraft  = State(initialValue: "")
            _colorDraft = State(initialValue: workspaceFolderPalette[0])
        case .edit(let folder, _):
            _nameDraft  = State(initialValue: folder.name)
            _colorDraft = State(initialValue: folder.color)
        }
    }

    private var title: String {
        switch mode {
        case .create: return "New folder"
        case .edit(_, .rename): return "Rename folder"
        case .edit(_, .recolor): return "Change folder color"
        }
    }

    private var showsNameField: Bool {
        switch mode {
        case .edit(_, .recolor): return false
        default:                 return true
        }
    }

    private var showsColorPicker: Bool {
        switch mode {
        case .edit(_, .rename): return false
        default:                return true
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s4) {
                    if showsNameField {
                        TextField("Folder name", text: $nameDraft)
                            .textFieldStyle(.roundedBorder)
                            .autocorrectionDisabled(false)
                    }

                    if showsColorPicker {
                        VStack(alignment: .leading, spacing: AppSpacing.s2) {
                            Text("Color")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(QuickInkColors.inkSoft)
                            HStack(spacing: 8) {
                                ForEach(workspaceFolderPalette, id: \.self) { hex in
                                    Button(action: { colorDraft = hex }) {
                                        Circle()
                                            .fill(colorFromHex(hex) ?? QuickInkColors.accent)
                                            .frame(width: 32, height: 32)
                                            .overlay(
                                                Circle()
                                                    .stroke(
                                                        hex == colorDraft ? QuickInkColors.ink : Color.clear,
                                                        lineWidth: 2,
                                                    )
                                            )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
                .padding(AppSpacing.s4)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let trimmed = nameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                        if trimmed.isEmpty, showsNameField {
                            return
                        }
                        onSubmit(trimmed, colorDraft)
                    }
                }
            }
        }
    }
}
