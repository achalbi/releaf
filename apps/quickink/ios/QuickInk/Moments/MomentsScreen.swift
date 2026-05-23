/*
 * MomentsScreen.swift
 *
 * Visual memory layer for QuickInk photos and videos. This screen is
 * intentionally separate from Workspace: it favors imagery, timeline
 * browsing, and discovery shortcuts over document-folder structure.
 */

import SwiftUI
import Combine
import GRDB
import AVFoundation
import Speech

private let galleryTileGap: CGFloat = 6
private let focusedGalleryTileRadius: CGFloat = QuickInkRadius.lg

private enum MomentHomeTab {
    case timeline
    case albums
    case smartCollections
}

struct MomentsScreen: View {
    let userId: String
    let onOpenCapture: (String) -> Void
    let onOpenSearch: () -> Void
    let onOpenFolder: (FolderEntity) -> Void
    let onOpenSmartCollection: (SmartCollectionEntity) -> Void
    let onOpenTagLibrary: () -> Void
    let onOpenTag: (TagEntity) -> Void
    let onOpenLocation: (LocationEntity) -> Void
    let onOpenPerson: (PersonEntity) -> Void

    @StateObject private var model: MomentsViewModel
    @StateObject private var speechRecognizer = MomentsSearchSpeechRecognizer()
    @State private var selectedFilters: Set<MomentFilter> = []
    @State private var selectedTagIds: Set<String> = []
    @State private var selectedPersonIds: Set<String> = []
    @State private var selectedLocationIds: Set<String> = []
    @State private var showFilters = false
    @State private var activeFilterPicker: MomentFilterPicker?
    @State private var openGalleryGroupId: String?
    @State private var searchQuery = ""
    @State private var selectedTab: MomentHomeTab = .timeline
    @State private var folderEditorMode: FolderEditorMode?

    init(
        userId: String,
        onOpenCapture: @escaping (String) -> Void,
        onOpenSearch: @escaping () -> Void,
        onOpenFolder: @escaping (FolderEntity) -> Void,
        onOpenSmartCollection: @escaping (SmartCollectionEntity) -> Void,
        onOpenTagLibrary: @escaping () -> Void,
        onOpenTag: @escaping (TagEntity) -> Void,
        onOpenLocation: @escaping (LocationEntity) -> Void,
        onOpenPerson: @escaping (PersonEntity) -> Void
    ) {
        self.userId = userId
        self.onOpenCapture = onOpenCapture
        self.onOpenSearch = onOpenSearch
        self.onOpenFolder = onOpenFolder
        self.onOpenSmartCollection = onOpenSmartCollection
        self.onOpenTagLibrary = onOpenTagLibrary
        self.onOpenTag = onOpenTag
        self.onOpenLocation = onOpenLocation
        self.onOpenPerson = onOpenPerson
        _model = StateObject(wrappedValue: MomentsViewModel(userId: userId))
    }

