/*
 * WorkspaceHomeScreen.swift
 *
 * QuickInk Workspace v1 home (Screen 1 from the design brief).
 * The canonical landing surface for the bottom-nav Workspace tab
 * — post-GA it is always the route (the legacy rollout flag has
 * been retired).
 *
 * Composition (top → bottom):
 *   - Header   — "Workspace" title + folder count + bell + avatar.
 *   - Search   — pill that routes to the existing search.
 *   - Continue — dark hero card with the most-recently-opened
 *                capture; absent when the user hasn't opened any.
 *   - Smart    — horizontal strip of smart collection cards
 *                (Workspace v1 ships with the seeded "Needs review").
 *   - Folders  — list of active folders, color-coded, with item
 *                counts.
 *   - Tags     — top-10 tag chips by usage; "Browse all" routes to
 *                the tag library.
 *   - Bottom nav — Workspace tab active.
 *
 * Mirror of `WorkspaceHomeScreen.kt` in QuickInk's Android target.
 *
 * Deferred:
 *   - AI bar (Phase E — out of v1).
 *   - Pinned intelligence (Phase E — out of v1).
 *   - Up-next suggestions strip (Phase B.x heuristic candidate).
 */

import SwiftUI
import Combine
import GRDB
import ReleafCoreData
import ReleafCoreDesignSystem

@MainActor
public struct WorkspaceHomeScreen: View {

    public let userId: String
    public let onOpenSearch: () -> Void
    public let onOpenFolder: (FolderEntity) -> Void
    public let onOpenContinue: (CaptureSummary) -> Void
    public let onOpenProfile: () -> Void
    public let onOpenTag: (TagEntity) -> Void
    public let onOpenSmartCollection: (SmartCollectionEntity) -> Void
    public let onBrowseTags: () -> Void
    public let onOpenLocation: (LocationEntity) -> Void
    public let onOpenPerson: (PersonEntity) -> Void

    @StateObject private var viewModel: WorkspaceHomeViewModel
    @State private var folderEditorMode: FolderEditorMode? = nil
    @State private var folderActionsTarget: FolderEntity? = nil
    @State private var folderDeleteTarget: FolderEntity? = nil
    @State private var showSmartEditor: Bool = false
    @State private var confirmDeleteCollection: SmartCollectionEntity? = nil
    @State private var actionsForCollection: SmartCollectionEntity? = nil
    @State private var editCollection: SmartCollectionEntity? = nil
    @State private var locationEditorMode: LocationEditorMode? = nil
    @State private var personEditorMode: PersonEditorMode? = nil

