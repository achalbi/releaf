/*
 * TagPickerSheet.swift
 *
 * Workspace v1 Screen 6 — SwiftUI bottom sheet for attaching /
 * detaching tags on a single capture. Manual entry only; the
 * AI-suggested strip from the design lands in Phase E.2 (ScanReview
 * surface) for now and rolls into this sheet later if needed.
 *
 * Mirror of `TagPickerSheet.kt` in QuickInk's Android target.
 */

import SwiftUI
import Combine
import GRDB
import ReleafCoreDesignSystem

@MainActor
public struct TagPickerSheet: View {
    public let captureId: String
    public let userId: String
    public let onDismiss: () -> Void

    @StateObject private var viewModel: TagPickerViewModel
    @State private var newTagInput: String = ""
    @State private var selectedIds: Set<String> = []
    @State private var seeded: Bool = false

    public init(
        captureId: String,
        userId: String,
        onDismiss: @escaping () -> Void
    ) {
        self.captureId = captureId
        self.userId = userId
        self.onDismiss = onDismiss
        _viewModel = StateObject(wrappedValue: TagPickerViewModel(
            captureId: captureId, userId: userId
        ))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Add tags")
                .font(.system(size: 19, weight: .semibold))
                .foregroundColor(QuickInkColors.ink)
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, QuickInkSpacing.s3)
            Text("Tags help you find this doc from anywhere in the workspace.")
                .font(.system(size: 12.5))
                .foregroundColor(QuickInkColors.muted)
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, 2)

            newTagField
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, QuickInkSpacing.s3)

            Divider()
                .background(QuickInkColors.border)
                .padding(.top, QuickInkSpacing.s3)

            Text("ALL TAGS")
                .font(.system(size: 10, weight: .bold))
                .tracking(1.2)
                .foregroundColor(QuickInkColors.muted)
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, QuickInkSpacing.s3)
                .padding(.bottom, 4)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if viewModel.allTags.isEmpty {
                        Text("No tags yet. Type one above to start.")
                            .font(.system(size: 12.5))
                            .foregroundColor(QuickInkColors.muted)
                            .padding(.horizontal, QuickInkSpacing.s4)
                            .padding(.vertical, QuickInkSpacing.s2)
                    } else {
                        ForEach(viewModel.allTags) { tag in
                            tagRow(tag)
                        }
                    }
                }
            }
            .frame(maxHeight: 320)

            Divider()
                .background(QuickInkColors.border)

            footer
                .padding(QuickInkSpacing.s4)
        }
        .background(QuickInkColors.surface)
        .onAppear {
            viewModel.start()
        }
        .onReceive(viewModel.$attachedTagIds) { latest in
            // Seed the selection once on first non-empty arrival.
            if !seeded, !latest.isEmpty {
                selectedIds = Set(latest)
                seeded = true
            }
        }
    }

    private var newTagField: some View {
        HStack(spacing: 4) {
            Text("#")
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(QuickInkColors.accent)
            TextField("Type a tag…", text: $newTagInput, onCommit: {
                let normalized = normalizeTagName(newTagInput)
                guard !normalized.isEmpty else {
                    newTagInput = ""
                    return
                }
                Task {
                    let tag = try? await viewModel.findOrCreate(name: normalized)
                    if let id = tag?.id { selectedIds.insert(id) }
                    newTagInput = ""
                }
            })
            .font(.system(size: 13))
            .foregroundColor(QuickInkColors.ink)
        }
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.vertical, 10)
        .background(QuickInkColors.bg, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(QuickInkColors.border, lineWidth: 1))
    }

    private func tagRow(_ tag: TagEntity) -> some View {
        let isSelected = selectedIds.contains(tag.id)
        return Button(action: {
            if isSelected { selectedIds.remove(tag.id) }
            else          { selectedIds.insert(tag.id) }
        }) {
            HStack(spacing: QuickInkSpacing.s2) {
                ZStack {
                    RoundedRectangle(cornerRadius: 5)
                        .fill(isSelected ? QuickInkColors.accent : Color.clear)
                        .frame(width: 18, height: 18)
                        .overlay(
                            RoundedRectangle(cornerRadius: 5)
                                .stroke(
                                    isSelected ? QuickInkColors.accent : QuickInkColors.border,
                                    lineWidth: 1.5
                                )
                        )
                    if isSelected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.white)
                    }
                }
                Text("#")
                    .font(.system(size: 13.5, weight: .regular))
                    .foregroundColor(QuickInkColors.accent.opacity(0.6))
                Text(tag.name)
                    .font(.system(size: 13.5, weight: .medium))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var footer: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Button(action: {
                Task { await commit() }
            }) {
                Text("Save \(selectedIds.count) \(selectedIds.count == 1 ? "tag" : "tags")")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(QuickInkColors.ink, in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)

            Button(action: onDismiss) {
                Text("Cancel")
                    .font(.system(size: 13.5, weight: .medium))
                    .foregroundColor(QuickInkColors.ink)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 13)
                    .background(QuickInkColors.borderSoft, in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
        }
    }

    private func commit() async {
        let original = Set(viewModel.attachedTagIds)
        let toAttach = selectedIds.subtracting(original)
        let toDetach = original.subtracting(selectedIds)
        for id in toAttach {
            try? await viewModel.attach(tagId: id)
        }
        for id in toDetach {
            try? await viewModel.detach(tagId: id)
        }
        onDismiss()
    }
}

@MainActor
final class TagPickerViewModel: ObservableObject {

    @Published private(set) var allTags: [TagEntity] = []
    @Published private(set) var attachedTagIds: [String] = []

    private let captureId: String
    private let userId: String
    private let tagRepo: TagRepository
    private let joinRepo: CaptureTagRepository
    private let dbQueue: DatabaseQueue
    private var cancellables: Set<AnyCancellable> = []

    init(captureId: String, userId: String, database: QuickInkDatabase = .shared) {
        self.captureId = captureId
        self.userId    = userId
        self.tagRepo   = TagRepository(database: database)
        self.joinRepo  = CaptureTagRepository(database: database)
        self.dbQueue   = database.dbQueue
    }

    func start() {
        guard cancellables.isEmpty else { return }
        tagRepo
            .observe(userId: userId)
            .replaceError(with: [])
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.allTags = $0 }
            .store(in: &cancellables)
        joinRepo
            .observeTagIds(captureId: captureId)
            .replaceError(with: [])
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.attachedTagIds = $0 }
            .store(in: &cancellables)
    }

    func findOrCreate(name: String) async throws -> TagEntity {
        try await tagRepo.findOrCreate(userId: userId, name: name)
    }

    func attach(tagId: String) async throws {
        try await joinRepo.attachTag(captureId: captureId, tagId: tagId)
    }

    func detach(tagId: String) async throws {
        try await joinRepo.detachTag(captureId: captureId, tagId: tagId)
    }
}

/// Tag-name normalization mirroring Android `normalizeTagName`:
/// lowercase, hyphens preserved, non-alphanumerics → hyphen,
/// consecutive hyphens collapsed, trimmed, 32-char cap.
func normalizeTagName(_ raw: String) -> String {
    let lowered = raw
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
    var out = ""
    var prevHyphen = false
    for ch in lowered {
        let isWord = ch.isLetter || ch.isNumber
        if isWord {
            out.append(ch)
            prevHyphen = false
        } else {
            if !prevHyphen { out.append("-") }
            prevHyphen = true
        }
    }
    let trimmed = out.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    return String(trimmed.prefix(32))
}