    var body: some View {
        Group {
            if let galleryGroup = openGalleryGroup {
                MomentGalleryDetailView(
                    group: galleryGroup,
                    primaryTagByCapture: model.primaryTagByCapture,
                    onBack: { openGalleryGroupId = nil },
                    onOpenCapture: onOpenCapture,
                    onToggleFavorite: { model.toggleFavorite($0) }
                )
            } else {
                momentsHome
            }
        }
        .background(QuickInkColors.bg.ignoresSafeArea())
        .onAppear { model.start() }
        .onDisappear { speechRecognizer.stop() }
        .onChange(of: speechRecognizer.transcript) { value in
            searchQuery = value
        }
        .sheet(item: $activeFilterPicker) { picker in
            MomentFilterPickerSheet(
                picker: picker,
                options: filterOptions(for: picker),
                selectedIds: selectedIds(for: picker),
                onToggle: { toggleOption($0, for: picker) },
                onDone: { activeFilterPicker = nil }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(item: $folderEditorMode) { mode in
            FolderEditorView(
                mode: mode,
                onSubmit: { name, color in
                    Task {
                        if case .create = mode {
                            await model.createAlbum(name: name, color: color)
                        }
                        folderEditorMode = nil
                    }
                },
                onCancel: { folderEditorMode = nil }
            )
            .presentationDetents([.medium])
        }
    }

    private var momentsHome: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                header

                if showFilters {
                    filterPanel
                }

                quickAccessRow

                switch selectedTab {
                case .timeline:
                    timelineContent
                case .albums:
                    albumsTab
                case .smartCollections:
                    smartCollectionsTab
                }

                Color.clear.frame(height: QuickInkBottomNavReservedHeight)
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.top, QuickInkSpacing.s3)
        }
    }

    @ViewBuilder
    private var timelineContent: some View {
        if mediaCaptures.isEmpty {
            emptyState
        } else if visibleCaptures.isEmpty {
            emptyFilteredState
        } else {
            ForEach(groups) { group in
                TimelineGroupView(
                    group: group,
                    primaryTagByCapture: model.primaryTagByCapture,
                    onOpenCapture: onOpenCapture,
                    onOpenGallery: { openGalleryGroupId = group.id }
                )
            }
        }
    }

    private var visibleCaptures: [CaptureSummary] {
        let filtered = mediaCaptures.filter {
            $0.matchesMomentFilters(
                selectedFilters,
                selectedTagIds: selectedTagIds,
                selectedPersonIds: selectedPersonIds,
                selectedLocationIds: selectedLocationIds,
                tagIdsByCapture: model.tagIdsByCapture,
                personIdsByCapture: model.personIdsByCapture,
                locationIdsByCapture: model.locationIdsByCapture
            )
        }
        let searched = filtered.filter {
            $0.matchesMomentSearch(searchQuery, primaryTagName: model.primaryTagByCapture[$0.id])
        }
        return searched.sorted { $0.createdAt > $1.createdAt }
    }

    private var groups: [MomentGroup] {
        MomentGroup.make(from: visibleCaptures)
    }

    private var mediaCaptures: [CaptureSummary] {
        model.captures.filter { $0.isMomentMedia }
    }

    private var albums: [FolderEntity] {
        model.folders.filter { !$0.isDefault && !$0.isSeeded }
    }

    private var mediaCountsByFolder: [String: Int] {
        Dictionary(grouping: mediaCaptures.compactMap { $0.folderId }, by: { $0 })
            .mapValues { $0.count }
    }

    private var albumCoverByFolder: [String: CaptureSummary] {
        var out: [String: CaptureSummary] = [:]
        for capture in mediaCaptures {
            guard let folderId = capture.folderId, out[folderId] == nil else { continue }
            out[folderId] = capture
        }
        return out
    }

    private var openGalleryGroup: MomentGroup? {
        groups.first { $0.id == openGalleryGroupId }
    }

    private var photoCount: Int {
        mediaCaptures.filter { $0.mediaKind == .photo }.count
    }

    private var videoCount: Int {
        mediaCaptures.filter { $0.mediaKind == .video }.count
    }

    private var header: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Moments")
                        .font(QuickInkText.display)
                        .foregroundStyle(QuickInkColors.ink)
                    Text(mediaCaptures.isEmpty
                         ? "Photos and videos, beautifully organized"
                         : "\(mediaCaptures.count) memories · \(photoCount) photos · \(videoCount) videos")
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .lineLimit(1)
                }
                Spacer()
                CircleIconButton(
                    systemName: "sparkles",
                    label: "Smart memory highlights",
                    tint: QuickInkColors.accent,
                    action: {}
                )
            }

            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 14))
                    .foregroundStyle(QuickInkColors.muted)
                TextField(
                    speechRecognizer.isListening
                        ? "Listening..."
                        : "Search photos, places, people, tags...",
                    text: $searchQuery
                )
                .font(.system(size: 13))
                .foregroundStyle(QuickInkColors.ink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .accessibilityLabel(Text("Search moments"))

                Button(action: { speechRecognizer.toggle() }) {
                    Image(systemName: "mic.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(speechRecognizer.isListening ? QuickInkColors.accent : QuickInkColors.inkSoft)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(speechRecognizer.isListening ? "Stop voice search" : "Start voice search"))

                Button(action: { showFilters.toggle() }) {
                    Image(systemName: "slider.horizontal.3")
                        .font(.system(size: 14))
                        .foregroundStyle(QuickInkColors.inkSoft)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(showFilters ? "Hide moment filters" : "Show moment filters"))
            }
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, 12)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(QuickInkColors.surface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
            .frame(maxWidth: .infinity)
        }
    }

    private var filterPanel: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            Text("Filter")
                .font(QuickInkText.label)
                .foregroundStyle(QuickInkColors.ink)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: QuickInkSpacing.s2) {
                    ForEach(MomentFilter.allCases) { filter in
                        FilterPill(
                            label: filterLabel(filter),
                            selected: isFilterActive(filter),
                            action: { handleFilterTap(filter) }
                        )
                    }
                }
            }
            if !selectedOptionTokens.isEmpty {
                Text("Selected")
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: QuickInkSpacing.s2) {
                        ForEach(selectedOptionTokens) { token in
                            SelectedFilterChip(
                                label: token.label,
                                action: { removeOption(token) }
                            )
                        }
                    }
                }
            }
        }
        .padding(QuickInkSpacing.s4)
        .background(
            RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                .fill(QuickInkColors.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
    }

    private func toggleFilter(_ filter: MomentFilter) {
        if selectedFilters.contains(filter) {
            selectedFilters.remove(filter)
        } else {
            selectedFilters.insert(filter)
        }
    }

    private func handleFilterTap(_ filter: MomentFilter) {
        switch filter {
        case .tags:
            activeFilterPicker = .tags
        case .people:
            activeFilterPicker = .people
        case .places:
            activeFilterPicker = .places
        default:
            toggleFilter(filter)
        }
    }

    private func isFilterActive(_ filter: MomentFilter) -> Bool {
        switch filter {
        case .tags: return !selectedTagIds.isEmpty
        case .people: return !selectedPersonIds.isEmpty
        case .places: return !selectedLocationIds.isEmpty
        default: return selectedFilters.contains(filter)
        }
    }

    private func filterLabel(_ filter: MomentFilter) -> String {
        switch filter {
        case .tags where !selectedTagIds.isEmpty: return "Tags (\(selectedTagIds.count))"
        case .people where !selectedPersonIds.isEmpty: return "People (\(selectedPersonIds.count))"
        case .places where !selectedLocationIds.isEmpty: return "Places (\(selectedLocationIds.count))"
        default: return filter.label
        }
    }

    private func clearAllFilters() {
        selectedFilters.removeAll()
        selectedTagIds.removeAll()
        selectedPersonIds.removeAll()
        selectedLocationIds.removeAll()
    }

    private var selectedOptionTokens: [SelectedMomentFilterToken] {
        let tagById = Dictionary(uniqueKeysWithValues: model.tags.map { ($0.id, $0) })
        let personById = Dictionary(uniqueKeysWithValues: model.people.map { ($0.id, $0) })
        let locationById = Dictionary(uniqueKeysWithValues: model.locations.map { ($0.id, $0) })
        var tokens: [SelectedMomentFilterToken] = []
        tokens.append(contentsOf: selectedTagIds.map {
            SelectedMomentFilterToken(
                kind: .tags,
                optionId: $0,
                label: "#\(tagById[$0]?.name ?? "Tag")"
            )
        })
        tokens.append(contentsOf: selectedPersonIds.map {
            SelectedMomentFilterToken(
                kind: .people,
                optionId: $0,
                label: personById[$0]?.name ?? "Person"
            )
        })
        tokens.append(contentsOf: selectedLocationIds.map {
            SelectedMomentFilterToken(
                kind: .places,
                optionId: $0,
                label: locationById[$0]?.name ?? "Place"
            )
        })
        return tokens
    }

    private func selectedIds(for picker: MomentFilterPicker) -> Set<String> {
        switch picker {
        case .tags: return selectedTagIds
        case .people: return selectedPersonIds
        case .places: return selectedLocationIds
        }
    }

    private func toggleOption(_ id: String, for picker: MomentFilterPicker) {
        switch picker {
        case .tags:
            selectedTagIds.toggleMembership(id)
        case .people:
            selectedPersonIds.toggleMembership(id)
        case .places:
            selectedLocationIds.toggleMembership(id)
        }
    }

    private func removeOption(_ token: SelectedMomentFilterToken) {
        switch token.kind {
        case .tags:
            selectedTagIds.remove(token.optionId)
        case .people:
            selectedPersonIds.remove(token.optionId)
        case .places:
            selectedLocationIds.remove(token.optionId)
        }
    }

    private func filterOptions(for picker: MomentFilterPicker) -> [MomentFilterOption] {
        switch picker {
        case .tags:
            return model.tags.map {
                let bucketId = $0.bucket ?? inferMomentTagBucketId($0.name)
                let bucket = workspaceTagBuckets.first { $0.id == bucketId }
                return MomentFilterOption(
                    id: $0.id,
                    label: $0.name,
                    subtitle: "\(model.tagCounts[$0.id] ?? 0) moments",
                    systemName: "tag",
                    tint: color(from: $0.color) ?? bucket?.hue ?? QuickInkColors.accent,
                    bucketId: bucketId
                )
            }
        case .people:
            return model.people.map {
                MomentFilterOption(
                    id: $0.id,
                    label: $0.name,
                    subtitle: "\(model.personCounts[$0.id] ?? 0) moments",
                    systemName: "person",
                    tint: color(from: $0.color) ?? QuickInkColors.accent,
                    bucketId: nil
                )
            }
        case .places:
            return model.locations.map {
                MomentFilterOption(
                    id: $0.id,
                    label: $0.name,
                    subtitle: "\(model.locationCounts[$0.id] ?? 0) moments",
                    systemName: "mappin.and.ellipse",
                    tint: color(from: $0.color) ?? QuickInkColors.accent,
                    bucketId: nil
                )
            }
        }
    }

    private var quickAccessRow: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            QuickAccessCard(
                title: "Timeline",
                caption: "All memories",
                systemName: "calendar",
                active: selectedTab == .timeline,
                action: { selectedTab = .timeline }
            )
            QuickAccessCard(
                title: "Albums",
                caption: albums.count == 1 ? "1 album" : "\(albums.count) albums",
                systemName: "rectangle.stack",
                active: selectedTab == .albums,
                action: { selectedTab = .albums }
            )
            QuickAccessCard(
                title: "Smart collections",
                caption: model.smartCollections.count == 1 ? "1 collection" : "\(model.smartCollections.count) collections",
                systemName: "sparkles",
                active: selectedTab == .smartCollections,
                action: { selectedTab = .smartCollections }
            )
        }
    }

    @ViewBuilder
    private var albumsTab: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            SectionTitleBlock(
                title: "Albums",
                subtitle: albums.isEmpty
                    ? "Create curated sets for your favorite moments"
                    : "\(albums.count) curated \(albums.count == 1 ? "set" : "sets")"
            )
            if albums.isEmpty {
                CreateAlbumHeroCard {
                    folderEditorMode = .create
                }
            } else {
                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                        GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                    ],
                    spacing: QuickInkSpacing.s3
                ) {
                    AddAlbumTile {
                        folderEditorMode = .create
                    }
                    ForEach(albums) { album in
                        AlbumTile(
                            album: album,
                            itemCount: mediaCountsByFolder[album.id] ?? 0,
                            cover: albumCoverByFolder[album.id],
                            action: { onOpenFolder(album) }
                        )
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var smartCollectionsTab: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            SectionTitleBlock(
                title: "Smart collections",
                subtitle: model.smartCollections.isEmpty
                    ? "AI-built sets will appear here"
                    : "\(model.smartCollections.count) automatic \(model.smartCollections.count == 1 ? "collection" : "collections")"
            )
            if model.smartCollections.isEmpty {
                SmartCollectionsEmptyCard()
            } else {
                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                        GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                    ],
                    spacing: QuickInkSpacing.s3
                ) {
                    ForEach(model.smartCollections) { collection in
                        SmartCollectionTile(
                            collection: collection,
                            action: { onOpenSmartCollection(collection) }
                        )
                    }
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            Image(systemName: "photo.on.rectangle.angled")
                .font(.system(size: 28, weight: .medium))
                .foregroundStyle(QuickInkColors.accent)
                .frame(width: 58, height: 58)
                .background(QuickInkColors.accentSoft, in: RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous))
            Text("No moments yet")
                .font(QuickInkText.editorial)
                .foregroundStyle(QuickInkColors.ink)
            Text("Capture or import photos and videos to build a visual timeline.")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.inkSoft)
                .multilineTextAlignment(.center)
            Button(action: onOpenSearch) {
                Label("Search library", systemImage: "magnifyingglass")
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.textOnAccent)
                    .padding(.horizontal, QuickInkSpacing.s4)
                    .padding(.vertical, QuickInkSpacing.s3)
                    .background(QuickInkColors.accent, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity)
        .padding(QuickInkSpacing.s6)
        .background(
            RoundedRectangle(cornerRadius: QuickInkRadius.xl, style: .continuous)
                .fill(QuickInkColors.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.xl, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
    }

    private var emptyFilteredState: some View {
        VStack(spacing: QuickInkSpacing.s2) {
            Text("Nothing matches this view")
                .font(QuickInkText.editorial)
                .foregroundStyle(QuickInkColors.ink)
            Text("Clear search and filters to return to the full timeline.")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.inkSoft)
            Button("Show timeline") {
                clearAllFilters()
                searchQuery = ""
            }
                .font(QuickInkText.label)
                .foregroundStyle(QuickInkColors.accent)
                .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity)
        .padding(QuickInkSpacing.s5)
        .background(
            RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                .fill(QuickInkColors.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
    }

    private func color(from raw: String?) -> Color? {
        guard let raw else { return nil }
        let cleaned = raw.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        guard let value = UInt32(cleaned, radix: 16) else { return nil }
        return Color(hex: value)
    }
}

@MainActor
final class MomentsViewModel: ObservableObject {
    @Published private(set) var captures: [CaptureSummary] = []
    @Published private(set) var primaryTagByCapture: [String: String] = [:]
    @Published private(set) var tags: [TagEntity] = []
    @Published private(set) var tagCounts: [String: Int] = [:]
    @Published private(set) var folders: [FolderEntity] = []
    @Published private(set) var smartCollections: [SmartCollectionEntity] = []
    @Published private(set) var locations: [LocationEntity] = []
    @Published private(set) var locationCounts: [String: Int] = [:]
    @Published private(set) var people: [PersonEntity] = []
    @Published private(set) var personCounts: [String: Int] = [:]
    @Published private(set) var tagIdsByCapture: [String: Set<String>] = [:]
    @Published private(set) var personIdsByCapture: [String: Set<String>] = [:]
    @Published private(set) var locationIdsByCapture: [String: Set<String>] = [:]

    private let userId: String
    private let dbQueue: DatabaseQueue
    private var cancellables: [AnyCancellable] = []
    private var started = false

    init(userId: String, database: QuickInkDatabase = .shared) {
        self.userId = userId
        self.dbQueue = database.dbQueue
    }

    func start() {
        guard !started else { return }
        started = true

        ValueObservation.tracking { [userId] db in
            try CaptureSummary.fetchAll(db, sql: """
                SELECT id, title, preview_uri, pdf_uri, page_count, created_at,
                       source, paper_size, latitude, longitude, locality,
                       sub_locality, address, folder_id, last_opened_at,
                       last_opened_page, last_opened_device, video_uri,
                       video_drive_file_id, is_favorite
                FROM captures
                WHERE user_id = ?
                  AND deleted_at IS NULL
                  AND source IN ('photo', 'video')
                ORDER BY created_at DESC
                LIMIT 120
                """, arguments: [userId])
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.captures = $0
        })
        .store(in: &cancellables)

        CaptureTagRepository()
            .observePrimaryTagNames(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.primaryTagByCapture = $0
            })
            .store(in: &cancellables)

        TagRepository()
            .observe(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.tags = $0
            })
            .store(in: &cancellables)

        CaptureTagRepository()
            .observeTagCounts(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] rows in
                self?.tagCounts = Dictionary(uniqueKeysWithValues: rows.map { ($0.tagId, $0.docCount) })
            })
            .store(in: &cancellables)

        SmartCollectionRepository()
            .observeActive(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.smartCollections = $0
            })
            .store(in: &cancellables)

        FolderRepository()
            .observe(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.folders = $0
            })
            .store(in: &cancellables)

        LocationRepository()
            .observe(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.locations = $0
            })
            .store(in: &cancellables)

        LocationRepository()
            .observeLocationCounts(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] rows in
                self?.locationCounts = Dictionary(uniqueKeysWithValues: rows.map { ($0.locationId, $0.docCount) })
            })
            .store(in: &cancellables)

        PersonRepository()
            .observe(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.people = $0
            })
            .store(in: &cancellables)

        PersonRepository()
            .observePersonCounts(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] rows in
                self?.personCounts = Dictionary(uniqueKeysWithValues: rows.map { ($0.personId, $0.docCount) })
            })
            .store(in: &cancellables)

        ValueObservation.tracking { [userId] db -> [String: Set<String>] in
            struct Pair: Decodable, FetchableRecord {
                let captureId: String
                let tagId: String
                enum CodingKeys: String, CodingKey {
                    case captureId = "capture_id"
                    case tagId = "tag_id"
                }
            }
            let rows = try Pair.fetchAll(db, sql: """
                SELECT capture_tags.capture_id AS capture_id,
                       capture_tags.tag_id     AS tag_id
                FROM capture_tags
                JOIN captures ON captures.id = capture_tags.capture_id
                JOIN tags     ON tags.id     = capture_tags.tag_id
                WHERE capture_tags.deleted_at IS NULL
                  AND captures.deleted_at IS NULL
                  AND tags.deleted_at IS NULL
                  AND captures.user_id = ?
                """, arguments: [userId])
            var map: [String: Set<String>] = [:]
            for row in rows {
                map[row.captureId, default: []].insert(row.tagId)
            }
            return map
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] value in
            self?.tagIdsByCapture = value
        })
        .store(in: &cancellables)

        ValueObservation.tracking { [userId] db -> [String: Set<String>] in
            struct Pair: Decodable, FetchableRecord {
                let captureId: String
                let personId: String
                enum CodingKeys: String, CodingKey {
                    case captureId = "capture_id"
                    case personId = "person_id"
                }
            }
            let rows = try Pair.fetchAll(db, sql: """
                SELECT capture_people.capture_id AS capture_id,
                       capture_people.person_id  AS person_id
                FROM capture_people
                JOIN captures ON captures.id = capture_people.capture_id
                JOIN people   ON people.id   = capture_people.person_id
                WHERE capture_people.deleted_at IS NULL
                  AND captures.deleted_at IS NULL
                  AND people.deleted_at IS NULL
                  AND captures.user_id = ?
                """, arguments: [userId])
            var map: [String: Set<String>] = [:]
            for row in rows {
                map[row.captureId, default: []].insert(row.personId)
            }
            return map
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] value in
            self?.personIdsByCapture = value
        })
        .store(in: &cancellables)

        ValueObservation.tracking { [userId] db -> [String: Set<String>] in
            struct Pair: Decodable, FetchableRecord {
                let captureId: String
                let locationId: String
                enum CodingKeys: String, CodingKey {
                    case captureId = "capture_id"
                    case locationId = "location_id"
                }
            }
            let rows = try Pair.fetchAll(db, sql: """
                SELECT capture_locations.capture_id  AS capture_id,
                       capture_locations.location_id AS location_id
                FROM capture_locations
                JOIN captures  ON captures.id  = capture_locations.capture_id
                JOIN locations ON locations.id = capture_locations.location_id
                WHERE capture_locations.deleted_at IS NULL
                  AND captures.deleted_at IS NULL
                  AND locations.deleted_at IS NULL
                  AND captures.user_id = ?
                """, arguments: [userId])
            var map: [String: Set<String>] = [:]
            for row in rows {
                map[row.captureId, default: []].insert(row.locationId)
            }
            return map
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] value in
            self?.locationIdsByCapture = value
        })
        .store(in: &cancellables)
    }

    func toggleFavorite(_ capture: CaptureSummary) {
        Task {
            do {
                try await CaptureRepository().setFavorite(
                    captureId: capture.id,
                    isFavorite: !capture.isFavorite
                )
                await QuickInkSyncEnvironment.shared.refreshPendingPushState()
            } catch {
                print("MomentsViewModel.toggleFavorite failed: \(error)")
            }
        }
    }

    func createAlbum(name: String, color: String) async {
        do {
            _ = try await FolderRepository().create(
                userId: userId,
                name: name,
                color: color,
                position: folders.count
            )
        } catch {
            print("MomentsViewModel.createAlbum failed: \(error)")
        }
    }
}

