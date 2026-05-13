/*
 * FolderDetailScreen.swift
 *
 * Workspace v1 Screen 2 — captures inside a folder with a tag-strip
 * filter on top. Reached by tapping a folder on the Workspace home;
 * back arrow returns. Mirror of `FolderDetailScreen.kt` in QuickInk's
 * Android target.
 */

import SwiftUI
import Combine
import GRDB
import ReleafCoreDesignSystem

@MainActor
public struct FolderDetailScreen: View {

    public let folderId: String
    public let userId: String
    public let onBack: () -> Void
    public let onOpenCapture: (CaptureSummary) -> Void
    public let onOpenSearch: () -> Void
    public let onHome: () -> Void
    public let onWorkspace: () -> Void
    public let onScan: () -> Void
    public let onSettings: () -> Void

    @StateObject private var viewModel: FolderDetailViewModel
    @State private var selectedTagId: String? = nil

    public init(
        folderId: String,
        userId: String,
        onBack: @escaping () -> Void,
        onOpenCapture: @escaping (CaptureSummary) -> Void,
        onOpenSearch: @escaping () -> Void,
        onHome: @escaping () -> Void,
        onWorkspace: @escaping () -> Void,
        onScan: @escaping () -> Void,
        onSettings: @escaping () -> Void
    ) {
        self.folderId = folderId
        self.userId = userId
        self.onBack = onBack
        self.onOpenCapture = onOpenCapture
        self.onOpenSearch = onOpenSearch
        self.onHome = onHome
        self.onWorkspace = onWorkspace
        self.onScan = onScan
        self.onSettings = onSettings
        _viewModel = StateObject(wrappedValue: FolderDetailViewModel(folderId: folderId, userId: userId))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            folderBar
            searchBar
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, QuickInkSpacing.s2)

            if !viewModel.tagsInFolder.isEmpty {
                tagStrip
                    .padding(.top, QuickInkSpacing.s3)
            }

