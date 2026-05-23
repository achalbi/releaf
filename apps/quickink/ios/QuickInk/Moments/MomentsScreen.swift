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

struct MomentsScreen: View {
    let userId: String
    let onOpenCapture: (String) -> Void
    let onOpenSearch: () -> Void
    let onOpenSmartCollection: (SmartCollectionEntity) -> Void
    let onOpenTagLibrary: () -> Void
    let onOpenTag: (TagEntity) -> Void
    let onOpenLocation: (LocationEntity) -> Void
    let onOpenPerson: (PersonEntity) -> Void

    @StateObject private var model: MomentsViewModel
    @StateObject private var speechRecognizer = MomentsSearchSpeechRecognizer()
    @State private var selectedFilters: Set<MomentFilter> = []
    @State private var showFilters = false
    @State private var openGalleryGroupId: String?
    @State private var searchQuery = ""

    init(
        userId: String,
        onOpenCapture: @escaping (String) -> Void,
        onOpenSearch: @escaping () -> Void,
        onOpenSmartCollection: @escaping (SmartCollectionEntity) -> Void,
        onOpenTagLibrary: @escaping () -> Void,
        onOpenTag: @escaping (TagEntity) -> Void,
        onOpenLocation: @escaping (LocationEntity) -> Void,
        onOpenPerson: @escaping (PersonEntity) -> Void
    ) {
        self.userId = userId
        self.onOpenCapture = onOpenCapture
        self.onOpenSearch = onOpenSearch
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
    }

    private var momentsHome: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                header

                if showFilters {
                    filterPanel
                }

                quickAccessRow

                if hasDiscoveryContent {
                    discoverySection
                }

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

                Color.clear.frame(height: QuickInkBottomNavReservedHeight)
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.top, QuickInkSpacing.s3)
        }
    }

    private var visibleCaptures: [CaptureSummary] {
        let filtered = mediaCaptures.filter {
            $0.matchesMomentFilters(
                selectedFilters,
                primaryTagByCapture: model.primaryTagByCapture,
                captureIdsWithPeople: model.captureIdsWithPeople,
                captureIdsWithPlaces: model.captureIdsWithPlaces
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

    private var openGalleryGroup: MomentGroup? {
        groups.first { $0.id == openGalleryGroupId }
    }

    private var photoCount: Int {
        mediaCaptures.filter { $0.mediaKind == .photo }.count
    }

    private var videoCount: Int {
        mediaCaptures.filter { $0.mediaKind == .video }.count
    }

    private var unsortedCount: Int {
        mediaCaptures.filter { $0.folderId == nil }.count
    }

    private var hasDiscoveryContent: Bool {
        !model.smartCollections.isEmpty ||
            !model.tags.isEmpty ||
            !model.people.isEmpty ||
            !model.locations.isEmpty
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
                            label: filter.label,
                            selected: selectedFilters.contains(filter),
                            action: { toggleFilter(filter) }
                        )
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

    private var quickAccessRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: QuickInkSpacing.s2) {
                QuickAccessCard(
                    title: "Timeline",
                    caption: "All memories",
                    systemName: "calendar",
                    active: selectedFilters.isEmpty,
                    action: { selectedFilters.removeAll() }
                )
                QuickAccessCard(
                    title: "Photos",
                    caption: "\(photoCount) items",
                    systemName: "photo",
                    active: selectedFilters.contains(.photos),
                    action: { toggleFilter(.photos) }
                )
                QuickAccessCard(
                    title: "Videos",
                    caption: "\(videoCount) clips",
                    systemName: "video",
                    active: selectedFilters.contains(.videos),
                    action: { toggleFilter(.videos) }
                )
                QuickAccessCard(
                    title: "Favorites",
                    caption: "Saved media",
                    systemName: "heart",
                    active: selectedFilters.contains(.favorites),
                    action: { toggleFilter(.favorites) }
                )
                QuickAccessCard(
                    title: "Albums",
                    caption: "Curated sets",
                    systemName: "rectangle.stack",
                    active: false,
                    action: onOpenSearch
                )
                QuickAccessCard(
                    title: "Smart",
                    caption: "\(model.smartCollections.count) rules",
                    systemName: "sparkles",
                    active: false,
                    action: {
                        if let first = model.smartCollections.first {
                            onOpenSmartCollection(first)
                        }
                    }
                )
                QuickAccessCard(
                    title: "Tags",
                    caption: "\(model.tags.count) labels",
                    systemName: "tag",
                    active: false,
                    action: onOpenTagLibrary
                )
                QuickAccessCard(
                    title: "People",
                    caption: "\(model.people.count) faces",
                    systemName: "person",
                    active: false,
                    action: {
                        if let first = model.people.first {
                            onOpenPerson(first)
                        }
                    }
                )
                QuickAccessCard(
                    title: "Places",
                    caption: "\(model.locations.count) places",
                    systemName: "mappin.and.ellipse",
                    active: false,
                    action: {
                        if let first = model.locations.first {
                            onOpenLocation(first)
                        }
                    }
                )
                QuickAccessCard(
                    title: "Archive",
                    caption: "Archived media",
                    systemName: "archivebox",
                    active: false,
                    action: onOpenSearch
                )
                QuickAccessCard(
                    title: "Unsorted",
                    caption: "\(unsortedCount) items",
                    systemName: "tag",
                    active: false,
                    action: onOpenSearch
                )
            }
        }
    }

    private var discoverySection: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            SectionTitle(title: "Smart Collections", action: "See all", onAction: onOpenTagLibrary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: QuickInkSpacing.s3) {
                    ForEach(model.smartCollections.prefix(4)) { collection in
                        DiscoveryCard(
                            title: collection.name,
                            caption: collection.isSeeded ? "System managed" : "Custom rule",
                            systemName: "sparkles",
                            accent: color(from: collection.color),
                            action: { onOpenSmartCollection(collection) }
                        )
                    }
                    ForEach(model.tags.prefix(3)) { tag in
                        DiscoveryCard(
                            title: tag.name,
                            caption: "Tag",
                            systemName: "tag",
                            accent: color(from: tag.color),
                            action: { onOpenTag(tag) }
                        )
                    }
                    ForEach(model.people.prefix(3)) { person in
                        DiscoveryCard(
                            title: person.name,
                            caption: "\(model.personCounts[person.id, default: 0]) moments",
                            systemName: "person",
                            accent: color(from: person.color),
                            action: { onOpenPerson(person) }
                        )
                    }
                    ForEach(model.locations.prefix(3)) { location in
                        DiscoveryCard(
                            title: location.name,
                            caption: "\(model.locationCounts[location.id, default: 0]) moments",
                            systemName: "mappin.and.ellipse",
                            accent: color(from: location.color),
                            action: { onOpenLocation(location) }
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
                selectedFilters.removeAll()
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
    @Published private(set) var smartCollections: [SmartCollectionEntity] = []
    @Published private(set) var locations: [LocationEntity] = []
    @Published private(set) var locationCounts: [String: Int] = [:]
    @Published private(set) var people: [PersonEntity] = []
    @Published private(set) var personCounts: [String: Int] = [:]
    @Published private(set) var captureIdsWithPlaces: Set<String> = []
    @Published private(set) var captureIdsWithPeople: Set<String> = []

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

        SmartCollectionRepository()
            .observeActive(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.smartCollections = $0
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

        ValueObservation.tracking { [userId] db -> Set<String> in
            let ids = try String.fetchAll(db, sql: """
                SELECT DISTINCT capture_locations.capture_id
                FROM capture_locations
                JOIN captures ON captures.id = capture_locations.capture_id
                WHERE capture_locations.deleted_at IS NULL
                  AND captures.deleted_at IS NULL
                  AND captures.user_id = ?
                """, arguments: [userId])
            return Set(ids)
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] ids in
            self?.captureIdsWithPlaces = ids
        })
        .store(in: &cancellables)

        ValueObservation.tracking { [userId] db -> Set<String> in
            let ids = try String.fetchAll(db, sql: """
                SELECT DISTINCT capture_people.capture_id
                FROM capture_people
                JOIN captures ON captures.id = capture_people.capture_id
                WHERE capture_people.deleted_at IS NULL
                  AND captures.deleted_at IS NULL
                  AND captures.user_id = ?
                """, arguments: [userId])
            return Set(ids)
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] ids in
            self?.captureIdsWithPeople = ids
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
                Text(title)
                    .font(QuickInkText.caption)
                    .foregroundStyle(active ? QuickInkColors.accent : QuickInkColors.ink)
                    .lineLimit(1)
                Text(caption)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
                    .lineLimit(1)
            }
            .frame(width: 72, height: 62, alignment: .leading)
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
        .accessibilityLabel(Text(title))
    }
}