@MainActor
final class MomentsSearchSpeechRecognizer: ObservableObject {
    @Published private(set) var isListening = false
    @Published private(set) var transcript = ""

    private let audioEngine = AVAudioEngine()
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var recognizer: SFSpeechRecognizer? {
        SFSpeechRecognizer(locale: Locale.current)
    }

    func toggle() {
        if isListening {
            stop()
        } else {
            Task { await start() }
        }
    }

    func stop() {
        if audioEngine.isRunning {
            audioEngine.stop()
            audioEngine.inputNode.removeTap(onBus: 0)
        }
        recognitionRequest?.endAudio()
        recognitionTask?.cancel()
        recognitionTask = nil
        recognitionRequest = nil
        isListening = false
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    private func start() async {
        guard !isListening else { return }
        guard let recognizer, recognizer.isAvailable else { return }
        guard await requestSpeechPermission() else { return }
        guard await requestMicrophonePermission() else { return }

        recognitionTask?.cancel()
        recognitionTask = nil

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if #available(iOS 16, *) {
            request.addsPunctuation = true
        }
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }

        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
            try session.setActive(true, options: [.notifyOthersOnDeactivation])

            let inputNode = audioEngine.inputNode
            let recordingFormat = inputNode.outputFormat(forBus: 0)
            inputNode.removeTap(onBus: 0)
            inputNode.installTap(onBus: 0, bufferSize: 1_024, format: recordingFormat) { buffer, _ in
                request.append(buffer)
            }

