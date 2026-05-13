/*
 * TagLibraryScreen.swift
 *
 * Workspace v1 Screen 4 — tag library + intersect builder.
 * Reached from the Workspace home's "BROWSE ALL" tag link.
 *
 * Mirror of `TagLibraryScreen.kt` in QuickInk's Android target.
 */

import SwiftUI
import Combine
import GRDB
import ReleafCoreData
import ReleafCoreDesignSystem

@MainActor
public struct TagLibraryScreen: View {

    public let userId: String
    public let onBack: () -> Void
    public let onOpenTag: (TagEntity) -> Void
    public let onOpenSearch: () -> Void
    public let onHome: () -> Void
    public let onWorkspace: () -> Void
    public let onScan: () -> Void
    public let onSettings: () -> Void

    @StateObject private var viewModel: TagLibraryViewModel
    @State private var query: String = ""
    @State private var picked: [TagEntity] = []
    @State private var showAddSheet: Bool = false

    // Phase D.1 — tag CRUD modal state.
    @State private var actionsForTag: TagEntity? = nil
    @State private var renameTarget: TagEntity? = nil
    @State private var deleteTarget: TagEntity? = nil
    @State private var showCreate: Bool = false
    @State private var renameDraft: String = ""
    @State private var createDraft: String = ""
    /// Phase D.2 — save the in-progress intersection as a new
    /// smart collection. Non-nil → prompt is up.
    @State private var saveAsCollectionPicked: [TagEntity] = []
    @State private var saveAsCollectionDraft: String = ""