private struct DiscoveryCard: View {
    let title: String
    let caption: String
    let systemName: String
    let accent: Color?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: QuickInkSpacing.s3) {
                Image(systemName: systemName)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(accent ?? QuickInkColors.accent)
                    .frame(width: 42, height: 42)
                    .background((accent ?? QuickInkColors.accent).opacity(0.14), in: RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.ink)
                        .lineLimit(1)
                    Text(caption)
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                        .lineLimit(1)
                }
                Spacer(minLength: QuickInkSpacing.s2)
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(QuickInkColors.muted)
            }
            .frame(width: 172)
            .padding(QuickInkSpacing.s3)
            .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                    .strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(title))
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
    case labels
    case people
    case places

    var id: String { rawValue }

    var label: String {
        switch self {
        case .photos: return "Photos"
        case .videos: return "Videos"
        case .favorites: return "Favorites"
        case .labels: return "Labels"
        case .people: return "People"
        case .places: return "Places"
        }
    }
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
        primaryTagByCapture: [String: String],
        captureIdsWithPeople: Set<String>,
        captureIdsWithPlaces: Set<String>
    ) -> Bool {
        guard !selected.isEmpty else { return true }

        let mediaTypeSelected = selected.contains(.photos) || selected.contains(.videos)
        if mediaTypeSelected {
            let matchesMediaType =
                (selected.contains(.photos) && mediaKind == .photo) ||
                (selected.contains(.videos) && mediaKind == .video)
            guard matchesMediaType else { return false }
        }

        if selected.contains(.favorites), !isFavorite { return false }
        if selected.contains(.labels), (primaryTagByCapture[id] ?? "").isEmpty { return false }
        if selected.contains(.people), !captureIdsWithPeople.contains(id) { return false }
        if selected.contains(.places),
           !captureIdsWithPlaces.contains(id),
           (locality ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return false
        }

        return true
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

private func parseMomentDate(_ iso: String) -> Date? {
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    let basic = ISO8601DateFormatter()
    basic.formatOptions = [.withInternetDateTime]
    return fractional.date(from: iso) ?? basic.date(from: iso)
}