            audioEngine.prepare()
            try audioEngine.start()
            recognitionRequest = request
            isListening = true
        } catch {
            stop()
            return
        }

        recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                if let text = result?.bestTranscription.formattedString.trimmingCharacters(in: .whitespacesAndNewlines),
                   !text.isEmpty {
                    self.transcript = text
                }
                if error != nil || result?.isFinal == true {
                    self.stop()
                }
            }
        }
    }

    private func requestSpeechPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
    }

    private func requestMicrophonePermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }
}

private struct TimelineGroupView: View {
    let group: MomentGroup
    let primaryTagByCapture: [String: String]
    let onOpenCapture: (String) -> Void
    let onOpenGallery: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(group.title)
                        .font(QuickInkText.heading)
                        .foregroundStyle(QuickInkColors.ink)
                    Text(group.subtitle)
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                }
                Spacer()
                Button(action: onOpenGallery) {
                    Text("\(group.items.count) items →")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.accent)
                        .padding(.horizontal, QuickInkSpacing.s2)
                        .padding(.vertical, QuickInkSpacing.s1)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Open \(group.title) gallery with \(group.items.count) items"))
            }

            AirbnbMomentCollageView(
                captures: group.items,
                primaryTagByCapture: primaryTagByCapture,
                onOpenCapture: onOpenCapture,
                onOpenGallery: onOpenGallery
            )
        }
    }
}

private struct MomentGalleryDetailView: View {
    let group: MomentGroup
    let primaryTagByCapture: [String: String]
    let onBack: () -> Void
    let onOpenCapture: (String) -> Void
    let onToggleFavorite: (CaptureSummary) -> Void

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: QuickInkSpacing.s3) {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(QuickInkColors.ink)
                            .frame(width: 42, height: 42)
                            .background(QuickInkColors.surface, in: Circle())
                            .overlay(Circle().strokeBorder(QuickInkColors.border, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Back to Moments"))

                    VStack(alignment: .leading, spacing: 2) {
                        Text(group.title)
                            .font(QuickInkText.heading)
                            .foregroundStyle(QuickInkColors.ink)
                            .lineLimit(1)
                        Text("\(group.items.count) photos & videos · \(group.subtitle)")
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.muted)
                            .lineLimit(1)
                    }

                    Spacer()
                    AirbnbActionGlyph(systemName: "square.and.arrow.up", label: "Share gallery")
                }
                .padding(.bottom, QuickInkSpacing.s3)

                GalleryMosaicView(
                    captures: group.items,
                    primaryTagByCapture: primaryTagByCapture,
                    onOpenCapture: onOpenCapture,
                    onToggleFavorite: onToggleFavorite
                )

                Color.clear.frame(height: QuickInkBottomNavReservedHeight)
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.top, QuickInkSpacing.s3)
        }
        .background(QuickInkColors.bg.ignoresSafeArea())
    }
}

private struct GalleryMosaicView: View {
    let captures: [CaptureSummary]
    let primaryTagByCapture: [String: String]
    let onOpenCapture: (String) -> Void
    let onToggleFavorite: (CaptureSummary) -> Void

    private var blocks: [GalleryMosaicBlock] {
        captures.galleryMosaicBlocks()
    }

    var body: some View {
        VStack(spacing: galleryTileGap) {
            ForEach(blocks) { block in
                GalleryMosaicBlockView(
                    block: block,
                    primaryTagByCapture: primaryTagByCapture,
                    onOpenCapture: onOpenCapture,
                    onToggleFavorite: onToggleFavorite
                )
            }
        }
    }
}

private struct GalleryMosaicBlockView: View {
    let block: GalleryMosaicBlock
    let primaryTagByCapture: [String: String]
    let onOpenCapture: (String) -> Void
    let onToggleFavorite: (CaptureSummary) -> Void

