/*
 * HomeScreen.swift
 *
 * QuickInk's home dashboard. Per the mockup spec:
 *   - Greeting header
 *   - Search bar (pill with soft border tone)
 *   - Sync status pill
 *   - Horizontal "Recent" rail of note thumbnails
 *   - 2-column category grid
 *   - Bottom nav with a Zap (⚡) FAB in the middle
 *
 * Architecturally this replaces the Slice-3 camera-first single-CTA
 * Home. The scanner is now reachable explicitly via the FAB rather
 * than an auto-launch on first appear. That matches the mockup
 * direction (a dashboard the user can land on, not an immediate
 * capture mode).
 *
 * Data sources:
 *   - Recent scans: NotepadListViewModel's first 5 entries.
 *   - Sync status: SyncStateStore.shared.
 *
 * Counterpart: Android `HomeScreen.kt`.
 */

import SwiftUI
import ReleafCoreNotes
import ReleafCoreScan
import ReleafCoreSync

struct HomeScreen: View {
    @ObservedObject var controller: ScanFlowController
    let userId: String
    let onOpenNotes: () -> Void
    let onOpenSettings: () -> Void

    /// Optional callbacks that aren't wired up at the QuickInkRoot
    /// level yet (Search and category-tap navigation land in T11
    /// and a follow-up). Hosting them here so the FAB + chrome
    /// composition is correct, and the route wiring is a single
    /// edit on QuickInkRoot when those screens land.
    var onOpenSearch: (() -> Void)? = nil
    var onTapCategory: ((String) -> Void)? = nil
    var onOpenEntry: ((String) -> Void)? = nil
    /// Tap-on-thumbnail handler for the Recent rail. Routes to the
    /// scan-detail viewer (preview image + on-demand OCR text).
    /// `nil` keeps the rail rendering but disables navigation —
    /// useful for previews / tests.
    var onOpenScan: ((String) -> Void)? = nil
    /// Routes to the new Profile editor (photo / phone / punchline).
    /// Picked from the avatar dropdown menu alongside "Sign out".
    /// Wired at QuickInkRoot.
    var onOpenProfile: (() -> Void)? = nil
    /// Avatar dropdown's Sign out action. Wired at QuickInkRoot to
    /// `authStore.signOut()` so the avatar menu can drop the user
    /// straight to the SignIn gate without a Settings detour.
    var onSignOut: (() -> Void)? = nil
    /// Resolved display name shown on the greeting. Already
    /// reconciled at the parent (Settings override > Google session
    /// displayName > nil). `nil` falls through to "QuickInk".
    var displayName: String? = nil
    /// Signed-in account email, threaded through from the parent
    /// (`QuickInkRoot`'s AuthStore session). Surfaced under the name
    /// in the profile drawer's banner header. Empty string when
    /// signed out — banner hides the email row.
    var email: String = ""
    /// `file://` URI of the user's profile photo, when set. Empty
    /// string falls back to the initial / glyph avatar. Reconciled
    /// at the parent so a Profile-screen edit re-renders the home
    /// avatar without a UserDefaults observer.
    var profilePhotoUri: String = ""

    @State private var showQuickCapture = false
    /// Side-panel drawer that slides in from the leading edge when
    /// the avatar is tapped — mirror of Releaf's home drawer
    /// (apps/releaf/ios/Releaf/Features/Home/HomeScreen.swift).
    /// Replaces the previous `Menu` so the avatar action surface is
    /// visually consistent across the two sibling apps.
    @State private var showProfileDrawer = false
    @StateObject private var capturesVM: CaptureListViewModel
    @StateObject private var categoriesVM: CategoryListViewModel
    @ObservedObject private var syncState = SyncStateStore.shared