    public init(
        userId: String,
        onOpenSearch: @escaping () -> Void,
        onOpenFolder: @escaping (FolderEntity) -> Void,
        onOpenContinue: @escaping (CaptureSummary) -> Void,
        onOpenProfile: @escaping () -> Void,
        onOpenTag: @escaping (TagEntity) -> Void,
        onOpenSmartCollection: @escaping (SmartCollectionEntity) -> Void,
        onBrowseTags: @escaping () -> Void,
        onOpenLocation: @escaping (LocationEntity) -> Void = { _ in },
        onOpenPerson: @escaping (PersonEntity) -> Void = { _ in }
    ) {
        self.userId = userId
        self.onOpenSearch = onOpenSearch
        self.onOpenFolder = onOpenFolder
        self.onOpenContinue = onOpenContinue
        self.onOpenProfile = onOpenProfile
        self.onOpenTag = onOpenTag
        self.onOpenSmartCollection = onOpenSmartCollection
        self.onBrowseTags = onBrowseTags
        self.onOpenLocation = onOpenLocation
        self.onOpenPerson = onOpenPerson
        _viewModel = StateObject(wrappedValue: WorkspaceHomeViewModel(userId: userId))
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                header
                    .padding(.horizontal, AppSpacing.s4)

                searchBar
                    .padding(.horizontal, AppSpacing.s4)

                if let hero = viewModel.recentlyOpened.first {
                    let rest = Array(viewModel.recentlyOpened.dropFirst())
                    recentsCarousel(hero: hero, rest: rest)
                }

                smartCollectionsStrip

                sectionDivider

                foldersSection
                    .padding(.horizontal, AppSpacing.s4)

                sectionDivider

                placesSection
                    .padding(.horizontal, AppSpacing.s4)

                sectionDivider

                peopleSection
                    .padding(.horizontal, AppSpacing.s4)

                sectionDivider

                tagsSection
                    .padding(.horizontal, AppSpacing.s4)

                Spacer(minLength: 120)
            }
            .padding(.top, AppSpacing.s3)
        }
        .background(QuickInkColors.bg)
        .onAppear { viewModel.start() }
        .sheet(item: $folderActionsTarget) { folder in
            FolderActionSheet(
                folder:        folder,
                onRename:      {
                    folderActionsTarget = nil
                    folderEditorMode = .edit(folder: folder, kind: .rename)
                },
                onChangeColor: {
                    folderActionsTarget = nil
                    folderEditorMode = .edit(folder: folder, kind: .recolor)
                },
                onDelete:      {
                    folderActionsTarget = nil
                    folderDeleteTarget = folder
                }
            )
            .presentationDetents([.medium])
        }
        .sheet(item: $folderEditorMode) { mode in
            FolderEditorView(
                mode:      mode,
                onSubmit:  { name, color in
                    Task {
                        switch mode {
                        case .create:
                            _ = try? await viewModel.createFolder(name: name, color: color)
                        case .edit(let folder, let kind):
                            switch kind {
                            case .rename:
                                if !name.isEmpty, name != folder.name {
                                    try? await viewModel.renameFolder(id: folder.id, newName: name)
                                }
                            case .recolor:
                                if color != folder.color {
                                    try? await viewModel.setFolderColor(id: folder.id, color: color)
                                }
                            }
                        }
                    }
                    folderEditorMode = nil
                },
                onCancel:  { folderEditorMode = nil }
            )
            .presentationDetents([.medium])
        }
        .sheet(item: $actionsForCollection) { collection in
            smartCollectionActionSheet(collection)
                .presentationDetents([.height(200)])
        }
        .sheet(item: $editCollection) { collection in
            let initialInput = SmartCollectionRuleInput.fromClauses(
                SmartCollectionRule.decode(collection.ruleJson)
            )
            SmartCollectionEditorView(
                folders:      viewModel.folders,
                tags:         viewModel.tags,
                initialName:  collection.name,
                initialInput: initialInput,
                initialIcon:  collection.icon,
                initialColor: collection.color,
                isEdit:       true,
                onSubmit: { name, ruleInput, icon, color in
                    let target = collection
                    Task {
                        await updateSmartCollection(
                            target: target,
                            name:   name,
                            input:  ruleInput,
                            icon:   icon,
                            color:  color
                        )
                        editCollection = nil
                    }
                },
                onCancel: { editCollection = nil }
            )
            .presentationDetents([.large])
        }
        .alert(
            "Delete \"\(confirmDeleteCollection?.name ?? "")\"?",
            isPresented: Binding(
                get: { confirmDeleteCollection != nil },
                set: { if !$0 { confirmDeleteCollection = nil } }
            ),
            presenting: confirmDeleteCollection
        ) { collection in
            Button("Delete", role: .destructive) {
                let id = collection.id
                Task {
                    let now = IsoClock.nowIso()
                    let dbQueue = QuickInkDatabase.shared.dbQueue
                    try? await dbQueue.write { db in
                        try db.execute(sql: """
                            UPDATE smart_collections
                            SET deleted_at = ?, updated_at = ?, dirty = 1
                            WHERE id = ?
                            """, arguments: [now, now, id])
                    }
                    confirmDeleteCollection = nil
                }
            }
            Button("Cancel", role: .cancel) { confirmDeleteCollection = nil }
        } message: { _ in
            Text("The rule is removed. Captures aren't deleted — the collection is just a saved view.")
        }
        .sheet(isPresented: $showSmartEditor) {
            SmartCollectionEditorView(
                folders: viewModel.folders,
                tags:    viewModel.tags,
                onSubmit: { name, ruleInput, icon, color in
                    Task {
                        await saveSmartCollection(
                            name:  name,
                            input: ruleInput,
                            icon:  icon,
                            color: color
                        )
                        showSmartEditor = false
                    }
                },
                onCancel: { showSmartEditor = false }
            )
            .presentationDetents([.large])
        }
        .alert(
            "Delete \"\(folderDeleteTarget?.name ?? "")\"?",
            isPresented: Binding(
                get: { folderDeleteTarget != nil },
                set: { if !$0 { folderDeleteTarget = nil } }
            ),
            presenting: folderDeleteTarget
        ) { folder in
            Button("Delete", role: .destructive) {
                Task { try? await viewModel.softDeleteFolder(folderId: folder.id) }
                folderDeleteTarget = nil
            }
            Button("Cancel", role: .cancel) { folderDeleteTarget = nil }
        } message: { folder in
            let count = viewModel.folderCaptureCounts[folder.id] ?? 0
            Text(
                count == 0
                    ? "The folder is empty. Deleting it can't be undone."
                    : "\(count) capture\(count == 1 ? "" : "s") will move to Unsorted."
            )
        }
    }

    @ViewBuilder
    private func smartCollectionActionSheet(_ collection: SmartCollectionEntity) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Image(systemName: "sparkles")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(QuickInkColors.accent)
                Text(collection.name)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
            }
            .padding(.horizontal, AppSpacing.s4)
            .padding(.vertical, AppSpacing.s3)
            Divider().background(QuickInkColors.borderSoft)
            Button(action: {
                editCollection = collection
                actionsForCollection = nil
            }) {
                HStack {
                    Text("Edit").foregroundColor(QuickInkColors.ink).font(.system(size: 15))
                    Spacer()
                }
                .padding(.horizontal, AppSpacing.s4)
                .padding(.vertical, 14)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button(action: {
                confirmDeleteCollection = collection
                actionsForCollection = nil
            }) {
                HStack {
                    Text("Delete").foregroundColor(QuickInkColors.danger).font(.system(size: 15))
                    Spacer()
                }
                .padding(.horizontal, AppSpacing.s4)
                .padding(.vertical, 14)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Spacer(minLength: 12)
        }
        .background(QuickInkColors.surface)
    }

    private func updateSmartCollection(
        target: SmartCollectionEntity,
        name: String,
        input: SmartCollectionRuleInput,
        icon: String?,
        color: String?
    ) async {
        let clauses = input.toClauses()
        guard !clauses.isEmpty else { return }
        let now = IsoClock.nowIso()
        let dbQueue = QuickInkDatabase.shared.dbQueue
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let newName = trimmed.isEmpty ? target.name : trimmed
        let ruleJson = SmartCollectionRule.encode(clauses)
        try? await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE smart_collections
                SET name = ?, rule_json = ?, icon = ?, color = ?,
                    updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [newName, ruleJson, icon, color, now, target.id])
        }
    }

    private func saveSmartCollection(
        name: String,
        input: SmartCollectionRuleInput,
        icon: String?,
        color: String?
    ) async {
        let clauses = input.toClauses()
        guard !clauses.isEmpty else { return }
        let now = IsoClock.nowIso()
        let dbQueue = QuickInkDatabase.shared.dbQueue
        let nextPos = (try? await dbQueue.read { db in
            (try Int.fetchOne(db, sql: """
                SELECT COALESCE(MAX(position), -1) + 1
                FROM smart_collections
                WHERE user_id = ? AND deleted_at IS NULL
                """, arguments: [userId])) ?? 0
        }) ?? 0
        let row = SmartCollectionEntity(
            id:        Uuidv7.generate(),
            userId:    userId,
            name:      name.isEmpty ? "Untitled collection" : name,
            icon:      icon,
            color:     color,
            ruleJson:  SmartCollectionRule.encode(clauses),
            position:  nextPos,
            isSeeded:  false,
            createdAt: now,
            updatedAt: now,
            dirty:     true,
        )
        try? await dbQueue.write { db in try row.insert(db) }
    }

    // MARK: - Section divider

    private var sectionDivider: some View {
        Rectangle()
            .fill(QuickInkColors.border)
            .frame(height: 1)
            .padding(.horizontal, AppSpacing.s4)
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Workspace")
                    .font(.system(size: 30, weight: .medium))
                    .foregroundColor(QuickInkColors.ink)
                Text("\(viewModel.folders.count) \(viewModel.folders.count == 1 ? "folder" : "folders")")
                    .font(.system(size: 12.5))
                    .foregroundColor(QuickInkColors.muted)
            }
            Spacer()
            HStack(spacing: AppSpacing.s2) {
                Button(action: { /* notifications — out of scope */ }) {
                    Image(systemName: "bell")
                        .font(.system(size: 19))
                        .foregroundColor(QuickInkColors.ink)
                        .frame(width: 36, height: 36)
                }
                Button(action: onOpenProfile) {
                    Circle()
                        .fill(QuickInkColors.accent)
                        .frame(width: 36, height: 36)
                        .overlay(
                            Text("A")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(.white)
                        )
                }
            }
        }
    }

    // MARK: - Search bar

    private var searchBar: some View {
        Button(action: onOpenSearch) {
            HStack(spacing: AppSpacing.s2) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 14))
                    .foregroundColor(QuickInkColors.muted)
                Text("Search documents, tags…")
                    .font(.system(size: 13))
                    .foregroundColor(QuickInkColors.muted)
                Spacer()
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 14))
                    .foregroundColor(QuickInkColors.inkSoft)
            }
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, 12)
            .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Continue card

    private func continueCard(_ capture: CaptureSummary) -> some View {
        let title = capture.title?.isEmpty == false ? capture.title! : "Untitled scan"
        let page = capture.lastOpenedPage ?? 1
        let total = max(capture.pageCount, 1)
        let frac = min(max(Double(page) / Double(total), 0), 1)

        return Button(action: { onOpenContinue(capture) }) {
            HStack(spacing: AppSpacing.s3) {
                ContinueCardThumbnail(previewUri: capture.previewUri)

                VStack(alignment: .leading, spacing: 4) {
                    Text("CONTINUE")
                        .font(.system(size: 10, weight: .semibold))
                        .tracking(1.2)
                        .foregroundColor(Color(white: 0.85))
                    Text(title)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundColor(Color(red: 0.96, green: 0.94, blue: 0.90))
                        .lineLimit(1)
                    Text("Page \(page) of \(total)")
                        .font(.system(size: 11.5))
                        .foregroundColor(Color(red: 0.96, green: 0.94, blue: 0.90).opacity(0.8))
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 999)
                                .fill(Color(red: 0.96, green: 0.94, blue: 0.90).opacity(0.2))
                            RoundedRectangle(cornerRadius: 999)
                                .fill(QuickInkColors.accent)
                                .frame(width: geo.size.width * frac)
                        }
                    }
                    .frame(height: 3)
                }

                Circle()
                    .fill(QuickInkColors.accent)
                    .frame(width: 32, height: 32)
                    .overlay(
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.white)
                    )
            }
            .padding(AppSpacing.s3)
            .frame(width: 280, height: recentsCarouselHeight)
            .background(QuickInkColors.ink, in: RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(.plain)
    }

    /// Shared height for every card in the Recents carousel so the
    /// hero and the thumbnail cards align as a single row regardless
    /// of content. Matches the Continue card's natural height
    /// (thumbnail 70 + AppSpacing.s3 padding × 2).
    private var recentsCarouselHeight: CGFloat { 94 }

    // MARK: - Recently-opened strip

    /// Single horizontal carousel that combines the Continue hero
    /// with recents thumbnails so the user can swipe horizontally
    /// instead of scrolling the page to reach older items. The hero
    /// stays wider (~280pt) so it still reads as the "primary" pick
    /// on first paint; the rest are 100pt thumbnail cards.
    private func recentsCarousel(hero: CaptureSummary, rest: [CaptureSummary]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .center, spacing: 10) {
                continueCard(hero)
                ForEach(rest) { cap in
                    recentDocCard(cap)
                }
            }
            .padding(.horizontal, AppSpacing.s4)
        }
    }

    private func recentDocCard(_ capture: CaptureSummary) -> some View {
        let title = capture.title?.isEmpty == false ? capture.title! : "Untitled scan"
        let page  = capture.lastOpenedPage ?? 1
        let total = max(capture.pageCount, 1)
        let frac  = min(max(Double(page) / Double(total), 0), 1)

        return Button(action: { onOpenContinue(capture) }) {
            VStack(alignment: .leading, spacing: 4) {
                RecentDocThumbnail(previewUri: capture.previewUri)

                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(QuickInkColors.borderSoft)
                        .frame(height: 3)
                    GeometryReader { geo in
                        Capsule()
                            .fill(QuickInkColors.accent)
                            .frame(width: geo.size.width * frac, height: 3)
                    }
                    .frame(height: 3)
                }
                .frame(width: 100, height: 3)

                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(QuickInkColors.ink)
                    .lineLimit(1)
                Text("p. \(page) / \(total)")
                    .font(.system(size: 10.5))
                    .foregroundColor(QuickInkColors.muted)
                    .lineLimit(1)
            }
            .frame(width: 100, height: recentsCarouselHeight, alignment: .topLeading)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Smart collections strip

    private var smartCollectionsStrip: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            HStack {
                Text("Smart collections")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
                Button(action: { showSmartEditor = true }) {
                    Text("+ NEW")
                        .font(.system(size: 10.5, weight: .semibold))
                        .tracking(1.2)
                        .foregroundColor(QuickInkColors.accent)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, AppSpacing.s4)

            if viewModel.smartCollections.isEmpty {
                Text("Save tag combinations or create a rule-based view.")
                    .font(.system(size: 11.5))
                    .italic()
                    .foregroundColor(QuickInkColors.muted)
                    .padding(.horizontal, AppSpacing.s4)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(viewModel.smartCollections) { sc in
                            smartCollectionCard(sc)
                                .onLongPressGesture { actionsForCollection = sc }
                        }
                    }
                    .padding(.horizontal, AppSpacing.s4)
                }
            }
        }
    }

    private func smartCollectionCard(_ sc: SmartCollectionEntity) -> some View {
        let tint = colorFromHex(sc.color) ?? QuickInkColors.accent
        return Button(action: { onOpenSmartCollection(sc) }) {
            VStack(alignment: .leading, spacing: 4) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(tint.opacity(0.18))
                        .frame(width: 28, height: 28)
                    Image(systemName: iconSymbolForSlug(sc.icon))
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(tint)
                }
                Spacer().frame(height: 2)
                Text(sc.name)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                    .lineLimit(1)
                Text("Smart")
                    .font(.system(size: 11))
                    .foregroundColor(QuickInkColors.muted)
            }
            .frame(width: 142, alignment: .leading)
            .padding(12)
            .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Folders

    private var foldersSection: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            HStack {
                Text("Folders")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
                Button(action: { folderEditorMode = .create }) {
                    Text("NEW FOLDER")
                        .font(.system(size: 10.5, weight: .semibold))
                        .tracking(1.2)
                        .foregroundColor(QuickInkColors.accent)
                }
                .buttonStyle(.plain)
            }

            if viewModel.folders.isEmpty {
                Text("No folders yet.")
                    .font(.system(size: 12.5))
                    .foregroundColor(QuickInkColors.muted)
                    .padding(.vertical, AppSpacing.s3)
            } else {
                ForEach(viewModel.folders) { folder in
                    folderRow(folder)
                }
            }
        }
    }

    private func folderRow(_ folder: FolderEntity) -> some View {
        let count = viewModel.folderCaptureCounts[folder.id] ?? 0
        let newCount = viewModel.folderNewCounts[folder.id] ?? 0
        return Button(action: { onOpenFolder(folder) }) {
            HStack(spacing: AppSpacing.s3) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(colorFromHex(folder.color) ?? QuickInkColors.accent)
                    .frame(width: 24, height: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(folder.name)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(QuickInkColors.ink)
                    HStack(spacing: 6) {
                        Text("\(count) \(count == 1 ? "item" : "items")")
                            .font(.system(size: 11.5))
                            .foregroundColor(QuickInkColors.muted)
                        if newCount > 0 {
                            HStack(spacing: 4) {
                                Circle()
                                    .fill(QuickInkColors.accent)
                                    .frame(width: 5, height: 5)
                                Text("\(newCount) new")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundColor(QuickInkColors.accentDeep)
                            }
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(QuickInkColors.accentSoft,
                                        in: RoundedRectangle(cornerRadius: 4))
                        }
                    }
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.muted)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onLongPressGesture { folderActionsTarget = folder }
    }

    // MARK: - Tags

    private var tagsSection: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            HStack {
                Text("Tags")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
                Button(action: onBrowseTags) {
                    Text("BROWSE ALL")
                        .font(.system(size: 10.5, weight: .semibold))
                        .tracking(1.2)
                        .foregroundColor(QuickInkColors.accent)
                }
                .buttonStyle(.plain)
            }

            let ranked = viewModel.rankedTags(limit: 10)
            if ranked.isEmpty {
                Text("No tags yet.")
                    .font(.system(size: 12.5))
                    .foregroundColor(QuickInkColors.muted)
            } else {
                FlowChips(tags: ranked) { (tag, count) in
                    tagChip(tag: tag, count: count)
                }
            }
        }
    }

    private func tagChip(tag: TagEntity, count: Int) -> some View {
        Button(action: { onOpenTag(tag) }) {
            HStack(spacing: 4) {
                Text("#")
                    .font(.system(size: 11.5, weight: .bold))
                    .foregroundColor(QuickInkColors.accent)
                Text(tag.name)
                    .font(.system(size: 11.5))
                    .foregroundColor(QuickInkColors.inkSoft)
                if count > 0 {
                    Text("\(count)")
                        .font(.system(size: 10))
                        .foregroundColor(QuickInkColors.muted)
                }
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 5)
            .background(QuickInkColors.surface, in: Capsule())
            .overlay(Capsule().stroke(QuickInkColors.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Places
    //
    // Mirror of Android's `LocationsSection` (renamed to "Places" in
    // the UI; the storage layer still uses the legacy `location` name
    // for wire-format parity).

    private var placesSection: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            HStack {
                Text("Places")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
                Button(action: { locationEditorMode = .create }) {
                    Text("NEW PLACE")
                        .font(.system(size: 10.5, weight: .semibold))
                        .tracking(1.2)
                        .foregroundColor(QuickInkColors.accent)
                }
                .buttonStyle(.plain)
            }

            if viewModel.locations.isEmpty {
                Text("No places yet.")
                    .font(.system(size: 12.5))
                    .foregroundColor(QuickInkColors.muted)
                    .padding(.vertical, AppSpacing.s3)
            } else {
                ForEach(viewModel.locations) { loc in
                    placeRow(loc)
                }
            }
        }
        .sheet(item: $locationEditorMode) { mode in
            LocationEditorView(
                mode:     mode,
                onSubmit: { name, address, latitude, longitude in
                    Task {
                        switch mode {
                        case .create:
                            _ = try? await viewModel.createLocation(
                                name:      name,
                                address:   address?.isEmpty == true ? nil : address,
                                latitude:  latitude,
                                longitude: longitude
                            )
                        case .edit(let loc):
                            if !name.isEmpty, name != loc.name {
                                try? await viewModel.renameLocation(id: loc.id, newName: name)
                            }
                            let nextAddress = address?.isEmpty == true ? nil : address
                            if nextAddress != loc.address ||
                               latitude    != loc.latitude ||
                               longitude   != loc.longitude {
                                try? await viewModel.setLocationCoordinates(
                                    id:        loc.id,
                                    latitude:  latitude,
                                    longitude: longitude,
                                    address:   nextAddress
                                )
                            }
                        }
                    }
                    locationEditorMode = nil
                },
                onCancel: { locationEditorMode = nil }
            )
            .presentationDetents([.medium])
        }
    }

    private func placeRow(_ location: LocationEntity) -> some View {
        let count = viewModel.locationCounts[location.id] ?? 0
        return Button(action: { onOpenLocation(location) }) {
            HStack(spacing: AppSpacing.s3) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(colorFromHex(location.color) ?? QuickInkColors.accentSoft)
                    .frame(width: 24, height: 24)
                    .overlay(
                        Image(systemName: "mappin.and.ellipse")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(QuickInkColors.accent)
                    )
                VStack(alignment: .leading, spacing: 2) {
                    Text(location.name)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(QuickInkColors.ink)
                    if let address = location.address, !address.isEmpty {
                        Text(address)
                            .font(.system(size: 11.5))
                            .foregroundColor(QuickInkColors.muted)
                            .lineLimit(1)
                    } else {
                        Text("\(count) \(count == 1 ? "item" : "items")")
                            .font(.system(size: 11.5))
                            .foregroundColor(QuickInkColors.muted)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.muted)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onLongPressGesture { locationEditorMode = .edit(location: location) }
    }

    // MARK: - People

    private var peopleSection: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            HStack {
                Text("People")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
                Button(action: { personEditorMode = .create }) {
                    Text("NEW PERSON")
                        .font(.system(size: 10.5, weight: .semibold))
                        .tracking(1.2)
                        .foregroundColor(QuickInkColors.accent)
                }
                .buttonStyle(.plain)
            }

            if viewModel.people.isEmpty {
                Text("No people yet.")
                    .font(.system(size: 12.5))
                    .foregroundColor(QuickInkColors.muted)
                    .padding(.vertical, AppSpacing.s3)
            } else {
                ForEach(viewModel.people) { person in
                    personRow(person)
                }
            }
        }
        .sheet(item: $personEditorMode) { mode in
            PersonEditorView(
                mode:     mode,
                onSubmit: { name, phone, email, lookupKey, photoUri in
                    Task {
                        switch mode {
                        case .create:
                            _ = try? await viewModel.createPerson(
                                name:             name,
                                phone:            phone?.isEmpty == true ? nil : phone,
                                email:            email?.isEmpty == true ? nil : email,
                                contactLookupKey: lookupKey,
                                contactPhotoUri:  photoUri
                            )
                        case .edit(let person):
                            if !name.isEmpty, name != person.name {
                                try? await viewModel.renamePerson(id: person.id, newName: name)
                            }
                            let nextPhone     = phone?.isEmpty == true ? nil : phone
                            let nextEmail     = email?.isEmpty == true ? nil : email
                            if nextPhone     != person.contactPhone ||
                               nextEmail     != person.contactEmail ||
                               lookupKey     != person.contactLookupKey ||
                               photoUri      != person.contactPhotoUri {
                                try? await viewModel.setPersonContact(
                                    id:        person.id,
                                    lookupKey: lookupKey,
                                    phone:     nextPhone,
                                    email:     nextEmail,
                                    photoUri:  photoUri
                                )
                            }
                        }
                    }
                    personEditorMode = nil
                },
                onCancel: { personEditorMode = nil }
            )
            .presentationDetents([.medium])
        }
    }

    private func personRow(_ person: PersonEntity) -> some View {
        let count = viewModel.personCounts[person.id] ?? 0
        return Button(action: { onOpenPerson(person) }) {
            HStack(spacing: AppSpacing.s3) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(colorFromHex(person.color) ?? QuickInkColors.accentSoft)
                    .frame(width: 24, height: 24)
                    .overlay(
                        Image(systemName: "person.fill")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(QuickInkColors.accent)
                    )
                VStack(alignment: .leading, spacing: 2) {
                    Text(person.name)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(QuickInkColors.ink)
                    if let phone = person.contactPhone, !phone.isEmpty {
                        Text(phone)
                            .font(.system(size: 11.5))
                            .foregroundColor(QuickInkColors.muted)
                            .lineLimit(1)
                    } else if let email = person.contactEmail, !email.isEmpty {
                        Text(email)
                            .font(.system(size: 11.5))
                            .foregroundColor(QuickInkColors.muted)
                            .lineLimit(1)
                    } else {
                        Text("\(count) \(count == 1 ? "item" : "items")")
                            .font(.system(size: 11.5))
                            .foregroundColor(QuickInkColors.muted)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.muted)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onLongPressGesture { personEditorMode = .edit(person: person) }
    }
}

// MARK: - Helpers

/// Cheap two-line flow layout that wraps chips to the next row. iOS
/// 16+ has Layout but the chip set is bounded; falls back to LazyVGrid
/// adaptive columns for compatibility.
private struct FlowChips<Content: View>: View {
    let tags: [(TagEntity, Int)]
    let content: (TagEntity, Int) -> Content

    var body: some View {
        let columns = [GridItem(.adaptive(minimum: 80), spacing: 6)]
        LazyVGrid(columns: columns, alignment: .leading, spacing: 6) {
            ForEach(tags, id: \.0.id) { pair in
                content(pair.0, pair.1)
            }
        }
    }
}

/// Parse a "#RRGGBB" or "#RGB" string into a SwiftUI `Color`. Nil on
/// malformed input.
func colorFromHex(_ hex: String?) -> Color? {
    guard var s = hex?.uppercased(), s.hasPrefix("#") else { return nil }
    s.removeFirst()
    guard s.count == 6 || s.count == 3 else { return nil }
    var r: UInt64 = 0, g: UInt64 = 0, b: UInt64 = 0
    if s.count == 3 {
        let chars = Array(s)
        guard
            Scanner(string: "\(chars[0])\(chars[0])").scanHexInt64(&r),
            Scanner(string: "\(chars[1])\(chars[1])").scanHexInt64(&g),
            Scanner(string: "\(chars[2])\(chars[2])").scanHexInt64(&b)
        else { return nil }
    } else {
        let chars = Array(s)
        guard
            Scanner(string: String(chars[0...1])).scanHexInt64(&r),
            Scanner(string: String(chars[2...3])).scanHexInt64(&g),
            Scanner(string: String(chars[4...5])).scanHexInt64(&b)
        else { return nil }
    }
    return Color(red: Double(r) / 255.0, green: Double(g) / 255.0, blue: Double(b) / 255.0)
}

// MARK: - Folder editor modal kinds (Phase B.1)

/// Discriminates between the create flow and an edit-in-place flow
/// for the [FolderEditorView]. Identifiable so SwiftUI's `.sheet(item:)`
/// can drive presentation.
enum FolderEditorMode: Identifiable {
    case create
    case edit(folder: FolderEntity, kind: FolderEditorKind)

    var id: String {
        switch self {
        case .create: return "create"
        case .edit(let folder, let kind): return "edit-\(folder.id)-\(kind.rawValue)"
        }
    }
}

enum FolderEditorKind: String { case rename, recolor }

/// 7-swatch palette mirror of Android's WorkspaceFolderPalette.
let workspaceFolderPalette: [String] = [
    "#E66943", "#E8AE17", "#4F9E5A", "#3A78AE", "#7A5DA8", "#C75677", "#2E8A86",
]