    var body: some View {
        switch block.type {
        case .wide, .single:
            if let capture = block.items.first {
                FocusedGalleryTile(
                    capture: capture,
                    primaryTagName: primaryTagByCapture[capture.id],
                    metaLabel: focusedGalleryMetaLabel(capture),
                    action: { onOpenCapture(capture.id) },
                    onToggleFavorite: { onToggleFavorite(capture) }
                )
                .frame(maxWidth: .infinity)
                .frame(height: block.type == .wide ? 232 : 208)
            }

        case .pair:
            HStack(spacing: galleryTileGap) {
                ForEach(block.items) { capture in
                    FocusedGalleryTile(
                        capture: capture,
                        primaryTagName: primaryTagByCapture[capture.id],
                        metaLabel: focusedGalleryMetaLabel(capture),
                        action: { onOpenCapture(capture.id) },
                        onToggleFavorite: { onToggleFavorite(capture) }
                    )
                    .frame(maxWidth: .infinity)
                    .frame(height: 180)
                }
                if block.items.count == 1 {
                    FocusedGalleryFiller()
                        .frame(maxWidth: .infinity)
                        .frame(height: 180)
                }
            }

        case .tallStack:
            if let lead = block.items.first {
                let stacked = Array(block.items.dropFirst())
                HStack(spacing: galleryTileGap) {
                    FocusedGalleryTile(
                        capture: lead,
                        primaryTagName: primaryTagByCapture[lead.id],
                        metaLabel: focusedGalleryMetaLabel(lead),
                        action: { onOpenCapture(lead.id) },
                        onToggleFavorite: { onToggleFavorite(lead) }
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                    VStack(spacing: galleryTileGap) {
                        ForEach(stacked) { capture in
                            FocusedGalleryTile(
                                capture: capture,
                                primaryTagName: primaryTagByCapture[capture.id],
                                metaLabel: focusedGalleryMetaLabel(capture),
                                action: { onOpenCapture(capture.id) },
                                onToggleFavorite: { onToggleFavorite(capture) }
                            )
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                        if stacked.count == 1 {
                            FocusedGalleryFiller()
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .frame(height: 320)
            }
        }
    }
}

private struct FocusedGalleryTile: View {
    let capture: CaptureSummary
    let primaryTagName: String?
    let metaLabel: String?
    let action: () -> Void
    let onToggleFavorite: () -> Void

    private var title: String {
        capture.displayTitle(primaryTagName: primaryTagName, fallback: sourceLabel(capture))
    }

    var body: some View {
        ZStack {
            MomentPreview(capture: capture)
            LinearGradient(
                colors: [.clear, .clear, .black.opacity(0.26)],
                startPoint: .top,
                endPoint: .bottom
            )

            if capture.mediaKind == .video {
                Image(systemName: "play.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Color.white)
                    .frame(width: 26, height: 26)
                    .background(Color.black.opacity(0.42), in: Circle())
                    .padding(QuickInkSpacing.s2)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }

            focusedFavoriteButton
                .padding(QuickInkSpacing.s2)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

            if let metaLabel {
                Text(metaLabel)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.black.opacity(0.38), in: Capsule())
                    .overlay(Capsule().strokeBorder(Color.white.opacity(0.72), lineWidth: 1))
                    .padding(6)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }
        }
        .background(Color(hex: 0xEAE7E0))
        .clipShape(RoundedRectangle(cornerRadius: focusedGalleryTileRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: focusedGalleryTileRadius, style: .continuous)
                .strokeBorder(Color.white.opacity(0.88), lineWidth: 1)
        )
        .shadow(color: Color.black.opacity(0.10), radius: 8, x: 0, y: 4)
        .contentShape(RoundedRectangle(cornerRadius: focusedGalleryTileRadius, style: .continuous))
        .onTapGesture(perform: action)
        .accessibilityLabel(Text("Open \(capture.mediaKind.accessibilityLabel) \(title)"))
    }

    private var focusedFavoriteButton: some View {
        Button(action: onToggleFavorite) {
            Image(systemName: capture.isFavorite ? "heart.fill" : "heart")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(capture.isFavorite ? QuickInkColors.accent : Color.white)
                .frame(width: 30, height: 30)
                .background(Color.black.opacity(capture.isFavorite ? 0.58 : 0.42), in: Circle())
                .overlay(Circle().strokeBorder(Color.white.opacity(0.58), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(capture.isFavorite ? "Remove from favorites" : "Add to favorites")
    }
}

private struct FocusedGalleryFiller: View {
    var body: some View {
        Color(hex: 0xEAE7E0)
            .clipShape(RoundedRectangle(cornerRadius: focusedGalleryTileRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: focusedGalleryTileRadius, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.88), lineWidth: 1)
            )
            .shadow(color: Color.black.opacity(0.10), radius: 8, x: 0, y: 4)
    }
}

private func focusedGalleryMetaLabel(_ capture: CaptureSummary) -> String? {
    return capture.mediaKind == .video ? "Video" : nil
}

private struct AirbnbMomentCollageView: View {
    let captures: [CaptureSummary]
    let primaryTagByCapture: [String: String]
    let onOpenCapture: (String) -> Void
    let onOpenGallery: () -> Void

    private var visible: [CaptureSummary] {
        Array(captures.prefix(6))
    }

    private var hiddenCount: Int {
        max(captures.count - visible.count, 0)
    }

    private var secondRow: [CaptureSummary] {
        Array(visible.dropFirst().prefix(2))
    }

    private var lowerRow: [CaptureSummary] {
        Array(visible.dropFirst(3).prefix(3))
    }

    var body: some View {
        if let lead = visible.first {
            VStack(spacing: 4) {
                if visible.count == 1 {
                    AirbnbHeroTile(
                        capture: lead,
                        primaryTagName: primaryTagByCapture[lead.id],
                        action: { onOpenCapture(lead.id) }
                    )
                    .frame(maxWidth: .infinity)
                    .frame(height: 220)
                } else if visible.count == 2 {
                    HStack(spacing: 4) {
                        ForEach(visible) { capture in
                            AirbnbGalleryTile(
                                capture: capture,
                                primaryTagName: primaryTagByCapture[capture.id],
                                action: { onOpenCapture(capture.id) }
                            )
                            .frame(maxWidth: .infinity)
                            .frame(height: 172)
                        }
                    }
                } else {
                    AirbnbHeroTile(
                        capture: lead,
                        primaryTagName: primaryTagByCapture[lead.id],
                        action: { onOpenCapture(lead.id) }
                    )
                    .frame(maxWidth: .infinity)
                    .frame(height: 220)

                    if !secondRow.isEmpty {
                        HStack(spacing: 4) {
                            ForEach(secondRow) { capture in
                                AirbnbGalleryTile(
                                    capture: capture,
                                    primaryTagName: primaryTagByCapture[capture.id],
                                    action: { onOpenCapture(capture.id) }
                                )
                                .frame(maxWidth: .infinity)
                                .frame(height: 128)
                            }
                        }
                    }

                    if !lowerRow.isEmpty {
                        switch lowerRow.count {
                        case 1:
                            AirbnbGalleryTile(
                                capture: lowerRow[0],
                                primaryTagName: primaryTagByCapture[lowerRow[0].id],
                                action: { onOpenCapture(lowerRow[0].id) }
                            )
                            .frame(maxWidth: .infinity)
                            .frame(height: 168)

                        case 2:
                            HStack(spacing: 4) {
                                ForEach(lowerRow) { capture in
                                    AirbnbGalleryTile(
                                        capture: capture,
                                        primaryTagName: primaryTagByCapture[capture.id],
                                        action: { onOpenCapture(capture.id) }
                                    )
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 150)
                                }
                            }

                        default:
                            HStack(spacing: 4) {
                                AirbnbGalleryTile(
                                    capture: lowerRow[0],
                                    primaryTagName: primaryTagByCapture[lowerRow[0].id],
                                    action: { onOpenCapture(lowerRow[0].id) }
                                )
                                .frame(maxWidth: .infinity, maxHeight: .infinity)

                                let stacked = Array(lowerRow.dropFirst())
                                VStack(spacing: 4) {
                                    ForEach(Array(stacked.enumerated()), id: \.element.id) { index, capture in
                                        let opensGallery = hiddenCount > 0 && index == stacked.count - 1
                                        AirbnbGalleryTile(
                                            capture: capture,
                                            primaryTagName: primaryTagByCapture[capture.id],
                                            hiddenCount: opensGallery ? hiddenCount : 0,
                                            action: opensGallery ? onOpenGallery : { onOpenCapture(capture.id) }
                                        )
                                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                                    }
                                }
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                            }
                            .frame(height: 230)
                        }
                    }
                }
            }
            .background(QuickInkColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.xl, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.xl, style: .continuous)
                    .strokeBorder(QuickInkColors.border.opacity(0.45), lineWidth: 1)
            )
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Text("Moment gallery with \(captures.count) items"))
        }
    }
}

private struct AirbnbHeroTile: View {
    let capture: CaptureSummary
    let primaryTagName: String?
    let action: () -> Void

    private var title: String {
        capture.displayTitle(primaryTagName: primaryTagName, fallback: sourceLabel(capture))
    }

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Button(action: action) {
                ZStack {
                    MomentPreview(capture: capture)
                    LinearGradient(
                        colors: [.black.opacity(0.16), .clear, .black.opacity(0.46)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
                Text(title)
                    .font(QuickInkText.editorial)
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
                Text(momentSubtitle(capture))
                    .font(QuickInkText.caption.weight(.semibold))
                    .foregroundStyle(Color.white.opacity(0.88))
                    .lineLimit(1)
            }
            .padding(QuickInkSpacing.s4)

            if capture.mediaKind == .video {
                MomentTypeBadge(kind: capture.mediaKind)
                    .padding(QuickInkSpacing.s2)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
        }
        .background(QuickInkColors.borderSoft)
        .accessibilityLabel(Text("Open highlighted \(capture.mediaKind.accessibilityLabel) \(title)"))
    }
}

private struct AirbnbGalleryTile: View {
    let capture: CaptureSummary
    let primaryTagName: String?
    var hiddenCount: Int = 0
    let action: () -> Void

    private var title: String {
        capture.displayTitle(primaryTagName: primaryTagName, fallback: sourceLabel(capture))
    }

    var body: some View {
        Button(action: action) {
            ZStack {
                MomentPreview(capture: capture)
                if hiddenCount > 0 {
                    Color.black.opacity(0.72)
                    Text("+\(hiddenCount)")
                        .font(QuickInkText.heading)
                        .foregroundStyle(Color.white)
                } else if capture.mediaKind == .video {
                    MomentTypeBadge(kind: capture.mediaKind)
                        .padding(QuickInkSpacing.s2)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                }
            }
            .background(QuickInkColors.borderSoft)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Open \(capture.mediaKind.accessibilityLabel) \(title)"))
    }
}

private struct AirbnbActionGlyph: View {
    let systemName: String
    let label: String

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(Color(hex: 0x242424))
            .frame(width: 38, height: 38)
            .background(Color.white.opacity(0.92), in: Circle())
            .accessibilityLabel(Text(label))
    }
}

private struct MomentTile: View {
    let capture: CaptureSummary
    let primaryTagName: String?
    let action: () -> Void

    private var title: String {
        capture.displayTitle(primaryTagName: primaryTagName, fallback: sourceLabel(capture))
    }

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topTrailing) {
                    MomentPreview(capture: capture)
                        .frame(maxWidth: .infinity)
                        .aspectRatio(capture.mediaKind == .video ? 0.86 : 1, contentMode: .fit)
                        .clipped()
                    MomentTypeBadge(kind: capture.mediaKind)
                        .padding(QuickInkSpacing.s2)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(QuickInkText.cardTitle)
                        .foregroundStyle(QuickInkColors.ink)
                        .lineLimit(1)
                    Text(momentSubtitle(capture))
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                        .lineLimit(1)
                }
                .padding(QuickInkSpacing.s3)
            }
            .background(QuickInkColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                    .strokeBorder(QuickInkColors.border.opacity(0.72), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Open \(capture.mediaKind.accessibilityLabel) \(title)"))
    }
}

private struct MomentHeroCard: View {
    let capture: CaptureSummary
    let primaryTagName: String?
    let action: () -> Void

    private var title: String {
        capture.displayTitle(primaryTagName: primaryTagName, fallback: sourceLabel(capture))
    }

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .bottomLeading) {
                MomentPreview(capture: capture)
                    .frame(maxWidth: .infinity, minHeight: 190, maxHeight: 190)
                    .clipped()
                LinearGradient(
                    colors: [.clear, .black.opacity(0.64)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
                    Text(title)
                        .font(QuickInkText.editorial)
                        .foregroundStyle(Color.white)
                        .lineLimit(1)
                    Text(momentSubtitle(capture))
                        .font(QuickInkText.caption)
                        .foregroundStyle(Color.white.opacity(0.84))
                        .lineLimit(1)
                }
                .padding(QuickInkSpacing.s4)
                MomentTypeBadge(kind: capture.mediaKind)
                    .padding(QuickInkSpacing.s2)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
            .frame(height: 190)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.xl, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Open highlighted \(capture.mediaKind.accessibilityLabel) \(title)"))
    }
}

private struct MomentPreview: View {
    let capture: CaptureSummary

    var body: some View {
        ZStack {
            if let image = previewImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                QuickInkColors.borderSoft
                Image(systemName: capture.mediaKind == .video ? "play.fill" : "doc.text.fill")
                    .font(.system(size: 26, weight: .medium))
                    .foregroundStyle(QuickInkColors.muted)
            }
        }
    }

    private var previewImage: UIImage? {
        guard let raw = capture.previewUri, !raw.isEmpty else { return nil }
        let path: String? = {
            if let url = URL(string: raw), url.isFileURL { return url.path }
            return raw
        }()
        guard let path else { return nil }
        return UIImage(contentsOfFile: path)
    }
}

private struct MomentTypeBadge: View {
    let kind: MediaKind

    var body: some View {
        Image(systemName: kind == .video ? "play.fill" : "photo")
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(QuickInkColors.textOnAccent)
            .padding(.horizontal, QuickInkSpacing.s2)
            .padding(.vertical, QuickInkSpacing.s1)
            .background(Color.black.opacity(0.42), in: Capsule())
            .accessibilityLabel(Text(kind.label))
    }
}

private struct QuickAccessCard: View {
    let title: String
    let caption: String
    let systemName: String
    let active: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading) {
                Image(systemName: systemName)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(active ? QuickInkColors.accent : QuickInkColors.ink)
                Spacer(minLength: QuickInkSpacing.s3)
                Text(title == "Smart collections" ? "Smart\ncollections" : title)
                    .font(QuickInkText.caption)
                    .foregroundStyle(active ? QuickInkColors.accent : QuickInkColors.ink)
                    .lineLimit(2)
                    .lineSpacing(-3)
                Text(caption)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, minHeight: 74, alignment: .leading)
            .padding(QuickInkSpacing.s3)
            .background(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .fill(active ? QuickInkColors.accentSoft : QuickInkColors.surface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .strokeBorder(active ? QuickInkColors.accent.opacity(0.28) : QuickInkColors.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
        .accessibilityLabel(Text(title))
    }
}

private struct SectionTitleBlock: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(QuickInkText.label)
                .foregroundStyle(QuickInkColors.ink)
            Text(subtitle)
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.inkSoft)
        }
    }
}

private struct CreateAlbumHeroCard: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: QuickInkSpacing.s3) {
                Image(systemName: "plus")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(QuickInkColors.accent)
                    .frame(width: 52, height: 52)
                    .background(QuickInkColors.accentSoft, in: RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Create your first album")
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.ink)
                    Text("Collect photos and videos into a focused memory set.")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            .padding(QuickInkSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                    .strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

private struct AddAlbumTile: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: QuickInkSpacing.s3) {
                Image(systemName: "plus")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(QuickInkColors.accent)
                    .frame(width: 44, height: 44)
                    .background(QuickInkColors.accentSoft, in: Circle())
                Text("New album")
                    .font(QuickInkText.caption.weight(.semibold))
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, minHeight: 138)
            .aspectRatio(1, contentMode: .fit)
            .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                    .strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Create album"))
    }
}