    init(
        controller: ScanFlowController,
        userId: String,
        onOpenNotes: @escaping () -> Void,
        onOpenSettings: @escaping () -> Void,
        onOpenSearch: (() -> Void)? = nil,
        onTapCategory: ((String) -> Void)? = nil,
        onOpenEntry: ((String) -> Void)? = nil,
        onOpenScan: ((String) -> Void)? = nil,
        onOpenProfile: (() -> Void)? = nil,
        onSignOut: (() -> Void)? = nil,
        displayName: String? = nil,
        email: String = "",
        profilePhotoUri: String = ""
    ) {
        self.controller = controller
        self.userId = userId
        self.onOpenNotes = onOpenNotes
        self.onOpenSettings = onOpenSettings
        self.onOpenSearch = onOpenSearch
        self.onTapCategory = onTapCategory
        self.onOpenEntry = onOpenEntry
        self.onOpenScan = onOpenScan
        self.onOpenProfile = onOpenProfile
        self.onSignOut = onSignOut
        self.displayName = displayName
        self.email = email
        self.profilePhotoUri = profilePhotoUri

        _capturesVM = StateObject(
            wrappedValue: CaptureListViewModel(userId: userId)
        )
        _categoriesVM = StateObject(
            wrappedValue: CategoryListViewModel(userId: userId)
        )
    }

    var body: some View {
        // Outer ZStack hosts the slide-in profile drawer as a
        // sibling layer above the home content. The drawer needs
        // to bleed past the bottom safe-area inset (where the nav
        // bar lives), so wrapping it as an `.overlay` of the
        // ScrollView wouldn't extend far enough — only the ZStack
        // sibling reaches the screen edges.
        ZStack(alignment: .topLeading) {
            mainContent

            if showProfileDrawer {
                ProfileDrawerOverlay(
                    displayName:     resolvedDisplayName,
                    email:           email,
                    profilePhotoUri: profilePhotoUri,
                    onClose:         {
                        withAnimation(.easeInOut(duration: 0.22)) { showProfileDrawer = false }
                    },
                    onOpenProfile: {
                        withAnimation(.easeInOut(duration: 0.22)) { showProfileDrawer = false }
                        onOpenProfile?()
                    },
                    onOpenSettings: {
                        withAnimation(.easeInOut(duration: 0.22)) { showProfileDrawer = false }
                        onOpenSettings()
                    },
                    onSignOut: {
                        withAnimation(.easeInOut(duration: 0.22)) { showProfileDrawer = false }
                        onSignOut?()
                    }
                )
                .transition(.move(edge: .leading))
                .zIndex(1)
            }
        }
    }