    public init(
        userId: String,
        onBack: @escaping () -> Void,
        onOpenTag: @escaping (TagEntity) -> Void,
        onOpenSearch: @escaping () -> Void,
        onHome: @escaping () -> Void,
        onWorkspace: @escaping () -> Void,
        onScan: @escaping () -> Void,
        onSettings: @escaping () -> Void
    ) {
        self.userId = userId
        self.onBack = onBack
        self.onOpenTag = onOpenTag
        self.onOpenSearch = onOpenSearch
        self.onHome = onHome
        self.onWorkspace = onWorkspace
        self.onScan = onScan
        self.onSettings = onSettings
        _viewModel = StateObject(wrappedValue: TagLibraryViewModel(userId: userId))
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                header
                searchField
                intersectBuilder
                mostUsedHeader
                tagGrid
                Spacer(minLength: 120)
            }
            .padding(.top, QuickInkSpacing.s2)
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
        .onChange(of: picked) { _ in
            viewModel.updateIntersect(picked: picked)
        }
        .sheet(isPresented: $showAddSheet) {
            tagAddSheet
                .presentationDetents([.medium])
        }
        .sheet(item: $actionsForTag) { tag in
            tagActionSheet(tag)
                .presentationDetents([.height(220)])
        }
        .alert("Rename tag", isPresented: Binding(
            get: { renameTarget != nil },
            set: { if !$0 { renameTarget = nil } }
        ), presenting: renameTarget) { tag in
            TextField("Name", text: $renameDraft)
            Button("Cancel", role: .cancel) { renameTarget = nil }
            Button("Save") {
                let trimmed = renameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { renameTarget = nil; return }
                Task {
                    try? await TagRepository().renameAndPropagate(
                        id:      tag.id,
                        oldName: tag.name,
                        newName: trimmed,
                        userId:  userId,
                    )
                    renameTarget = nil
                }
            }
        } message: { _ in
            Text("Existing scans tagged with this name will be updated.")
        }
        .alert("Delete #\(deleteTarget?.name ?? "")?", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        ), presenting: deleteTarget) { tag in
            Button("Delete", role: .destructive) {
                Task {
                    let now = IsoClock.nowIso()
                    let dbQueue = QuickInkDatabase.shared.dbQueue
                    try? await dbQueue.write { db in
                        try db.execute(sql: """
                            UPDATE capture_tags
                            SET deleted_at = ?, updated_at = ?, dirty = 1
                            WHERE tag_id = ? AND deleted_at IS NULL
                            """, arguments: [now, now, tag.id])
                    }
                    try? await TagRepository().softDelete(id: tag.id)
                    deleteTarget = nil
                }
            }
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        } message: { tag in
            let count = viewModel.countByTagId[tag.id] ?? 0
            Text(count == 0
                 ? "The tag isn't attached to any captures. Deleting it can't be undone."
                 : "\(count) capture\(count == 1 ? "" : "s") will be untagged.")
        }
        .alert("New tag", isPresented: $showCreate) {
            TextField("Name", text: $createDraft)
            Button("Cancel", role: .cancel) { showCreate = false; createDraft = "" }
            Button("Add") {
                let normalized = normalizeTagName(createDraft)
                guard !normalized.isEmpty else {
                    showCreate = false; createDraft = ""; return
                }
                Task {
                    _ = try? await TagRepository().findOrCreate(
                        userId: userId, name: normalized,
                    )
                    showCreate = false
                    createDraft = ""
                }
            }
        }
        .alert(
            "Save as smart collection",
            isPresented: Binding(
                get: { !saveAsCollectionPicked.isEmpty },
                set: { if !$0 { saveAsCollectionPicked = [] } }
            ),
        ) {
            TextField("Name", text: $saveAsCollectionDraft)
            Button("Cancel", role: .cancel) { saveAsCollectionPicked = []; saveAsCollectionDraft = "" }
            Button("Save") {
                let tagsForRule = saveAsCollectionPicked
                let draft = saveAsCollectionDraft
                let fallback = tagsForRule.map { "#\($0.name)" }.joined(separator: " + ")
                Task {
                    let now = IsoClock.nowIso()
                    let clauses: [RuleClause] = tagsForRule.map { .tagIs(tagId: $0.id) }
                    let ruleJson = SmartCollectionRule.encode(clauses)
                    let dbQueue = QuickInkDatabase.shared.dbQueue
                    let nextPos = (try? await dbQueue.read { db in
                        (try Int.fetchOne(db, sql: """
                            SELECT COALESCE(MAX(position), -1) + 1
                            FROM smart_collections
                            WHERE user_id = ? AND deleted_at IS NULL
                            """, arguments: [userId])) ?? 0
                    }) ?? 0
                    let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    let name = trimmed.isEmpty ? fallback : trimmed
                    let row = SmartCollectionEntity(
                        id:        Uuidv7.generate(),
                        userId:    userId,
                        name:      name,
                        icon:      nil,
                        color:     nil,
                        ruleJson:  ruleJson,
                        position:  nextPos,
                        isSeeded:  false,
                        createdAt: now,
                        updatedAt: now,
                        dirty:     true,
                    )
                    try? await dbQueue.write { db in try row.insert(db) }
                    saveAsCollectionPicked = []
                    saveAsCollectionDraft = ""
                    picked = []
                }
            }
        } message: {
            Text("Saves the current tag combination as a saved view.")
        }
    }

    private var filteredTags: [TagEntity] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return viewModel.tags }
        return viewModel.tags.filter { $0.name.lowercased().contains(q) }
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                    .frame(width: 36, height: 36)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Tags")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Text("\(viewModel.tags.count) tags · \(viewModel.totalAttachments) attachments")
                    .font(.system(size: 11))
                    .foregroundColor(QuickInkColors.muted)
            }
            Spacer()
            Button(action: { createDraft = ""; showCreate = true }) {
                Image(systemName: "plus")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, QuickInkSpacing.s2)
    }

    private var searchField: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(QuickInkColors.muted)
            TextField("Search tags…", text: $query)
                .font(.system(size: 13))
                .foregroundColor(QuickInkColors.ink)
        }
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.vertical, 11)
        .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(QuickInkColors.border, lineWidth: 1))
        .padding(.horizontal, QuickInkSpacing.s4)
    }

    // MARK: - Intersect builder

    private var intersectBuilder: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text("Combine tags — show docs that have all of:")
                .font(.system(size: 11))
                .italic()
                .foregroundColor(QuickInkColors.muted)

            FlowLayoutChips {
                ForEach(Array(picked.enumerated()), id: \.offset) { idx, tag in
                    if idx > 0 {
                        Text("+").font(.system(size: 11)).foregroundColor(QuickInkColors.muted)
                    }
                    pickedChip(tag: tag)
                }
                if !picked.isEmpty {
                    Text("+").font(.system(size: 11)).foregroundColor(QuickInkColors.muted)
                }
                addChip
            }

            Divider().background(QuickInkColors.borderSoft)

            HStack {
                Text(picked.isEmpty
                     ? "Pick a tag to combine."
                     : "\(viewModel.intersectCount) matching documents")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
                if !picked.isEmpty, viewModel.intersectCount > 0 {
                    HStack(spacing: 14) {
                        Button(action: {
                            saveAsCollectionDraft = picked.map { "#\($0.name)" }.joined(separator: " + ")
                            saveAsCollectionPicked = picked
                        }) {
                            Text("SAVE")
                                .font(.system(size: 10.5, weight: .semibold))
                                .tracking(1.2)
                                .foregroundColor(QuickInkColors.accent)
                        }
                        .buttonStyle(.plain)
                        Button(action: {
                            if let first = picked.first { onOpenTag(first) }
                        }) {
                            Text("VIEW")
                                .font(.system(size: 10.5, weight: .semibold))
                                .tracking(1.2)
                                .foregroundColor(QuickInkColors.accent)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(QuickInkSpacing.s3)
        .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(QuickInkColors.border, lineWidth: 1))
        .padding(.horizontal, QuickInkSpacing.s4)
    }

    private func pickedChip(tag: TagEntity) -> some View {
        HStack(spacing: 4) {
            Text("#").font(.system(size: 11, weight: .bold)).foregroundColor(.white.opacity(0.7))
            Text(tag.name).font(.system(size: 11.5)).foregroundColor(.white)
            Button(action: { picked.removeAll { $0.id == tag.id } }) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(.white)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(QuickInkColors.ink, in: Capsule())
    }

    private var addChip: some View {
        Button(action: { showAddSheet = true }) {
            HStack(spacing: 4) {
                Image(systemName: "plus").font(.system(size: 10, weight: .semibold))
                Text("add tag").font(.system(size: 11.5))
            }
            .foregroundColor(QuickInkColors.inkSoft)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(QuickInkColors.borderSoft, in: Capsule())
            .overlay(Capsule().stroke(QuickInkColors.muted.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    /// Phase D.1 — long-press action sheet for Rename / Delete.
    private func tagActionSheet(_ tag: TagEntity) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                Text("#").font(.system(size: 16, weight: .bold)).foregroundColor(QuickInkColors.accent)
                Text(tag.name).font(.system(size: 16, weight: .semibold)).foregroundColor(QuickInkColors.ink)
                Spacer()
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.vertical, QuickInkSpacing.s3)
            Divider().background(QuickInkColors.borderSoft)
            Button(action: {
                renameDraft = tag.name
                renameTarget = tag
                actionsForTag = nil
            }) {
                HStack {
                    Text("Rename").foregroundColor(QuickInkColors.ink).font(.system(size: 15))
                    Spacer()
                }
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.vertical, 14)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button(action: {
                deleteTarget = tag
                actionsForTag = nil
            }) {
                HStack {
                    Text("Delete tag").foregroundColor(QuickInkColors.danger).font(.system(size: 15))
                    Spacer()
                }
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.vertical, 14)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Spacer(minLength: 12)
        }
        .background(QuickInkColors.surface)
    }

    private var tagAddSheet: some View {
        let candidates = viewModel.tags.filter { tag in
            !picked.contains(where: { $0.id == tag.id })
        }
        return VStack(alignment: .leading, spacing: 0) {
            Text("Pick a tag")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(QuickInkColors.ink)
                .padding(QuickInkSpacing.s4)
            Divider().background(QuickInkColors.border)
            if candidates.isEmpty {
                Text("All tags are already picked.")
                    .font(.system(size: 12.5))
                    .foregroundColor(QuickInkColors.muted)
                    .padding(QuickInkSpacing.s4)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(candidates) { tag in
                            Button(action: {
                                picked.append(tag)
                                showAddSheet = false
                            }) {
                                HStack {
                                    Text("#").foregroundColor(QuickInkColors.accent)
                                    Text(tag.name).foregroundColor(QuickInkColors.ink)
                                    Spacer()
                                }
                                .padding(.horizontal, QuickInkSpacing.s4)
                                .padding(.vertical, 12)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            Spacer(minLength: 16)
        }
        .background(QuickInkColors.surface)
    }

    // MARK: - Tag grid

    private var mostUsedHeader: some View {
        HStack {
            Image(systemName: "flame")
                .font(.system(size: 12))
                .foregroundColor(QuickInkColors.accent)
            Text("MOST USED")
                .font(.system(size: 10, weight: .bold))
                .tracking(1.4)
                .foregroundColor(QuickInkColors.muted)
            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.top, QuickInkSpacing.s2)
    }

    private var tagGrid: some View {
        LazyVGrid(
            columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)],
            spacing: 8
        ) {
            ForEach(filteredTags.sorted {
                (viewModel.countByTagId[$0.id] ?? 0) > (viewModel.countByTagId[$1.id] ?? 0)
            }) { tag in
                tagCard(tag)
                    .onTapGesture { onOpenTag(tag) }
                    .onLongPressGesture { actionsForTag = tag }
            }
        }
        .padding(.horizontal, QuickInkSpacing.s4)
    }

    private func tagCard(_ tag: TagEntity) -> some View {
        let count = viewModel.countByTagId[tag.id] ?? 0
        return VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 1) {
                Text("#")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(QuickInkColors.accent)
                Text(tag.name)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
            }
            Text("\(count) document\(count == 1 ? "" : "s")")
                .font(.system(size: 11))
                .foregroundColor(QuickInkColors.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(QuickInkColors.border, lineWidth: 1))
    }
}

/// Simple flow-layout used for the intersect builder's pill row.
/// SwiftUI's `Layout` protocol could express this more elegantly
/// on iOS 16+, but a forwarded HStack inside a wrapping ZStack is
/// plenty for the bounded pill count we render.
struct FlowLayoutChips<Content: View>: View {
    let content: () -> Content
    init(@ViewBuilder _ content: @escaping () -> Content) { self.content = content }
    var body: some View {
        HStack(spacing: 6) { content() }
            .lineLimit(nil)
            .fixedSize(horizontal: false, vertical: true)
    }
}

@MainActor
final class TagLibraryViewModel: ObservableObject {

    @Published private(set) var tags: [TagEntity] = []
    @Published private(set) var counts: [TagCount] = []
    @Published private(set) var intersectCount: Int = 0

    let userId: String
    private let tagRepo: TagRepository
    private let joinRepo: CaptureTagRepository
    private var cancellables: Set<AnyCancellable> = []
    private var intersectCancellable: AnyCancellable? = nil

    init(userId: String, database: QuickInkDatabase = .shared) {
        self.userId   = userId
        self.tagRepo  = TagRepository(database: database)
        self.joinRepo = CaptureTagRepository(database: database)
    }

    var countByTagId: [String: Int] {
        Dictionary(uniqueKeysWithValues: counts.map { ($0.tagId, $0.docCount) })
    }

    /// Imperfect total (sums attachments, not distinct captures) —
    /// fine for the header subtitle; the exact distinct count is a
    /// follow-up.
    var totalAttachments: Int { counts.reduce(0) { $0 + $1.docCount } }

    func start() {
        guard cancellables.isEmpty else { return }
        tagRepo.observe(userId: userId)
            .replaceError(with: [])
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.tags = $0 }
            .store(in: &cancellables)
        joinRepo.observeTagCounts(userId: userId)
            .replaceError(with: [])
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.counts = $0 }
            .store(in: &cancellables)
    }

    func updateIntersect(picked: [TagEntity]) {
        intersectCancellable?.cancel()
        guard !picked.isEmpty else {
            intersectCount = 0
            return
        }
        intersectCancellable = joinRepo
            .observeIntersectCount(userId: userId, tagIds: picked.map(\.id))
            .replaceError(with: 0)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.intersectCount = $0 }
    }
}