private struct AlbumTile: View {
    let album: FolderEntity
    let itemCount: Int
    let cover: CaptureSummary?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .bottomLeading) {
                if let cover {
                    MomentPreview(capture: cover)
                } else {
                    LinearGradient(
                        colors: [
                            (colorFromHex(album.color) ?? QuickInkColors.accent).opacity(0.30),
                            QuickInkColors.surface,
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                }
                LinearGradient(
                    colors: [
                        .clear,
                        Color.black.opacity(cover == nil ? 0.10 : 0.48),
                    ],
                    startPoint: .center,
                    endPoint: .bottom
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(album.name)
                        .font(QuickInkText.caption.weight(.semibold))
                        .foregroundStyle(cover == nil ? QuickInkColors.ink : Color.white)
                        .lineLimit(1)
                    Text(itemCount == 1 ? "1 item" : "\(itemCount) items")
                        .font(QuickInkText.caption)
                        .foregroundStyle(cover == nil ? QuickInkColors.inkSoft : Color.white.opacity(0.82))
                        .lineLimit(1)
                }
                .padding(QuickInkSpacing.s3)
            }
            .frame(maxWidth: .infinity, minHeight: 138)
            .aspectRatio(1, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                    .strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Open album \(album.name)"))
    }
}

private struct SmartCollectionTile: View {
    let collection: SmartCollectionEntity
    let action: () -> Void

    var body: some View {
        let accent = colorFromHex(collection.color) ?? QuickInkColors.accent
        Button(action: action) {
            VStack(alignment: .leading) {
                Image(systemName: "sparkles")
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(accent)
                    .frame(width: 42, height: 42)
                    .background(accent.opacity(0.16), in: RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                Spacer(minLength: QuickInkSpacing.s4)
                Text(collection.name)
                    .font(QuickInkText.caption.weight(.semibold))
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(2)
                Text("Smart album")
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .lineLimit(1)
            }
            .padding(QuickInkSpacing.s3)
            .frame(maxWidth: .infinity, minHeight: 138, alignment: .leading)
            .aspectRatio(1, contentMode: .fit)
            .background(
                LinearGradient(
                    colors: [accent.opacity(0.18), QuickInkColors.surface],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ),
                in: RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                    .strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Open smart collection \(collection.name)"))
    }
}

private struct SmartCollectionsEmptyCard: View {
    var body: some View {
        HStack(spacing: QuickInkSpacing.s3) {
            Image(systemName: "sparkles")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(QuickInkColors.accent)
                .frame(width: 52, height: 52)
                .background(QuickInkColors.accentSoft, in: RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text("No smart collections yet")
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                Text("Automatic sets will appear as Moments learns from your media.")
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(QuickInkSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
    }
}

private struct SectionTitle: View {
    let title: String
    var action: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        HStack {
            Text(title)
                .font(QuickInkText.label)
                .foregroundStyle(QuickInkColors.ink)
            Spacer()
            if let action, let onAction {
                Button(action: onAction) {
                    Text(action)
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.accent)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct FilterPill: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(QuickInkText.caption)
                .foregroundStyle(selected ? QuickInkColors.textOnAccent : QuickInkColors.ink)
                .padding(.horizontal, QuickInkSpacing.s3)
                .padding(.vertical, QuickInkSpacing.s2)
                .background(selected ? QuickInkColors.accent : QuickInkColors.borderSoft, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

private struct SelectedFilterChip: View {
    let label: String
    let action: () -> Void

    var body: some View {
        HStack(spacing: QuickInkSpacing.s1) {
            Text(label)
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.accent)
                .lineLimit(1)
            Button(action: action) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(QuickInkColors.accent)
                    .frame(width: 18, height: 18)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Remove \(label) filter"))
        }
        .padding(.leading, QuickInkSpacing.s3)
        .padding(.trailing, QuickInkSpacing.s1)
        .padding(.vertical, QuickInkSpacing.s2)
        .background(QuickInkColors.accentSoft, in: Capsule())
        .overlay(Capsule().strokeBorder(QuickInkColors.accent.opacity(0.18), lineWidth: 1))
    }
}

private struct MomentFilterPickerSheet: View {
    let picker: MomentFilterPicker
    let options: [MomentFilterOption]
    let selectedIds: Set<String>
    let onToggle: (String) -> Void
    let onDone: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            if picker == .tags {
                MomentTagVocabularyContent(
                    options: options,
                    selectedIds: selectedIds,
                    emptyMessage: picker.emptyMessage,
                    onToggle: onToggle,
                    onDone: onDone
                )
            } else {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(picker.title)
                            .font(QuickInkText.heading)
                            .foregroundStyle(QuickInkColors.ink)
                        Text(picker.subtitle)
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.muted)
                    }
                    Spacer()
                    Button("Done", action: onDone)
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.accent)
                        .buttonStyle(.plain)
                }

                if options.isEmpty {
                    Text(picker.emptyMessage)
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, QuickInkSpacing.s4)
                } else {
                    ScrollView(showsIndicators: false) {
                        VStack(spacing: QuickInkSpacing.s2) {
                            ForEach(options) { option in
                                MomentFilterOptionRow(
                                    option: option,
                                    selected: selectedIds.contains(option.id),
                                    action: { onToggle(option.id) }
                                )
                            }
                        }
                        .padding(.bottom, QuickInkSpacing.s4)
                    }
                }
            }
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.top, QuickInkSpacing.s3)
        .padding(.bottom, QuickInkSpacing.s5)
        .background(QuickInkColors.surface)
    }
}

private struct MomentTagVocabularyContent: View {
    let options: [MomentFilterOption]
    let selectedIds: Set<String>
    let emptyMessage: String
    let onToggle: (String) -> Void
    let onDone: () -> Void
    @State private var tagSearchQuery = ""

    private var filteredOptions: [MomentFilterOption] {
        let query = tagSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return options }
        return options.filter {
            $0.label.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }
    }

    private var sections: [MomentTagVocabularySectionData] {
        buildMomentTagVocabularySections(filteredOptions)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            HStack(alignment: .center, spacing: QuickInkSpacing.s2) {
                Text("TAG VOCABULARY")
                    .font(QuickInkFont.ui(12, weight: .semibold))
                    .tracking(1.6)
                    .foregroundStyle(QuickInkColors.ink)

                Spacer()

                Text("\(filteredOptions.count) tags · \(sections.count) buckets")
                    .font(QuickInkFont.ui(12).italic())
                    .foregroundStyle(QuickInkColors.muted)

                Button("Done", action: onDone)
                    .font(QuickInkFont.ui(12, weight: .semibold))
                    .foregroundStyle(QuickInkColors.accent)
                    .buttonStyle(.plain)
            }

            if options.isEmpty {
                Text(emptyMessage)
                    .font(QuickInkFont.ui(12))
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, QuickInkSpacing.s4)
            } else {
                MomentTagVocabularySearchField(query: $tagSearchQuery)

                if filteredOptions.isEmpty {
                    Text("No matching tags.")
                        .font(QuickInkFont.ui(12))
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, QuickInkSpacing.s4)
                } else {
                    ScrollView(showsIndicators: false) {
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(sections) { section in
                                MomentTagVocabularySectionView(
                                    section: section,
                                    selectedIds: selectedIds,
                                    showBottomBorder: section.id != sections.last?.id,
                                    onToggle: onToggle
                                )
                            }
                        }
                        .padding(.bottom, QuickInkSpacing.s4)
                    }
                    .frame(maxHeight: 520)
                }
            }
        }
    }
}