    /// Original home content (scrolling dashboard + bottom nav).
    /// Hoisted out of `body` so the outer `ZStack` can host the
    /// slide-in profile drawer as a sibling above it.
    @ViewBuilder
    private var mainContent: some View {
        // `.safeAreaInset(edge: .bottom)` hosts the bar (mirror of
        // Releaf's MainShell pattern). Critical: a `ZStack(alignment:
        // .bottom)` here would give the bar the ZStack's full height,
        // and the Zap FAB's `.frame(maxHeight: .infinity)` would then
        // stretch the HStack to fill the entire screen vertically —
        // bar reads as a full-screen card with the cells floating in
        // its visual center. safeAreaInset bounds the bar to its
        // intrinsic content height (~80pt incl. the lifted FAB) and
        // automatically extends the ScrollView's safe area so content
        // never sits behind the bar — no manual `Spacer` needed.
        ScrollView {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                headerBlock
                recentRail
                categoryGrid
                // Sync pill at the bottom of the scroll content —
                // scrolls with the page, no floating over content,
                // centered horizontally. Extra top padding so it
                // doesn't sit too close to the category grid above.
                HStack {
                    Spacer()
                    syncStatusPill
                    Spacer()
                }
                .padding(.top, QuickInkSpacing.s3)
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.top, QuickInkSpacing.s4)
            // Extra breathing room at the bottom so the sync pill
            // doesn't bump into the nav bar's safe-area inset when
            // scrolled to the end.
            .padding(.bottom, QuickInkSpacing.s5)
        }
        .background(QuickInkColors.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            bottomNavBar
        }
        .task {
            // Open the live captures observation backing the rail
            // and the live categories observation backing the grid.
            capturesVM.start()
            categoriesVM.start()
        }
        .fullScreenCover(isPresented: $showQuickCapture) {
            // Mode-picker surface (the dark, branded scan UI).
            // QuickCapture's Zap shutter presents VisionKit's
            // DocumentScannerView internally; the result flows
            // back through controller.onScanComplete the same way
            // a direct scanner presentation would.
            QuickCaptureScreen(
                controller: controller,
                onDismiss:  { showQuickCapture = false }
            )
        }
    }

    // MARK: - Header

    @ViewBuilder
    private var headerBlock: some View {
        HStack(alignment: .top, spacing: QuickInkSpacing.s3) {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
                Text(greeting)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.muted)
                // Replaces the static "Quickink" title — shows the
                // user's name (Settings override > Google session
                // displayName > "QuickInk" fallback). Editable on
                // Settings → Account so the user can pick what the
                // app calls them without changing their Google
                // profile name.
                Text(resolvedDisplayName)
                    .font(QuickInkText.display)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
            }
            Spacer()
            profileIconButton
        }
    }

    /// "Settings override > Google session > QuickInk fallback".
    private var resolvedDisplayName: String {
        let trimmed = displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "QuickInk" : trimmed
    }

    /// Top-right profile pill — coral disc with the user's profile
    /// photo (when picked), the user's initial (when we have a
    /// name), or the SF Symbol person glyph. Tap slides the
    /// profile drawer in from the leading edge — same pattern as
    /// Releaf's avatar → home drawer. The drawer surfaces Profile
    /// (the new editor), Settings, and Sign out as account actions.
    @ViewBuilder
    private var profileIconButton: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.22)) {
                showProfileDrawer = true
            }
        } label: {
            ZStack {
                Circle()
                    .fill(QuickInkColors.accentSoft)
                    .frame(width: 44, height: 44)
                if let avatar = avatarUIImage {
                    Image(uiImage: avatar)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 44, height: 44)
                        .clipShape(Circle())
                } else if let initial = displayNameInitial {
                    Text(initial)
                        .font(QuickInkText.heading)
                        .foregroundStyle(QuickInkColors.accent)
                } else {
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 28))
                        .foregroundStyle(QuickInkColors.accent)
                }
            }
            .overlay(
                Circle().stroke(QuickInkColors.accent, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open profile menu")
    }

    /// Lazy-load the picked profile photo. `UIImage(contentsOfFile:)`
    /// is cheap and SwiftUI re-evaluates this computed property on
    /// every render — when the URI changes the new image lands
    /// without an explicit invalidation.
    private var avatarUIImage: UIImage? {
        guard !profilePhotoUri.isEmpty else { return nil }
        let path: String? = {
            if let url = URL(string: profilePhotoUri), url.isFileURL { return url.path }
            return profilePhotoUri
        }()
        guard let path else { return nil }
        return UIImage(contentsOfFile: path)
    }

    private var displayNameInitial: String? {
        let trimmed = displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard let first = trimmed.first else { return nil }
        return String(first).uppercased()
    }

    /// Time-of-day-aware greeting. Pure UI — independent of any
    /// signed-in display name.
    private var greeting: String {
        let hour = Calendar.current.component(.hour, from: Date())
        switch hour {
        case 5..<12:  return "Good morning"
        case 12..<18: return "Good afternoon"
        default:      return "Good evening"
        }
    }

    // MARK: - Sync status pill

    @ViewBuilder
    private var syncStatusPill: some View {
        SyncStatusPill(state: derivedSyncPillState)
    }

    /// Map from `SyncStateStore.shared.state` (pendingCount,
    /// lastFullSyncAt) to a `SyncPillState`. The offline / failed
    /// branches will activate once the shared sync layer publishes
    /// a `lastError: String?` and a network reachability bool.
    private var derivedSyncPillState: SyncPillState {
        if syncState.state.pendingCount > 0 {
            return .pending(count: syncState.state.pendingCount)
        }
        return .synced(lastSyncAt: relativeSyncTimestamp(syncState.state.lastFullSyncAt))
    }

    /// Turn an ISO-8601 timestamp into "moments ago" / "5m ago" /
    /// "2h ago" / "yesterday" / "3d ago" / "Apr 28". Nil / unparsable
    /// returns nil so the pill renders the "Not yet synced" branch
    /// instead of a misleading bare date.
    private func relativeSyncTimestamp(_ iso: String?) -> String? {
        guard let iso else { return nil }
        let isoFractional = ISO8601DateFormatter()
        isoFractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let isoBasic = ISO8601DateFormatter()
        isoBasic.formatOptions = [.withInternetDateTime]
        guard let date = isoFractional.date(from: iso) ?? isoBasic.date(from: iso) else {
            return nil
        }
        let seconds = max(0, Int(Date().timeIntervalSince(date)))
        switch seconds {
        case 0..<60:           return "moments ago"
        case 60..<3600:        return "\(seconds / 60)m ago"
        case 3600..<86_400:    return "\(seconds / 3600)h ago"
        case 86_400..<172_800: return "yesterday"
        case 172_800..<604_800:return "\(seconds / 86_400)d ago"
        default:
            let formatter = DateFormatter()
            formatter.dateFormat = "MMM d"
            return formatter.string(from: date)
        }
    }

    // MARK: - Recent rail

    @ViewBuilder
    private var recentRail: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            HStack {
                Text("RECENT")
                    .font(QuickInkText.eyebrow)
                    .tracking(QuickInkLetterSpacing.eyebrow)
                    .foregroundStyle(QuickInkColors.muted)
                Spacer()
                Button(action: onOpenNotes) {
                    Text("All notes →")
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.accent)
                }
                .buttonStyle(.plain)
            }

            if capturesVM.captures.isEmpty {
                emptyRecentRail
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: QuickInkSpacing.s3) {
                        ForEach(capturesVM.captures.prefix(6)) { capture in
                            RecentScanThumb(capture: capture)
                                .onTapGesture { onOpenScan?(capture.id) }
                        }
                    }
                    .padding(.vertical, 2) // Avoid shadow clipping at top.
                }
            }
        }
    }

    @ViewBuilder
    private var emptyRecentRail: some View {
        HStack {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
                Text("No scans yet.")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                Text("Tap the ⚡ to capture your first page.")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.muted)
            }
            Spacer()
        }
        .padding(QuickInkSpacing.s4)
        .frame(maxWidth: .infinity)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    // MARK: - Category grid

    /// Sort by the most recent capture in each category. ISO-8601
    /// `createdAt` strings sort lexicographically by timeline, so no
    /// parse step is needed. Categories with no captures in the
    /// loaded window fall to the end (empty-string key sorts smallest
    /// in descending order). Mirror of Android's `sorted` block.
    private var sortedCategories: [CategoryEntity] {
        let latestByName: [String: String] = Dictionary(
            grouping: capturesVM.captures
        ) { ($0.category ?? "").lowercased() }
        .mapValues { list in list.map(\.createdAt).max() ?? "" }

        return categoriesVM.categories.sorted { a, b in
            let aT = latestByName[a.name.lowercased()] ?? ""
            let bT = latestByName[b.name.lowercased()] ?? ""
            return aT > bT
        }
    }

    /// Map a category name to its tile SF Symbol. Default-seed names
    /// get purpose-specific glyphs; user-created categories fall
    /// through to a generic tag. Matches the Android
    /// `iconForCategory` switch.
    private func iconFor(_ name: String) -> String {
        switch name.lowercased() {
        case "ideas":      return "lightbulb"
        case "projects":   return "folder"
        case "meetings":   return "person.3"
        case "todo":       return "checkmark.circle"
        case "study":      return "graduationcap"
        case "journal":    return "book.closed"
        case "brainstorm": return "sparkles"
        default:           return "tag"
        }
    }

    @ViewBuilder
    private var categoryGrid: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            Text("CATEGORIES")
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)

            // 2-column grid sized to the live category count — every
            // active row from the `categories` table, newest first.
            // LazyVGrid scales to the long tail of user-added rows.
            LazyVGrid(
                columns: [
                    GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                    GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                ],
                spacing: QuickInkSpacing.s3
            ) {
                ForEach(sortedCategories, id: \.id) { cat in
                    let stats = categoryStats(for: cat.name)
                    CategoryTile(
                        name:         cat.name,
                        icon:         iconFor(cat.name),
                        count:        stats.count,
                        recencyBadge: stats.recencyBadge
                    )
                    .onTapGesture { onTapCategory?(cat.name) }
                }
            }
        }
    }

    /// Per-category aggregate computed from the captures we already
    /// have loaded (`capturesVM.captures`, capped at 30 by the VM).
    /// Returns the match count plus an optional "Today" / "Yesterday"
    /// recency hint for the tile's top-right badge.
    ///
    /// At 30 captures the count saturates — fine for the home tile
    /// (UI still reads "30 scans"); a dedicated `SELECT COUNT(*)
    /// GROUP BY category` query would lift that ceiling but isn't
    /// worth the extra observation while the typical user library
    /// stays well under 30.
    private func categoryStats(for name: String) -> (count: Int, recencyBadge: String?) {
        let needle = name.lowercased()
        let matching = capturesVM.captures.filter {
            ($0.category ?? "").lowercased() == needle
        }
        let count = matching.count

        // ISO8601 createdAt → calendar bucket. Two parsers because
        // older rows may not carry fractional seconds.
        let isoFractional = ISO8601DateFormatter()
        isoFractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let isoBasic = ISO8601DateFormatter()
        isoBasic.formatOptions = [.withInternetDateTime]
        let calendar = Calendar.current

        var hasToday = false
        var hasYesterday = false
        for capture in matching {
            let date = isoFractional.date(from: capture.createdAt)
                ?? isoBasic.date(from: capture.createdAt)
            guard let date else { continue }
            if calendar.isDateInToday(date) {
                hasToday = true
                break // Today wins; no point scanning further.
            }
            if calendar.isDateInYesterday(date) {
                hasYesterday = true
            }
        }
        let badge: String? = hasToday ? "Today" : (hasYesterday ? "Yesterday" : nil)
        return (count, badge)
    }

    // MARK: - Bottom nav with Zap FAB

    /// Floating glass-morphism bottom nav — frosted card hovering over
    /// the canvas with the Zap FAB lifted in the center. Mirror of
    /// Releaf's `BottomNav` layout, restyled with a `.regularMaterial`
    /// backdrop blur + warm cream tint so the editorial palette reads
    /// through the frost. The bar's silhouette is identical on Android
    /// (HomeScreen.kt → BottomNavBar) — Compose has no built-in
    /// backdrop blur without a third-party lib, so the Android side
    /// approximates with a translucent surface + bright top-edge.
    @ViewBuilder
    private var bottomNavBar: some View {
        let cardShape = RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)

        // ZStack(.top) so the FAB renders as a SIBLING of the bar,
        // AFTER the bar's `.overlay(border)` modifier. With the FAB
        // inside the HStack, the bar's `.overlay` was painting the
        // hairline border on top of the lifted FAB at the
        // intersection along the bar's top edge — visually the FAB
        // had a stripe through it. Lifting the FAB out of the HStack
        // and rendering it as the ZStack's second (top) child fixes
        // the z-order without changing the FAB's visual position.
        ZStack(alignment: .top) {
            HStack(spacing: 0) {
                navIcon(systemName: "house.fill", label: "Home", active: true) { /* current screen */ }
                    .frame(maxWidth: .infinity)
                navIconAsset(assetName: "IconNote", label: "Library", active: false, action: onOpenNotes)
                    .frame(maxWidth: .infinity)
                // Placeholder for the FAB column — keeps the HStack at
                // 5 equal cells so the flanking cells stay symmetric.
                // Fixed `.frame(height: 64)` so the bar's intrinsic
                // height doesn't collapse when the actual FAB moves
                // out of this slot.
                Color.clear
                    .frame(maxWidth: .infinity)
                    .frame(height: 64)
                navIconAsset(assetName: "IconSearch", label: "Search", active: false) { onOpenSearch?() }
                    .frame(maxWidth: .infinity)
                navIcon(systemName: "gearshape", label: "Settings", active: false, action: onOpenSettings)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, QuickInkSpacing.s1)
            .padding(.vertical, QuickInkSpacing.s1)
            // Canonical SwiftUI material API: `.background(material,
            // in: shape)` clips the backdrop blur to the rounded rect
            // cleanly. Stacking matters — first `.background` sits
            // closest to the content, subsequent calls go further
            // behind. Tint at 0.32 lets the material's gray show
            // through warmed by QuickInk cream.
            .background(QuickInkColors.surface.opacity(0.32), in: cardShape)
            .background(.thinMaterial, in: cardShape)
            .overlay(
                // Top-bright glass border — frosted-glass cue.
                cardShape.strokeBorder(
                    LinearGradient(
                        colors: [
                            Color.white.opacity(0.55),
                            QuickInkColors.border.opacity(0.40),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    lineWidth: 1
                )
            )
            // Shadows lightened from the previous pass — border halo
            // 0.32→0.18 (radius 5→4) and card lift 0.20→0.12. The bar
            // now reads as a softer hovering glass tile instead of a
            // chip with a heavy stroke.
            .shadow(color: QuickInkColors.ink.opacity(0.18), radius: 4, x: 0, y: 2)
            .shadow(color: QuickInkColors.ink.opacity(0.12), radius: 18, x: 0, y: 10)

            // FAB sibling — drawn AFTER the bar in the ZStack so it
            // sits on top of the border + glass surface.
            zapFab
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s3)
    }

    @ViewBuilder
    private func navIcon(systemName: String, label: String, active: Bool, action: @escaping () -> Void) -> some View {
        // Active cells render the icon + label inside an accentSoft
        // rounded-rect pill, tinted with the accent. Inactive cells
        // render flat in `ink` — same posture as Releaf's BottomNav
        // RegularTab.
        let tint = active ? QuickInkColors.accent : QuickInkColors.ink
        let bg   = active ? QuickInkColors.accentSoft : Color.clear
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: systemName)
                    .font(.system(size: 20))
                    .foregroundStyle(tint)
                Text(label)
                    .font(QuickInkText.caption)
                    .foregroundStyle(tint)
            }
            .padding(.horizontal, QuickInkSpacing.s2)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .fill(bg)
            )
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(active ? [.isSelected] : [])
    }

    /// Asset-backed nav icon — same shape as `navIcon` but renders a
    /// QuickInk vector asset (template-rendered, tinted via
    /// foregroundStyle). Used for the Library / Search tabs which
    /// have brand-specific icons in `Assets.xcassets`.
    @ViewBuilder
    private func navIconAsset(assetName: String, label: String, active: Bool, action: @escaping () -> Void) -> some View {
        let tint = active ? QuickInkColors.accent : QuickInkColors.ink
        let bg   = active ? QuickInkColors.accentSoft : Color.clear
        Button(action: action) {
            VStack(spacing: 2) {
                Image(assetName, bundle: .module)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(tint)
                Text(label)
                    .font(QuickInkText.caption)
                    .foregroundStyle(tint)
            }
            .padding(.horizontal, QuickInkSpacing.s2)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .fill(bg)
            )
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(active ? [.isSelected] : [])
    }

    /// The signature ⚡ Zap FAB — coral disc with a top→bottom
    /// gradient, lifted ~16pt above the card's top edge so it reads
    /// as a hovering brand mark. Counterpart to Releaf's BrandTab.
    ///
    /// Three layers, bottom up:
    ///   1. Canvas ring — bg-coloured disc 8pt larger than the FAB
    ///      with its own drop shadow. Punches through the frosted
    ///      glass bar so the lifted brand mark sits on a canvas moat
    ///      instead of looking pasted onto the bar surface.
    ///   2. Coral gradient disc with its accent halo shadow.
    ///   3. Bolt glyph.
    @ViewBuilder
    private var zapFab: some View {
        let gradient = LinearGradient(
            colors: [QuickInkColors.accent, QuickInkColors.accentDeep],
            startPoint: .top,
            endPoint: .bottom
        )
        Button(action: { showQuickCapture = true }) {
            ZStack {
                // Canvas ring shadow lightened (0.38→0.22, radius
                // 14→10, y 7→5) — moat still reads but no longer
                // dominates the bar's softer glass.
                Circle()
                    .fill(QuickInkColors.bg)
                    .frame(width: 64, height: 64)
                    .shadow(color: QuickInkColors.ink.opacity(0.22), radius: 10, x: 0, y: 5)
                // Gradient FAB disc + its own coral halo. Halo
                // lightened 0.55→0.38, radius 22→16, y 12→8. Shadow
                // attached HERE rather than on the outer ZStack so it
                // doesn't bleed through the canvas ring below.
                Circle()
                    .fill(gradient)
                    .frame(width: 56, height: 56)
                    .shadow(color: QuickInkColors.accent.opacity(0.38), radius: 16, x: 0, y: 8)
                Image(systemName: "bolt.fill")
                    .font(.system(size: 32, weight: .semibold))
                    .foregroundStyle(QuickInkColors.textOnAccent)
            }
            .offset(y: -16)
            // No `.frame(maxWidth: .infinity)` here — the FAB now
            // lives as a ZStack sibling of the bar (not an HStack
            // cell), so it should size intrinsically to its 64pt
            // canvas ring. ZStack(alignment: .top) centers it
            // horizontally and aligns its top to the bar's top edge.
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Quick capture")
    }
}