            resultEyebrow
                .padding(.top, QuickInkSpacing.s2)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    let captures = filteredCaptures
                    if captures.isEmpty {
                        Text(selectedTagId == nil
                             ? "No captures in this folder yet."
                             : "No captures match this tag in this folder.")
                            .font(.system(size: 12.5))
                            .foregroundColor(QuickInkColors.muted)
                            .padding(QuickInkSpacing.s4)
                    } else {
                        ForEach(captures) { cap in
                            docRow(cap)
                        }
                    }
                }
                .padding(.horizontal, QuickInkSpacing.s4)
            }
        }
        .background(QuickInkColors.bg)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            QuickInkBottomNavBar(
                activeTab:   .workspace,
                onHome:      onHome,
                onWorkspace: onWorkspace,
                onScan:      onScan,
                onSearch:    onOpenSearch,
                onSettings:  onSettings
            )
        }
        .onAppear { viewModel.start() }
    }

    private var filteredCaptures: [CaptureSummary] {
        guard let tagId = selectedTagId else { return viewModel.captures }
        let allowed = Set(viewModel.captureIdsForSelectedTag)
        return viewModel.captures.filter { allowed.contains($0.id) }
    }

    // MARK: - Folder bar

    private var folderBar: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                    .frame(width: 36, height: 36)
            }
            RoundedRectangle(cornerRadius: 6)
                .fill(colorFromHex(viewModel.folder?.color) ?? QuickInkColors.accent)
                .frame(width: 24, height: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(viewModel.folder?.name ?? "…")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Text("\(filteredCaptures.count) \(filteredCaptures.count == 1 ? "item" : "items")")
                    .font(.system(size: 11))
                    .foregroundColor(QuickInkColors.muted)
            }
            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
    }

    private var searchBar: some View {
        Button(action: onOpenSearch) {
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(QuickInkColors.muted)
                Text("Search in folder")
                    .font(.system(size: 13))
                    .foregroundColor(QuickInkColors.muted)
                Spacer()
            }
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, 12)
            .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(QuickInkColors.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var tagStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                pill(label: "All tags", icon: "tag.fill", active: selectedTagId == nil) {
                    selectedTagId = nil
                }
                ForEach(viewModel.tagsInFolder) { tag in
                    pill(label: "#\(tag.name)", icon: nil, active: selectedTagId == tag.id) {
                        selectedTagId = (selectedTagId == tag.id) ? nil : tag.id
                    }
                }
            }
            .padding(.horizontal, QuickInkSpacing.s4)
        }
    }

    private func pill(label: String, icon: String?, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 10))
                        .foregroundColor(active ? .white : QuickInkColors.accent)
                }
                Text(label)
                    .font(.system(size: 11.5))
                    .foregroundColor(active ? .white : QuickInkColors.inkSoft)
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 6)
            .background(active ? QuickInkColors.ink : QuickInkColors.surface, in: Capsule())
            .overlay(Capsule().stroke(active ? QuickInkColors.ink : QuickInkColors.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var resultEyebrow: some View {
        HStack {
            if let tagId = selectedTagId,
               let tag = viewModel.tagsInFolder.first(where: { $0.id == tagId }) {
                Text("Filtered to ")
                    .font(.system(size: 12, weight: .regular, design: .serif))
                    .italic()
                    .foregroundColor(QuickInkColors.muted) +
                Text("#\(tag.name)")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(QuickInkColors.accentDeep) +
                Text(" · \(filteredCaptures.count) docs")
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.muted)
            } else {
                Text("\(filteredCaptures.count) \(filteredCaptures.count == 1 ? "doc" : "docs")")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
            }
            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s4)
    }

    private func docRow(_ cap: CaptureSummary) -> some View {
        let title = cap.title?.isEmpty == false ? cap.title! :
            (cap.category?.isEmpty == false ? cap.category! : "Untitled scan")
        let tags = (viewModel.captureTagsById[cap.id] ?? []).prefix(3)
        let overflow = max(0, (viewModel.captureTagsById[cap.id]?.count ?? 0) - 3)
        return Button(action: { onOpenCapture(cap) }) {
            HStack(alignment: .top, spacing: QuickInkSpacing.s3) {
                WorkspaceDocThumbnail(previewUri: cap.previewUri)
                VStack(alignment: .leading, spacing: 5) {
                    Text(title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(QuickInkColors.ink)
                        .lineLimit(1)
                    if !tags.isEmpty || overflow > 0 {
                        HStack(spacing: 4) {
                            ForEach(Array(tags), id: \.self) { name in
                                tagChip(name: name, isOverflow: false)
                            }
                            if overflow > 0 {
                                tagChip(name: "+\(overflow)", isOverflow: true)
                            }
                        }
                    }
                    Text("\(cap.pageCount) \(cap.pageCount == 1 ? "page" : "pages") · \(String(cap.createdAt.prefix(10)))")
                        .font(.system(size: 11.5))
                        .foregroundColor(QuickInkColors.muted)
                }
                Spacer()
            }
            .padding(.vertical, 13)
            .overlay(
                Rectangle()
                    .fill(QuickInkColors.borderSoft)
                    .frame(height: 1),
                alignment: .bottom
            )
        }
        .buttonStyle(.plain)
    }

    private func tagChip(name: String, isOverflow: Bool) -> some View {
        let bg = isOverflow ? QuickInkColors.borderSoft : QuickInkColors.accentSoft
        let fg = isOverflow ? QuickInkColors.inkSoft    : QuickInkColors.accentDeep
        return HStack(spacing: 1) {
            if !isOverflow {
                Text("#")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(fg)
            }
            Text(name)
                .font(.system(size: 10.5))
                .foregroundColor(fg)
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .background(bg, in: RoundedRectangle(cornerRadius: 4))
    }
}

@MainActor
final class FolderDetailViewModel: ObservableObject {

    @Published private(set) var folder: FolderEntity? = nil
    @Published private(set) var captures: [CaptureSummary] = []
    @Published private(set) var tagsInFolder: [TagEntity] = []
    @Published private(set) var captureIdsForSelectedTag: [String] = []
    @Published private(set) var captureTagsById: [String: [String]] = [:]

    private let folderId: String
    private let userId: String
    private let dbQueue: DatabaseQueue
    private var cancellables: Set<AnyCancellable> = []

    init(folderId: String, userId: String, database: QuickInkDatabase = .shared) {
        self.folderId = folderId
        self.userId = userId
        self.dbQueue = database.dbQueue
    }

    func start() {
        guard cancellables.isEmpty else { return }

        // Folder row
        ValueObservation.tracking { [folderId] db in
            try FolderEntity.filter(Column("id") == folderId).fetchOne(db)
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.folder = $0
        })
        .store(in: &cancellables)

        // Captures in folder
        ValueObservation.tracking { [folderId] db in
            try CaptureSummary.fetchAll(db, sql: """
                SELECT id, title, preview_uri, pdf_uri, category, page_count, created_at, source,
                       latitude, longitude, locality, sub_locality, address,
                       folder_id, last_opened_at, last_opened_page, last_opened_device
                FROM captures
                WHERE folder_id = ? AND deleted_at IS NULL
                ORDER BY created_at DESC
                """, arguments: [folderId])
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.captures = $0
        })
        .store(in: &cancellables)

        // Distinct tags actually present on captures in this folder
        ValueObservation.tracking { [folderId] db in
            let ids = try String.fetchAll(db, sql: """
                SELECT DISTINCT capture_tags.tag_id FROM capture_tags
                JOIN captures ON captures.id = capture_tags.capture_id
                WHERE captures.folder_id = ?
                  AND captures.deleted_at IS NULL
                  AND capture_tags.deleted_at IS NULL
                """, arguments: [folderId])
            if ids.isEmpty { return [TagEntity]() }
            let placeholders = ids.map { _ in "?" }.joined(separator: ",")
            return try TagEntity.fetchAll(db, sql: """
                SELECT * FROM tags
                WHERE id IN (\(placeholders))
                  AND deleted_at IS NULL
                ORDER BY name ASC
                """, arguments: StatementArguments(ids))
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.tagsInFolder = $0
        })
        .store(in: &cancellables)

        // Capture → list-of-tag-names. Per-row Flow would scale
        // better but a single materialized map keeps the row code
        // simple at small N.
        ValueObservation.tracking { [folderId] db in
            struct Pair: Codable, FetchableRecord {
                let captureId: String
                let name: String
                enum CodingKeys: String, CodingKey {
                    case captureId = "capture_id"
                    case name
                }
            }
            let pairs = try Pair.fetchAll(db, sql: """
                SELECT capture_tags.capture_id, tags.name
                FROM capture_tags
                JOIN tags     ON tags.id = capture_tags.tag_id
                JOIN captures ON captures.id = capture_tags.capture_id
                WHERE captures.folder_id = ?
                  AND captures.deleted_at IS NULL
                  AND capture_tags.deleted_at IS NULL
                  AND tags.deleted_at IS NULL
                ORDER BY capture_tags.created_at ASC
                """, arguments: [folderId])
            var out: [String: [String]] = [:]
            for p in pairs { out[p.captureId, default: []].append(p.name) }
            return out
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.captureTagsById = $0
        })
        .store(in: &cancellables)
    }
}