private struct MomentTagVocabularySearchField: View {
    @Binding var query: String

    var body: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(QuickInkColors.muted)

            TextField("Search tags...", text: $query)
                .font(QuickInkFont.ui(12))
                .foregroundStyle(QuickInkColors.ink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(QuickInkColors.muted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Clear tag search"))
            }
        }
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.vertical, 10)
        .background(QuickInkColors.bg, in: RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .strokeBorder(QuickInkColors.borderSoft, lineWidth: 1)
        )
    }
}

private struct MomentTagVocabularySectionView: View {
    let section: MomentTagVocabularySectionData
    let selectedIds: Set<String>
    let showBottomBorder: Bool
    let onToggle: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: QuickInkSpacing.s3) {
                RoundedRectangle(cornerRadius: 99, style: .continuous)
                    .fill(section.hue)
                    .frame(width: 3, height: 42)
                    .padding(.top, 3)

                VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
                    HStack(alignment: .center, spacing: QuickInkSpacing.s2) {
                        Text(section.name.uppercased())
                            .font(QuickInkFont.ui(13, weight: .semibold))
                            .tracking(1)
                            .foregroundStyle(section.hue)

                        if let prefixLabel = section.prefixLabel {
                            Text(prefixLabel)
                                .font(QuickInkFont.ui(12))
                                .foregroundStyle(QuickInkColors.muted)
                        }

                        Spacer()

                        Text("\(section.options.count)")
                            .font(QuickInkFont.ui(12, weight: .semibold))
                            .foregroundStyle(QuickInkColors.ink)
                            .frame(width: 34, height: 34)
                            .background(QuickInkColors.bg, in: Circle())
                    }

                    Text(section.question)
                        .font(QuickInkFont.ui(12).italic())
                        .foregroundStyle(QuickInkColors.muted)

                    FlowLayout(spacing: QuickInkSpacing.s2, runSpacing: QuickInkSpacing.s2) {
                        ForEach(section.options) { option in
                            MomentTagVocabularyChip(
                                option: option,
                                tint: section.hue,
                                selected: selectedIds.contains(option.id),
                                action: { onToggle(option.id) }
                            )
                        }
                    }
                }
            }
            .padding(.top, QuickInkSpacing.s3)

            if showBottomBorder {
                Rectangle()
                    .fill(QuickInkColors.borderSoft)
                    .frame(height: 1)
                    .padding(.top, QuickInkSpacing.s3)
            }
        }
    }
}

private struct MomentTagVocabularyChip: View {
    let option: MomentFilterOption
    let tint: Color
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(option.label)
                .font(QuickInkFont.ui(12, weight: .semibold))
                .foregroundStyle(tint)
                .lineLimit(1)
                .padding(.horizontal, QuickInkSpacing.s3)
                .padding(.vertical, 7)
                .background(selected ? tint.opacity(0.14) : Color.clear, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? tint : tint.opacity(0.72), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Filter by tag \(option.label)"))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

private struct MomentFilterOptionRow: View {
    let option: MomentFilterOption
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: QuickInkSpacing.s3) {
                Image(systemName: option.systemName)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(option.tint)
                    .frame(width: 34, height: 34)
                    .background(option.tint.opacity(0.14), in: Circle())

                VStack(alignment: .leading, spacing: 2) {
                    Text(option.label)
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.ink)
                        .lineLimit(1)
                    if let subtitle = option.subtitle {
                        Text(subtitle)
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.muted)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(QuickInkColors.accent)
                }
            }
            .padding(QuickInkSpacing.s3)
            .background(selected ? QuickInkColors.accentSoft : QuickInkColors.bg, in: RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .strokeBorder(selected ? QuickInkColors.accent.opacity(0.24) : QuickInkColors.borderSoft, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

private struct CircleIconButton: View {
    let systemName: String
    let label: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 42, height: 42)
                .background(QuickInkColors.surface, in: Circle())
                .overlay(Circle().strokeBorder(QuickInkColors.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }
}

private enum MomentFilter: String, CaseIterable, Identifiable {
    case photos
    case videos
    case favorites
    case tags
    case people
    case places

    var id: String { rawValue }

    var label: String {
        switch self {
        case .photos: return "Photos"
        case .videos: return "Videos"
        case .favorites: return "Favorites"
        case .tags: return "Tags"
        case .people: return "People"
        case .places: return "Places"
        }
    }
}

private enum MomentFilterPicker: String, Identifiable {
    case tags
    case people
    case places

    var id: String { rawValue }

    var title: String {
        switch self {
        case .tags: return "Choose tags"
        case .people: return "Choose people"
        case .places: return "Choose places"
        }
    }

    var subtitle: String {
        switch self {
        case .tags: return "Show moments with any selected tag."
        case .people: return "Show moments with any selected person."
        case .places: return "Show moments from any selected place."
        }
    }

    var emptyMessage: String {
        switch self {
        case .tags: return "No tags are available yet."
        case .people: return "No people are available yet."
        case .places: return "No places are available yet."
        }
    }
}

private struct MomentFilterOption: Identifiable {
    let id: String
    let label: String
    let subtitle: String?
    let systemName: String
    let tint: Color
    let bucketId: String?
}

private struct MomentTagVocabularySectionData: Identifiable {
    let id: String
    let name: String
    let question: String
    let hue: Color
    let prefixLabel: String?
    let options: [MomentFilterOption]
}

private struct SelectedMomentFilterToken: Identifiable {
    let kind: MomentFilterPicker
    let optionId: String
    let label: String

    var id: String { "\(kind.rawValue)-\(optionId)" }
}

private enum MediaKind: String {
    case photo
    case video

    var label: String {
        switch self {
        case .photo: return "Photo"
        case .video: return "Video"
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .photo: return "photo"
        case .video: return "video"
        }
    }
}

private struct MomentGroup: Identifiable {
    let id: String
    let sortDate: Date
    let title: String
    let subtitle: String
    let items: [CaptureSummary]

    static func make(from captures: [CaptureSummary]) -> [MomentGroup] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: captures) { capture -> Date in
            guard let date = parseMomentDate(capture.createdAt) else {
                return Date.distantPast
            }
            return calendar.startOfDay(for: date)
        }

        return grouped.map { day, items in
            MomentGroup(
                id: String(day.timeIntervalSince1970),
                sortDate: day,
                title: dayLabel(day),
                subtitle: groupSubtitle(items),
                items: items.sorted { $0.createdAt > $1.createdAt }
            )
        }
        .sorted { $0.sortDate > $1.sortDate }
    }
}

private struct GalleryMosaicBlock: Identifiable {
    let id: String
    let type: GalleryMosaicBlockType
    let items: [CaptureSummary]
}

private enum GalleryMosaicBlockType {
    case wide
    case pair
    case tallStack
    case single
}

private extension CaptureSummary {
    var isMomentMedia: Bool {
        source == "photo" || source == "video"
    }