// MARK: - Component: RecentScanThumb

/// Recent-scan thumbnail used in the home rail. Renders the actual
/// preview JPEG produced by the document scanner (`captures.preview_uri`).
/// Falls back to a paper-toned placeholder while the file loads or
/// when the URI is missing — same look as the empty-state card.
/// Tapping opens `ScanDetailScreen` (full preview + OCR-on-demand).
struct RecentScanThumb: View {
    let capture: CaptureSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topLeading) {
                if let image = loadedPreview {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    placeholder
                }

                // Page-count chip — only when the capture has more
                // than one page, so single-page scans stay clean.
                if capture.pageCount > 1 {
                    pageCountChip
                        .padding(QuickInkSpacing.s2)
                }
            }
            .frame(width: 140, height: 120)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )

            VStack(alignment: .leading, spacing: 2) {
                Text(displayTitle)
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
                Text(displayDate)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }
            .padding(.top, QuickInkSpacing.s2)
        }
        .frame(width: 140)
    }

    /// Resolves `previewUri` (a `file://` URL string written by the
    /// scanner) to a `UIImage`. Returns `nil` if the file is missing
    /// or the URI is empty — the placeholder kicks in.
    private var loadedPreview: UIImage? {
        guard let raw = capture.previewUri, !raw.isEmpty else { return nil }
        let path: String? = {
            if let url = URL(string: raw), url.isFileURL { return url.path }
            return raw
        }()
        guard let path else { return nil }
        return UIImage(contentsOfFile: path)
    }

    @ViewBuilder
    private var placeholder: some View {
        ZStack {
            QuickInkColors.paper2
            Image(systemName: "doc.text.fill")
                .font(.system(size: 28))
                .foregroundStyle(QuickInkColors.muted)
        }
    }

    @ViewBuilder
    private var pageCountChip: some View {
        Text("\(capture.pageCount) pages")
            .font(QuickInkText.caption)
            .foregroundStyle(QuickInkColors.textOnAccent)
            .padding(.horizontal, QuickInkSpacing.s2)
            .padding(.vertical, 2)
            .background(
                RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                    .fill(QuickInkColors.ink.opacity(0.55))
            )
    }

    private var displayTitle: String {
        capture.category ?? "Scan"
    }

    /// Friendly date — `2026-05-02T14:30:00.000Z` → `May 2`. Falls
    /// back to the raw timestamp's date prefix if parsing fails.
    private var displayDate: String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: capture.createdAt) {
            let f = DateFormatter()
            f.dateFormat = "MMM d"
            return f.string(from: date)
        }
        return String(capture.createdAt.prefix(10))
    }
}

