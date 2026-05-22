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
 *   - Recents: NotepadListViewModel's first 5 entries.
 *   - Sync status: SyncStateStore.shared.
 *
 * Counterpart: Android `HomeScreen.kt`.
 */

import SwiftUI
import Combine
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
    /// Pushes the standalone Calendar screen — panchanga + Indian
    /// holidays + moon phases + a coral dot per scan day. Reached via
    /// the small calendar icon in the home header. Wired at
    /// QuickInkRoot to `path.append(.calendar)`.
    var onOpenCalendar: (() -> Void)? = nil
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
    /// User's location, threaded down from `DaylightLocationStore`
    /// in `QuickInkRoot` so the `DaylightHero` card resolves sunrise
    /// and sunset against the user's actual position. `nil` for
    /// either falls back to Mysuru — the panchanga anchor.
    var daylightLatitude:  Double? = nil
    var daylightLongitude: Double? = nil

    /// Side-panel drawer that slides in from the leading edge when
    /// the avatar is tapped — mirror of Releaf's home drawer
    /// (apps/releaf/ios/Releaf/Features/Home/HomeScreen.swift).
    /// Replaces the previous `Menu` so the avatar action surface is
    /// visually consistent across the two sibling apps.
    @State private var showProfileDrawer = false
    @StateObject private var capturesVM: CaptureListViewModel
    @ObservedObject private var syncState = SyncStateStore.shared
    /// Watches the scheduler's published `isRunning` so the pending-
    /// sync pill can flip into its "Backing up…" state mid-pass. Mirror
    /// of Android's WorkInfo observation in `HomeScreen.kt:230` — same
    /// signal, different infra.
    @ObservedObject private var syncScheduler = QuickInkSyncEnvironment.shared.scheduler
    /// Tap-ack window: when the user taps the pill, we hold the
    /// "Syncing now…" state for ~6s even if the underlying scheduler
    /// hasn't flipped `isRunning` yet (queue.async hop + Task spin-up
    /// adds a tens-of-millis delay where the user would otherwise see
    /// the pill not respond). Mirror of Android's `syncTapAckUntilMs`
    /// debounce. `Date.distantPast` = inactive.
    @State private var syncTapAckUntil: Date = .distantPast
    /// Drives the tap-ack expiry — bumped on every tap, ticked by a
    /// 250ms timer while active so the pill flips back to its
    /// at-rest state without waiting for the next render.
    @State private var nowForTapAck: Date = Date()

    /// Per-capture primary-tag-name lookup. Replaces the pre-A.3c
    /// `captures.category` read for the category grid + recent-rail
    /// title cascade.
    @State private var primaryTagByCapture: [String: String] = [:]
    @State private var primaryTagCancellable: AnyCancellable? = nil

    /// Whether the SustainabilityBreakdown sheet is up. Hoisted here
    /// (rather than living inside [SustainabilityHero]) so the home
    /// screen's view-controller can drive `.statusBarHidden(_)` on
    /// the underlying VC — at `.large` detent the sheet doesn't
    /// cover the status-bar strip, so hiding it has to happen on
    /// the presenter, not inside the sheet's content.
    @State private var showSustainabilityBreakdown: Bool = false

    init(
        controller: ScanFlowController,
        userId: String,
        onOpenNotes: @escaping () -> Void,
        onOpenSettings: @escaping () -> Void,
        onOpenSearch: (() -> Void)? = nil,
        onOpenEntry: ((String) -> Void)? = nil,
        onOpenScan: ((String) -> Void)? = nil,
        onOpenProfile: (() -> Void)? = nil,
        onOpenCalendar: (() -> Void)? = nil,
        onSignOut: (() -> Void)? = nil,
        displayName: String? = nil,
        email: String = "",
        profilePhotoUri: String = "",
        daylightLatitude: Double? = nil,
        daylightLongitude: Double? = nil
    ) {
        self.controller = controller
        self.userId = userId
        self.onOpenNotes = onOpenNotes
        self.onOpenSettings = onOpenSettings
        self.onOpenSearch = onOpenSearch
        self.onOpenEntry = onOpenEntry
        self.onOpenScan = onOpenScan
        self.onOpenProfile = onOpenProfile
        self.onOpenCalendar = onOpenCalendar
        self.onSignOut = onSignOut
        self.displayName = displayName
        self.email = email
        self.profilePhotoUri = profilePhotoUri
        self.daylightLatitude = daylightLatitude
        self.daylightLongitude = daylightLongitude

        _capturesVM = StateObject(
            wrappedValue: CaptureListViewModel(userId: userId)
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
                    onOpenLibrary: {
                        withAnimation(.easeInOut(duration: 0.22)) { showProfileDrawer = false }
                        onOpenNotes()
                    },
                    onOpenSearch: {
                        withAnimation(.easeInOut(duration: 0.22)) { showProfileDrawer = false }
                        onOpenSearch?()
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
        // Hide the system status bar (clock / battery / wifi) while
        // the SustainabilityBreakdown sheet is up. At `.large`
        // detent the sheet stops just below the bar, so the bar
        // would otherwise render over the home view in the
        // remaining strip and read as chrome above the sheet's
        // own header. Driven from here (the presenting VC) because
        // a `.statusBarHidden(_)` modifier inside the sheet's
        // content doesn't reach the underlying VC that owns the
        // status-bar appearance at this detent.
        .statusBarHidden(showSustainabilityBreakdown)
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
                // Daylight hero — slim card showing today's sunrise
                // and sunset times with a now-marker meter beneath.
                // Sits directly above the sustainability hero per
                // the home layout brief; both cards share an
                // editorial / warm-tinted register so the pair
                // reads as one block of ambient context. Times
                // come from `sunTimesFor(_:)` — the same USNO
                // solar calculator the Calendar's Rahu Kala
                // window uses, anchored at Mysuru. Mirror of
                // Android's `DaylightHero()` call site in
                // `HomeScreen.kt`.
                DaylightHero(
                    latitude:  daylightLatitude,
                    longitude: daylightLongitude
                )
                // Sustainability hero — frames QuickInk as a paper-
                // saving tool. Total pages is sourced from the
                // `CaptureListViewModel`'s lifetime SUM observation
                // (the same VM already powering the recents rail),
                // so a fresh scan re-renders the score without an
                // extra subscription. Tap opens the Tree-points
                // breakdown sheet. Mirror of Android's
                // `SustainabilityHero(totalPages = totalPagesSaved ?: 0)`
                // call site in `HomeScreen.kt`.
                SustainabilityHero(
                    pagesBySize:   capturesVM.pagesBySize,
                    showBreakdown: $showSustainabilityBreakdown
                )
                    // Mirror the displayed Tree-points value into a
                    // shared UserDefaults key so the next cold launch's
                    // cinematic counter (`LaunchAnimationView`) ticks
                    // up to the user's actual current balance instead
                    // of the hardcoded preview default. The splash
                    // runs before any DAO observation can resolve, so
                    // a cached pref is the only way to surface a real
                    // number on the launch screen. Observed on the
                    // per-size dict so a card-only scan (which doesn't
                    // change `totalPageCount` proportionally to its
                    // 4x weight) still flushes the cache.
                    .onChange(of: capturesVM.pagesBySize) { newBuckets in
                        SettingsState.cachedTreePoints =
                            computeTreeImpact(pagesBySize: typedPagesBySize(newBuckets)).totalPoints
                    }
                    .onAppear {
                        SettingsState.cachedTreePoints =
                            computeTreeImpact(pagesBySize: typedPagesBySize(capturesVM.pagesBySize)).totalPoints
                    }
                // "N pending" pill — renders only while there are
                // local rows that haven't been pushed to Drive. One
                // tap kicks the upload-only sync via the shared
                // scheduler. Count is sourced from
                // `SyncStateStore.localDirtyCount`, refreshed by
                // `QuickInkSyncEnvironment`'s 60-second foreground
                // tick (which also auto-kicks the sync — the pill
                // is the visible surface of that mechanism).
                if syncState.state.localDirtyCount > 0 {
                    pendingSyncPill
                }
                sectionDivider
                recentRail
                // Sync pill at the bottom of the scroll content —
                // scrolls with the page, no floating over content,
                // centered horizontally.
                HStack {
                    Spacer()
                    syncStatusPill
                    Spacer()
                }
                .padding(.top, QuickInkSpacing.s3)
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.top, 6)
            // Extra breathing room at the bottom so the sync pill
            // doesn't bump into the nav bar's safe-area inset when
            // scrolled to the end.
            .padding(.bottom, QuickInkSpacing.s5)
        }
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task {
            // Live captures observation backing the recent rail.
            capturesVM.start()
            // Per-capture primary-tag-name lookup — replaces the
            // pre-A.3c `captures.category` read used by the
            // categories grid + recent-rail title.
            if primaryTagCancellable == nil {
                primaryTagCancellable = CaptureTagRepository()
                    .observePrimaryTagNames(userId: userId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { map in primaryTagByCapture = map }
                    )
            }
        }
    }

    // MARK: - Section divider

    private var sectionDivider: some View {
        Rectangle()
            .fill(QuickInkColors.border)
            .frame(height: 1)
    }

    // MARK: - Header

    @ViewBuilder
    private var headerBlock: some View {
        HStack(alignment: .top, spacing: QuickInkSpacing.s3) {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
                // Daylight cue next to the greeting — sun during the
                // day, moon-with-stars after 18:00 (and before 05:00).
                // Mirrors the Android header (glyph moved off the
                // date/time strip).
                HStack(spacing: 4) {
                    Image(systemName: isEvening ? "moon.stars.fill" : "sun.max.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(isEvening ? QuickInkColors.inkSoft : QuickInkColors.leafYellowDeep)
                    Text(greeting)
                        .font(QuickInkText.body)
                        // Deep taupe (`#5F5245`) — the `inkSoft`
                        // light-mode value, pinned as a fixed tone for
                        // the greeting in both light and dark.
                        .foregroundStyle(Color(hex: 0x5F5245))
                }
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
            calendarIconButton
            profileIconButton
        }
    }

    /// Top-right calendar button — small 44pt accent-soft disc with
    /// a coral calendar glyph. Tapping pushes the standalone Calendar
    /// screen (panchanga + Indian holidays + per-day capture dots).
    /// Sits to the left of the profile avatar so the header reads
    /// "name | calendar | profile" — calendar is a destination,
    /// profile is the account menu.
    @ViewBuilder
    private var calendarIconButton: some View {
        Button {
            onOpenCalendar?()
        } label: {
            ZStack {
                Circle()
                    .fill(QuickInkColors.accentSoft)
                    .frame(width: 44, height: 44)
                Image(systemName: "calendar")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(QuickInkColors.accent)
            }
            .overlay(
                Circle().stroke(QuickInkColors.accent.opacity(0.55), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open calendar")
    }

    /// "Settings override > Google session > QuickInk fallback".
    private var resolvedDisplayName: String {
        let trimmed = displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "QuickInk" : trimmed
    }

    /// Top-right profile pill — coral disc with the user's profile
    /// photo, initial, or SF Symbol person glyph capped at 22pt.
    /// Tapping slides the
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
                        .frame(width: 22, height: 22)
                        .clipShape(Circle())
                } else if let initial = displayNameInitial {
                    Text(initial)
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(QuickInkColors.accent)
                } else {
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 22))
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

    /// True when the greeting reads "Good evening" — drives the
    /// moon glyph (vs. sun) in `headerBlock`. Same window as the
    /// `default` branch of `greeting`: hour ≥ 18 or hour < 5.
    private var isEvening: Bool {
        let hour = Calendar.current.component(.hour, from: Date())
        return hour < 5 || hour >= 18
    }

    // MARK: - Pending-sync pill

    /// True when either the underlying scheduler is mid-pass OR we're
    /// inside the post-tap acknowledgement window. The tap-ack covers
    /// the queue.async + Task spin-up gap where `isRunning` hasn't
    /// flipped yet — without it the pill would look unresponsive on
    /// tap. Mirror of Android's `isHomeSyncInFlight`.
    private var isSyncInFlight: Bool {
        syncScheduler.isRunning || nowForTapAck < syncTapAckUntil
    }

    /// Coral pill shown between the greeting and the recent rail
    /// when there are local rows that haven't reached Drive yet.
    /// One tap kicks `QuickInkSyncEnvironment.scheduler.requestImmediate()` —
    /// the same entry point Settings → "Sync now" uses.
    ///
    /// Pill morphs into a "Backing up to Drive" state while the sync
    /// is in flight (mirror of Android's `PendingSyncPill` — circular
    /// spinner replaces the count badge, subtitle flips to "Syncing
    /// now…", and an indeterminate linear bar appears under the
    /// subtitle). iOS doesn't have WorkManager-style granular %
    /// progress today, so the bar is indeterminate; granular % is a
    /// follow-up that needs `SyncRepository` to publish a counter.
    @ViewBuilder
    private var pendingSyncPill: some View {
        let count = syncState.state.localDirtyCount
        let syncing = isSyncInFlight
        Button(action: handleSyncTap) {
            HStack(spacing: QuickInkSpacing.s3) {
                ZStack {
                    Circle().fill(QuickInkColors.accent)
                        .frame(width: 28, height: 28)
                    if syncing {
                        // Circular spinner replaces the count badge —
                        // tinted onto-accent so it reads against the
                        // coral fill, same treatment Android uses
                        // with `colors.textOnAccent`.
                        ProgressView()
                            .progressViewStyle(.circular)
                            .controlSize(.small)
                            .tint(QuickInkColors.textOnAccent)
                    } else {
                        Text(count > 99 ? "99+" : "\(count)")
                            .font(QuickInkText.label)
                            .foregroundStyle(QuickInkColors.textOnAccent)
                    }
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(syncing
                         ? "Backing up to Drive"
                         : (count == 1 ? "1 item pending" : "\(count) items pending"))
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.ink)
                    Text(syncing ? "Syncing now…" : "Tap to back up to Drive now")
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.inkSoft)
                    if syncing {
                        // Linear indeterminate progress bar — slim
                        // (4pt) and tinted accent so it doesn't
                        // crowd the existing pill geometry. Sits
                        // under the subtitle, matching Android's
                        // bar position inside the same VStack.
                        ProgressView()
                            .progressViewStyle(.linear)
                            .tint(QuickInkColors.accent)
                            .frame(height: 4)
                            .padding(.top, 4)
                    }
                }
                Spacer()
                if !syncing {
                    Text("Sync →")
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.accent)
                }
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.vertical, QuickInkSpacing.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(QuickInkColors.accentSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                    .stroke(QuickInkColors.accent.opacity(0.55), lineWidth: 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Disable the button mid-sync — re-tapping while a pass is
        // already running just lands a duplicate `requestImmediate`
        // that the scheduler's in-flight check drops anyway, so
        // disabling makes the visual state honest.
        .disabled(syncing)
        .accessibilityLabel(Text(
            syncing
                ? "Backing up to Drive. \(count) item\(count == 1 ? "" : "s") pending."
                : (count == 1
                    ? "1 item pending. Tap to back up to Drive."
                    : "\(count) items pending. Tap to back up to Drive.")
        ))
    }

    /// Tap handler — kicks the sync and arms the tap-ack window so
    /// the pill flips into "Syncing now…" the instant the user taps,
    /// even before the scheduler's `isRunning` flag flips. Spawns a
    /// 6-second sleep that bumps `nowForTapAck` once at expiry so
    /// the computed `isSyncInFlight` re-evaluates and the pill flips
    /// back when the scheduler is also idle.
    private func handleSyncTap() {
        QuickInkSyncEnvironment.shared.scheduler.requestImmediate()
        let until = Date().addingTimeInterval(6.0)
        syncTapAckUntil = until
        nowForTapAck = Date()
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            // Only bump if our window is still the active one — a
            // later tap may have extended `syncTapAckUntil` past
            // `until`, in which case its own sleep will do the
            // refresh.
            if syncTapAckUntil == until {
                nowForTapAck = Date()
            }
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
                            RecentScanThumb(
                                capture:        capture,
                                primaryTagName: primaryTagByCapture[capture.id],
                                onOpen:         { onOpenScan?(capture.id) },
                                onDelete:       {
                                    Task {
                                        try? await CaptureRepository().softDelete(id: capture.id)
                                        await QuickInkSyncEnvironment.shared.refreshPendingPushState()
                                    }
                                }
                            )
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

    // The legacy "CATEGORIES" grid was removed from the Home scroll
    // surface — tag browsing lives in the Workspace tab's tag cloud
    // now. Helpers (categoryGrid / categoryStats / sortedCategories
    // / iconFor / CategoryTile) and the `onTapCategory` callback on
    // `HomeScreen` come along with it.

    // MARK: - Bottom nav with Zap FAB

}

// MARK: - Component: RecentScanThumb

/// Recent-scan thumbnail used in the home rail. Renders the actual
/// preview JPEG produced by the document scanner (`captures.preview_uri`).
/// Falls back to a paper-toned placeholder while the file loads or
/// when the URI is missing — same look as the empty-state card.
/// Tapping opens `ScanDetailScreen` (full preview + OCR-on-demand).
struct RecentScanThumb: View {
    let capture: CaptureSummary
    /// Primary-tag-name fallback for the title cascade — replaces
    /// the pre-A.3c `captures.category` field. Nil → cascade
    /// stops at "Scan".
    let primaryTagName: String?
    let onOpen: () -> Void
    let onDelete: () -> Void

    @State private var showDeleteConfirm = false

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

                sourceChip
                    .padding(QuickInkSpacing.s2)

                // Page-count chip — only when the capture has more
                // than one page, so single-page scans stay clean.
                // Pinned top-trailing so it doesn't overlap with
                // the Import pill on imported multi-page captures.
                if capture.pageCount > 1 {
                    pageCountChip
                        .padding(QuickInkSpacing.s2)
                        .frame(maxWidth: .infinity, alignment: .topTrailing)
                }
            }
            .frame(width: 140, height: 120)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)

            HStack(alignment: .center, spacing: QuickInkSpacing.s2) {
                VStack(alignment: .leading, spacing: 2) {
                    // Title Case — matches the library grid/list/search
                    // normalisation. `.capitalized` is per-word, splits
                    // on whitespace.
                    Text(displayTitle.capitalized)
                        .font(QuickInkText.cardTitle)
                        .foregroundStyle(QuickInkColors.ink)
                        .lineLimit(1)
                    Text(displayDate)
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture(perform: onOpen)

                Menu {
                    Button(action: onOpen) {
                        Label("Open", systemImage: "arrow.up.right.square")
                    }
                    Button(role: .destructive) {
                        showDeleteConfirm = true
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(QuickInkColors.muted)
                        .frame(width: 28, height: 28)
                        .contentShape(Circle())
                }
                .menuOrder(.fixed)
                .accessibilityLabel("More actions")
            }
            .padding(.top, QuickInkSpacing.s2)
        }
        .frame(width: 140)
        .alert(deleteDialogTitle, isPresented: $showDeleteConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive, action: onDelete)
        } message: {
            Text(deleteDialogMessage)
        }
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

    /// Resolved (icon, label, fg, bg) for the source chip. Four-
    /// way:
    ///   - "scan"   (document scanner — neutral chip, the default).
    ///   - "import" (gallery-picked photos — coral accent).
    ///   - "photo"  (in-app camera shot, no video) — neutral chip
    ///              with a "Photo" label.
    ///   - "video", or legacy "photo" + `video_uri` / `video_drive_file_id` set
    ///              (hold-to-record clip) — neutral chip with a
    ///              "Video" label + `play.fill` icon so the home
    ///              recents reads videos at a glance.
    /// Photo/Video/Scan share the neutral chrome on purpose; only
    /// the label + icon disambiguate.
    private var sourceChipInfo: (icon: String, label: String, fg: Color, bg: Color) {
        switch capture.source {
        case "import":
            return ("photo", "Import", QuickInkColors.textOnAccent, QuickInkColors.accent)
        case "video":
            return ("play.fill", "Video", QuickInkColors.ink.opacity(0.7), QuickInkColors.surface.opacity(0.9))
        case "photo":
            // Photo-mode captures split into still-photo and
            // hold-to-record video by the presence of a video
            // artifact on the row (local URI OR a Drive id for a
            // not-yet-downloaded clip — covers the cross-device
            // restore window too).
            let hasVideo = !(capture.videoUri ?? "").isEmpty
                || !(capture.videoDriveFileId ?? "").isEmpty
            if hasVideo {
                return ("play.fill", "Video", QuickInkColors.ink.opacity(0.7), QuickInkColors.surface.opacity(0.9))
            }
            return ("camera.fill", "Photo", QuickInkColors.ink.opacity(0.7), QuickInkColors.surface.opacity(0.9))
        default:
            return ("camera.fill", "Scan", QuickInkColors.ink.opacity(0.7), QuickInkColors.surface.opacity(0.9))
        }
    }

    private var isVideoCapture: Bool {
        let hasVideo = !(capture.videoUri ?? "").isEmpty
            || !(capture.videoDriveFileId ?? "").isEmpty
        return capture.source == "video" ||
            (capture.source == "photo" && hasVideo)
    }

    @ViewBuilder
    private var sourceChip: some View {
        let info = sourceChipInfo
        HStack(spacing: 4) {
            Image(systemName: info.icon)
                .font(.system(size: 10))
            Text(info.label)
                .font(QuickInkText.caption)
        }
        .foregroundStyle(info.fg)
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.vertical, 2)
        .background(
            RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                .fill(info.bg)
        )
    }

    private var displayTitle: String {
        if let t = capture.title?.trimmingCharacters(in: .whitespaces),
           !t.isEmpty {
            return t
        }
        return primaryTagName ?? "Scan"
    }

    private var deleteDialogTitle: String {
        "Delete this \(deleteNoun)?"
    }

    private var deleteDialogMessage: String {
        let related = isVideoCapture
            ? "related notes"
            : "recognised text and related notes"
        return "This \(deleteNoun) and its \(related) will be removed from this device and your other devices on the next sync."
    }

    private var deleteNoun: String {
        if isVideoCapture { return "video" }
        switch capture.source {
        case "photo":  return "photo"
        case "import": return "imported item"
        default:       return "scan"
        }
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