    var mediaKind: MediaKind {
        let hasVideo = !(videoUri ?? "").isEmpty || !(videoDriveFileId ?? "").isEmpty
        if source == "video" || (source == "photo" && hasVideo) {
            return .video
        }
        return .photo
    }

    func displayTitle(primaryTagName: String?, fallback: String) -> String {
        if let raw = title?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty {
            return raw
        }
        if let tag = primaryTagName?.trimmingCharacters(in: .whitespacesAndNewlines), !tag.isEmpty {
            return tag
        }
        return fallback
    }

    func matchesMomentSearch(_ query: String, primaryTagName: String?) -> Bool {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else { return true }

        let fields = [
            title,
            primaryTagName,
            displayTitle(primaryTagName: primaryTagName, fallback: sourceLabel(self)),
            sourceLabel(self),
            mediaKind.label,
            subLocality,
            locality,
            address,
            createdAt,
            momentDate(createdAt),
            isFavorite ? "favorite" : nil
        ]
        return fields.contains { value in
            value?.range(of: needle, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }
    }

    func matchesMomentFilters(
        _ selected: Set<MomentFilter>,
        selectedTagIds: Set<String>,
        selectedPersonIds: Set<String>,
        selectedLocationIds: Set<String>,
        tagIdsByCapture: [String: Set<String>],
        personIdsByCapture: [String: Set<String>],
        locationIdsByCapture: [String: Set<String>]
    ) -> Bool {
        guard !selected.isEmpty ||
                !selectedTagIds.isEmpty ||
                !selectedPersonIds.isEmpty ||
                !selectedLocationIds.isEmpty else { return true }

        let mediaTypeSelected = selected.contains(.photos) || selected.contains(.videos)
        if mediaTypeSelected {
            let matchesMediaType =
                (selected.contains(.photos) && mediaKind == .photo) ||
                (selected.contains(.videos) && mediaKind == .video)
            guard matchesMediaType else { return false }
        }

        if selected.contains(.favorites), !isFavorite { return false }
        if !selectedTagIds.isEmpty,
           tagIdsByCapture[id, default: []].isDisjoint(with: selectedTagIds) { return false }
        if !selectedPersonIds.isEmpty,
           personIdsByCapture[id, default: []].isDisjoint(with: selectedPersonIds) { return false }
        if !selectedLocationIds.isEmpty,
           locationIdsByCapture[id, default: []].isDisjoint(with: selectedLocationIds) { return false }

        return true
    }

}

private extension Set where Element == String {
    mutating func toggleMembership(_ value: String) {
        if contains(value) {
            remove(value)
        } else {
            insert(value)
        }
    }
}

private extension Array where Element == CaptureSummary {
    func galleryMosaicBlocks() -> [GalleryMosaicBlock] {
        guard !isEmpty else { return [] }

        let openingType: GalleryMosaicBlockType
        switch count {
        case 3...:
            openingType = .tallStack
        case 2:
            openingType = .pair
        default:
            openingType = .single
        }

        let openingCount: Int
        switch openingType {
        case .tallStack:
            openingCount = 3
        case .pair:
            openingCount = 2
        case .wide, .single:
            openingCount = 1
        }

        let openingItems = Array(self[0..<openingCount])
        var blocks: [GalleryMosaicBlock] = [
            GalleryMosaicBlock(
                id: "\(openingType)-\(openingItems.map(\.id).joined(separator: "-"))",
                type: openingType,
                items: openingItems
            )
        ]
        var index = openingCount
        var patternIndex = 0

        while index < count {
            let remaining = count - index
            let type: GalleryMosaicBlockType
            if remaining >= 3 && patternIndex % 3 == 2 {
                type = .tallStack
            } else if remaining >= 2 && patternIndex % 3 == 1 {
                type = .pair
            } else if patternIndex % 3 == 0 {
                type = .wide
            } else if remaining >= 2 {
                type = .pair
            } else {
                type = .single
            }

            let itemCount: Int
            switch type {
            case .tallStack:
                itemCount = 3
            case .pair:
                itemCount = 2
            case .wide, .single:
                itemCount = 1
            }

            let end = Swift.min(index + itemCount, count)
            let slice = Array(self[index..<end])
            blocks.append(
                GalleryMosaicBlock(
                    id: "\(type)-\(slice.map(\.id).joined(separator: "-"))",
                    type: type,
                    items: slice
                )
            )
            index = end
            patternIndex += 1
        }

        return blocks
    }
}

private func groupSubtitle(_ items: [CaptureSummary]) -> String {
    let places = items.compactMap { capture -> String? in
        if let area = capture.subLocality, !area.isEmpty { return area }
        if let city = capture.locality, !city.isEmpty { return city }
        return nil
    }
    let place = places.first
    let videoCount = items.filter { $0.mediaKind == .video }.count
    let parts = [place, videoCount > 0 ? "\(videoCount) videos" : nil].compactMap { $0 }
    return parts.isEmpty ? "Mixed media" : parts.joined(separator: " · ")
}

private func dayLabel(_ day: Date) -> String {
    if day == Date.distantPast { return "Earlier" }
    let calendar = Calendar.current
    if calendar.isDateInToday(day) { return "Today" }
    if calendar.isDateInYesterday(day) { return "Yesterday" }
    let formatter = DateFormatter()
    formatter.dateFormat = "MMM d, yyyy"
    return formatter.string(from: day)
}

private func momentSubtitle(_ capture: CaptureSummary) -> String {
    let place = capture.subLocality?.isEmpty == false
        ? capture.subLocality
        : capture.locality
    return [momentDate(capture.createdAt), place, sourceLabel(capture)]
        .compactMap { $0 }
        .joined(separator: " · ")
}

private func sourceLabel(_ capture: CaptureSummary) -> String {
    switch capture.mediaKind {
    case .video:
        return "Video"
    case .photo:
        switch capture.source {
        case "import": return "Import"
        case "photo": return "Photo"
        default: return "Scan"
        }
    }
}

private func momentDate(_ iso: String) -> String {
    guard let date = parseMomentDate(iso) else { return String(iso.prefix(10)) }
    let formatter = DateFormatter()
    formatter.dateFormat = "MMM d"
    return formatter.string(from: date)
}

private func buildMomentTagVocabularySections(_ options: [MomentFilterOption]) -> [MomentTagVocabularySectionData] {
    let canonicalIds = Set(workspaceTagBuckets.map(\.id))
    var sections: [MomentTagVocabularySectionData] = workspaceTagBuckets.compactMap { bucket in
        let bucketOptions = options.filter { option in
            (option.bucketId ?? inferMomentTagBucketId(option.label)) == bucket.id
        }
        guard !bucketOptions.isEmpty else { return nil }
        return MomentTagVocabularySectionData(
            id: bucket.id,
            name: bucket.name,
            question: bucket.question,
            hue: bucket.hue,
            prefixLabel: momentBucketPrefixLabel(bucket.prefixes),
            options: bucketOptions
        )
    }

    let otherOptions = options.filter { option in
        guard let bucketId = option.bucketId ?? inferMomentTagBucketId(option.label) else { return true }
        return !canonicalIds.contains(bucketId)
    }
    if !otherOptions.isEmpty {
        sections.append(
            MomentTagVocabularySectionData(
                id: "other",
                name: "Other",
                question: "uncategorized media tags",
                hue: QuickInkColors.muted,
                prefixLabel: nil,
                options: otherOptions
            )
        )
    }

    return sections
}

private func momentBucketPrefixLabel(_ prefixes: [String]?) -> String? {
    guard let prefixes, !prefixes.isEmpty else { return nil }
    return "(" + prefixes.map { "#\($0)" }.joined(separator: ", ") + ")"
}

private func inferMomentTagBucketId(_ name: String) -> String? {
    let trimmed = name
        .trimmingCharacters(in: .whitespacesAndNewlines)
    let normalized = (trimmed.hasPrefix("#") ? String(trimmed.dropFirst()) : trimmed).lowercased()
    if let bucket = workspaceTagBuckets.first(where: { bucket in
        bucket.prefixes?.contains(where: { normalized.hasPrefix($0.lowercased()) }) == true
    }) {
        return bucket.id
    }

    switch normalized {
    case "active", "later", "done", "todo":
        return "status"
    case "focus", "shallow", "errand", "call":
        return "energy"
    case "today", "thisweek", "thismonth":
        return "time"
    case "idea", "quote", "recipe", "checklist", "template":
        return "kind"
    case "camera", "capture", "import", "photo", "scan", "screenshot", "screenshots", "share", "shared", "video", "voice":
        return "source"
    default:
        return nil
    }
}

private func parseMomentDate(_ iso: String) -> Date? {
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    let basic = ISO8601DateFormatter()
    basic.formatOptions = [.withInternetDateTime]
    return fractional.date(from: iso) ?? basic.date(from: iso)
}