// MARK: - Component: CategoryTile

struct CategoryTile: View {
    let name: String
    let icon: String
    let count: Int
    /// "Today" / "Yesterday" when the category has at least one
    /// capture within that window; nil otherwise. Drawn as a small
    /// accent chip in the tile's top-right.
    let recencyBadge: String?

    init(name: String, icon: String, count: Int, recencyBadge: String? = nil) {
        self.name = name
        self.icon = icon
        self.count = count
        self.recencyBadge = recencyBadge
    }

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            HStack(alignment: .top) {
                ZStack {
                    Circle()
                        .fill(QuickInkColors.accentSoft)
                        .frame(width: 36, height: 36)
                    Image(systemName: icon)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(QuickInkColors.accent)
                }
                Spacer()
                if let badge = recencyBadge {
                    Text(badge.uppercased())
                        .font(QuickInkText.caption)
                        .tracking(QuickInkLetterSpacing.eyebrow)
                        .foregroundStyle(QuickInkColors.accent)
                        .padding(.horizontal, QuickInkSpacing.s2)
                        .padding(.vertical, 4)
                        .background(QuickInkColors.accentSoft)
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
                }
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(QuickInkText.heading)
                    .foregroundStyle(QuickInkColors.ink)
                Text(countLabel)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(QuickInkSpacing.s4)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    private var countLabel: String {
        if count == 0 { return "No scans yet" }
        return "\(count) scan\(count == 1 ? "" : "s")"
    }
}
