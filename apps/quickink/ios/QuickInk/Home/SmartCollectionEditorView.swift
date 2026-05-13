/*
 * SmartCollectionEditorView.swift
 *
 * Workspace v1 smart-collection editor. All six clause types from
 * `SmartCollectionRule.swift` are reachable from the UI:
 *
 *   - folder_is        (one folder)
 *   - date_range       (created_at preset)
 *   - tag_is           (one or more tags the capture must carry)
 *   - tag_is_not       (one or more tags the capture must NOT carry)
 *   - source_is        (scan / import / share-extension)
 *   - has_handwriting / has_signature / has_ocr_text (OCR signals;
 *     evaluator returns false until Phase E lights up the columns,
 *     but authoring them is exposed today so users can pre-build
 *     rules that activate when the signals arrive).
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
    let tags: [TagEntity]
    let initialName: String
    let initialInput: SmartCollectionRuleInput
    let initialIcon: String?
    let initialColor: String?
    let isEdit: Bool
    let onSubmit: (_ name: String, _ input: SmartCollectionRuleInput,
                   _ icon: String?, _ color: String?) -> Void
    let onCancel: () -> Void

    @State private var name: String
    @State private var input: SmartCollectionRuleInput
    @State private var iconSlug: String?
    @State private var colorHex: String?

    init(
        folders: [FolderEntity],
        tags: [TagEntity] = [],
        initialName: String = "",
        initialInput: SmartCollectionRuleInput = SmartCollectionRuleInput(),
        initialIcon: String? = nil,
        initialColor: String? = nil,
        isEdit: Bool = false,
        onSubmit: @escaping (String, SmartCollectionRuleInput, String?, String?) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.folders = folders
        self.tags = tags
        self.initialName = initialName
        self.initialInput = initialInput
        self.initialIcon = initialIcon
        self.initialColor = initialColor
        self.isEdit = isEdit
        self.onSubmit = onSubmit
        self.onCancel = onCancel
        _name     = State(initialValue: initialName)
        _input    = State(initialValue: initialInput)
        _iconSlug = State(initialValue: initialIcon)
        _colorHex = State(initialValue: initialColor)
    }

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

    private let sourceOptions: [Option<String>] = [
        Option(id: nil,                 label: "Any"),
        Option(id: "scan",              label: "Scan"),
        Option(id: "import",            label: "Import"),
        Option(id: "share-extension",   label: "Share"),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s4) {
                    TextField("Name", text: $name)
                        .textFieldStyle(.roundedBorder)

                    sectionLabel("FOLDER")
                    chipRow(options: folderOptions, selected: input.folderId) {
                        input.folderId = $0
                    }

                    sectionLabel("WHEN CREATED")
                    chipRow(options: dateOptions, selected: input.datePreset) {
                        input.datePreset = $0
                    }

                    if !tags.isEmpty {
                        sectionLabel("MUST HAVE TAG")
                        tagMultiSelect(selected: input.tagIncludeIds) { id in
                            if let idx = input.tagIncludeIds.firstIndex(of: id) {
                                input.tagIncludeIds.remove(at: idx)
                            } else {
                                input.tagIncludeIds.append(id)
                            }
                        }

                        sectionLabel("MUST NOT HAVE TAG")
                        tagMultiSelect(selected: input.tagExcludeIds) { id in
                            if let idx = input.tagExcludeIds.firstIndex(of: id) {
                                input.tagExcludeIds.remove(at: idx)
                            } else {
                                input.tagExcludeIds.append(id)
                            }
                        }
                    }

                    sectionLabel("SOURCE")
                    chipRow(options: sourceOptions, selected: input.sourceValue) {
                        input.sourceValue = $0
                    }

                    sectionLabel("OCR SIGNALS")
                    // Each OCR signal is a tri-state chip: unset →
                    // require true → require false. Tap cycles
                    // through. Cycling matches the rule grammar's
                    // `Bool` payload.
                    ocrChip(label: "Handwriting", state: input.hasHandwriting) {
                        input.hasHandwriting = cycle(input.hasHandwriting)
                    }
                    ocrChip(label: "Signature", state: input.hasSignature) {
                        input.hasSignature = cycle(input.hasSignature)
                    }
                    ocrChip(label: "OCR text", state: input.hasOcrText) {
                        input.hasOcrText = cycle(input.hasOcrText)
                    }

                    sectionLabel("ICON")
                    iconPaletteRow

                    sectionLabel("COLOR")
                    colorPaletteRow
                }
                .padding(QuickInkSpacing.s4)
            }
            .navigationTitle(isEdit ? "Edit smart collection" : "New smart collection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSubmit(trimmed, input, iconSlug, colorHex)
                    }
                    .disabled(input.isEmpty)
                }
            }
        }
    }

    @ViewBuilder
    private var iconPaletteRow: some View {
        FlowChipsRow {
            ForEach(SmartCollectionIconPalette, id: \.slug) { option in
                let isActive = option.slug == iconSlug
                Button(action: {
                    iconSlug = isActive ? nil : option.slug
                }) {
                    Image(systemName: option.symbol)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(isActive ? .white : QuickInkColors.inkSoft)
                        .frame(width: 32, height: 32)
                        .background(isActive ? QuickInkColors.ink : QuickInkColors.surface,
                                    in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .stroke(isActive ? QuickInkColors.ink : QuickInkColors.border,
                                        lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private var colorPaletteRow: some View {
        FlowChipsRow {
            ForEach(workspaceFolderPalette, id: \.self) { hex in
                let isActive = hex == colorHex
                Button(action: {
                    colorHex = isActive ? nil : hex
                }) {
                    Circle()
                        .fill(colorFromHex(hex) ?? QuickInkColors.accent)
                        .frame(width: 28, height: 28)
                        .overlay(
                            Circle().stroke(
                                isActive ? QuickInkColors.ink : QuickInkColors.border,
                                lineWidth: isActive ? 2 : 1
                            )
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .tracking(1.2)
            .foregroundColor(QuickInkColors.muted)
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
                    chipLabel(label: option.label, active: isActive)
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func tagMultiSelect(
        selected: [String],
        onToggle: @escaping (String) -> Void
    ) -> some View {
        FlowChipsRow {
            ForEach(tags, id: \.id) { tag in
                let isActive = selected.contains(tag.id)
                Button(action: { onToggle(tag.id) }) {
                    chipLabel(label: tag.name, active: isActive)
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func ocrChip(
        label: String,
        state: Bool?,
        onCycle: @escaping () -> Void
    ) -> some View {
        // Mirror the Android `OcrTriStateChip`: unset → "Any",
        // true → "Yes", false → "No".
        let suffix: String = {
            switch state {
            case .none:        return "Any"
            case .some(true):  return "Yes"
            case .some(false): return "No"
            }
        }()
        let isActive = state != nil
        Button(action: onCycle) {
            chipLabel(label: "\(label) · \(suffix)", active: isActive)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func chipLabel(label: String, active: Bool) -> some View {
        Text(label)
            .font(.system(size: 11.5))
            .foregroundColor(active ? .white : QuickInkColors.inkSoft)
            .padding(.horizontal, 11)
            .padding(.vertical, 5)
            .background(active ? QuickInkColors.ink : QuickInkColors.surface,
                        in: Capsule())
            .overlay(
                Capsule().stroke(
                    active ? QuickInkColors.ink : QuickInkColors.border,
                    lineWidth: 1
                )
            )
    }

    private func cycle(_ s: Bool?) -> Bool? {
        switch s {
        case .none:        return true
        case .some(true):  return false
        case .some(false): return nil
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
