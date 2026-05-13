/*
 * SettingsScreen.swift
 *
 * Slice 5 — two persisted toggles. Account row, theme override,
 * version info, etc. land in later slices alongside the auth
 * wiring + brand pass.
 *
 * Mirror of Android `SettingsScreen.kt`.
 */

import SwiftUI
import ReleafCoreAuth
import ReleafCoreDesignSystem
import ReleafCoreSync

struct SettingsScreen: View {

    let onBack: () -> Void
    @ObservedObject var authStore: AuthStore
    /// Owned by `MainShell` so the same SettingsState instance feeds
    /// the SettingsScreen pickers AND the QuickInkRoot theme-mode /
    /// primary-color resolver. A previous `@StateObject` here meant
    /// SettingsScreen built its own SettingsState; the picker change
    /// updated THAT instance, but MainShell's separate instance kept
    /// the old values, so the theme bar / accent never flipped.
    /// Mirror of Android's settings hoist into `MainShell` via
    /// `app.quickink.mobile.QuickInkRoot.MainShell.settingsPrefs`.
    @ObservedObject var settings: SettingsState
    let onManageCategories: (() -> Void)?
    /// Tab navigation callbacks for the floating bottom nav. Settings
    /// paints itself active; tapping it is a no-op.
    let onHome: () -> Void
    let onWorkspace: () -> Void
    let onScan: () -> Void
    let onSearch: () -> Void
    let onSettings: () -> Void

    init(
        onBack: @escaping () -> Void,
        authStore: AuthStore,
        settings: SettingsState,
        onManageCategories: (() -> Void)? = nil,
        onHome: @escaping () -> Void = {},
        onWorkspace: @escaping () -> Void = {},
        onScan: @escaping () -> Void = {},
        onSearch: @escaping () -> Void = {},
        onSettings: @escaping () -> Void = {}
    ) {
        self.onBack = onBack
        self.authStore = authStore
        self.settings = settings
        self.onManageCategories = onManageCategories
        self.onHome = onHome
        self.onWorkspace = onWorkspace
        self.onScan = onScan
        self.onSearch = onSearch
        self.onSettings = onSettings
    }

    /// Slice 4.2b — observes `SyncStateStore.shared` for the
    /// "Last synced" row. The store is a published `ObservableObject`,
    /// so SwiftUI re-renders this view whenever a sync pass writes a
    /// fresh `lastFullSyncAt` (via `SyncRepository.recordSuccess`).
    @ObservedObject private var syncState = SyncStateStore.shared

    /// Transient "Syncing now…" feedback while a manual Sync now /
    /// Restore from Drive pass is in flight. The underlying
    /// `requestImmediate` / `requestRestore` are fire-and-forget,
    /// and `SyncRepository.sync` swallows errors via `try?`, so
    /// without this the user has no visible signal that anything
    /// happened on tap. Held for ~2.5s — long enough to read,
    /// short enough that a slow sync still resolves to the real
    /// state via the published `syncState` re-render.
    @State private var isSyncingFlash = false

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                VStack(spacing: QuickInkSpacing.s5) {
                    section(title: "Appearance") {
                        themeModeRow
                        primaryColorRow
                    }

                    section(title: "Account") {
                        accountRow
                        // Display-name override row — what the Home
                        // greeting shows. Empty value falls back to
                        // the Google session's name (see
                        // `MainShell.resolvedDisplayName`). Bound
                        // directly to the published settings field so
                        // edits land in UserDefaults on every keystroke
                        // — no Save button needed.
                        displayNameRow
                    }

                    section(title: "Sync") {
                        toggleRow(
                            label: "Back up to Google Drive",
                            help:  "Scans and notes sync to Drive so they follow you across devices.",
                            isOn:  $settings.driveBackupEnabled
                        )
                        // Toggling Drive backup ON kicks an
                        // immediate pass so the user doesn't wait
                        // 15 minutes to see their first upload.
                        // The worker no-ops when the flag is off,
                        // so toggling OFF doesn't need to cancel
                        // the schedule. Mirror of the Android
                        // SettingsScreen's `requestImmediate` call
                        // path — see SettingsScreen.kt.
                        // iOS 17+ two-arg form preferred, but the
                        // one-arg form keeps iOS-16 compatibility
                        // (see Phase-3 build-target compromise).
                        .onChange(of: settings.driveBackupEnabled) { newValue in
                            if newValue, case .signedIn = authStore.state {
                                QuickInkSyncEnvironment.shared.scheduler.requestImmediate()
                            }
                        }
                        // Last sync row — reads the most recent
                        // successful pass from `SyncStateStore`.
                        // Renders the raw ISO-8601 timestamp for now;
                        // a "moments ago / 5m ago / yesterday at 3pm"
                        // formatter can land later when the rest of
                        // the surface gets a relative-time util.
                        // (Releaf's DriveSettingsSection ships the
                        // same way today.)
                        lastSyncedRow

                        // Slice 4.2d — Sync now / Restore from
                        // Drive controls. Mirror of Releaf's
                        // DriveSettingsSection CTAs. Both call
                        // requestImmediate; sync is bidirectional,
                        // so a manual kick of the same worker
                        // covers both push (sync now) and pull
                        // (restore) — distinct labels just frame
                        // the intent for the user. Taps on the
                        // signed-out state no-op gracefully because
                        // the scheduler's `runOnce` closure
                        // short-circuits without a session.
                        syncControlsRow
                        driveFolderRow
                    }

                    section(title: "Location") {
                        // Master switch for the scan + import flows'
                        // geolocation attach. When off, the capture
                        // saves with NULL latitude / longitude /
                        // locality columns — Details card simply
                        // omits the Area + City rows. The system
                        // permission grant is unrelated; revoking it
                        // there shuts the feature off independently.
                        toggleRow(
                            label: "Attach location to scans",
                            help:  "Each scan records the city and area it was taken in so you can find scans by place.",
                            isOn:  $settings.locationForScansEnabled
                        )
                    }

                    if let onManageCategories {
                        section(title: "Categories") {
                            categoriesRow(onTap: onManageCategories)
                        }
                    }

                    section(title: "Experimental") {
                        toggleRow(
                            label: "Searchable PDF export",
                            help:  "Adds an invisible OCR text layer to exported PDFs so PDF readers can search and copy the text. Off by default while we tune the layout.",
                            isOn:  $settings.searchablePdfExportEnabled
                        )
                    }

                    section(title: "About") {
                        aboutRow
                    }
                }
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s8)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            QuickInkBottomNavBar(
                activeTab:  .settings,
                onHome:     onHome,
                onWorkspace:  onWorkspace,
                onScan:     onScan,
                onSearch:   onSearch,
                onSettings: { /* current tab */ }
            )
        }
    }

    /// Inline TextField for the user's preferred display name. Edits
    /// flow into `SettingsState.customDisplayName`, which `MainShell`
    /// observes for the Home greeting. Placeholder cues the fallback
    /// behaviour — empty here means the Google session's name wins.
    @ViewBuilder
    private var displayNameRow: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
            Text("Display name")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.ink)
            TextField(
                "Use Google account name",
                text: $settings.customDisplayName
            )
            .font(QuickInkText.body)
            .foregroundStyle(QuickInkColors.ink)
            .textFieldStyle(.plain)
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            Text("Shown on the home screen. Leave blank to use your Google account name.")
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Account section content — shows the signed-in display
    /// name + email when there's a session, plus a Sign out
    /// button. Sign out flips `AuthStore.state` to `.signedOut`,
    /// which `QuickInkRoot`'s router observes and bounces to the
    /// SignIn screen (Option A — see `QuickInkRoot.ReSignInGate`).
    @ViewBuilder
    private var accountRow: some View {
        let session: GoogleAuthSession? = {
            if case .signedIn(let s) = authStore.state { return s }
            return nil
        }()

        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
                Text(session?.displayName ?? "Signed in")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                Text(session?.email ?? "Not signed in")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }

            Spacer()

            if session != nil {
                Button(action: signOut) {
                    HStack(spacing: QuickInkSpacing.s1) {
                        Image(systemName: "rectangle.portrait.and.arrow.right")
                            .font(.system(size: 14))
                        Text("Sign out")
                            .font(QuickInkText.body)
                    }
                    .foregroundStyle(QuickInkColors.accentDeep)
                    .padding(QuickInkSpacing.s2)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Sign out")
            }
        }
    }

    private func signOut() {
        Task { await authStore.signOut() }
    }

    @ViewBuilder
    private var topBar: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Back")

            Text("Settings")
                .font(QuickInkText.pageTitle)
                .foregroundStyle(QuickInkColors.ink)

            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
    }

    @ViewBuilder
    private func section<Content: View>(
        title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text(title.uppercased())
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.inkSoft)

            VStack(spacing: QuickInkSpacing.s2) {
                content()
            }
            .padding(QuickInkSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(QuickInkColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        }
    }

    /// Row that pushes the Manage Categories screen. Mirrors the
    /// "Sync now / Restore" row's flat-button pattern but with a
    /// disclosure chevron to signal navigation.
    @ViewBuilder
    private func categoriesRow(onTap: @escaping () -> Void) -> some View {
        Button(action: onTap) {
            HStack {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
                    Text("Manage categories")
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.ink)
                    Text("Add, rename, or remove the tags shown when you scan.")
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .multilineTextAlignment(.leading)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(QuickInkColors.muted)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func toggleRow(label: String, help: String, isOn: Binding<Bool>) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
            Toggle(isOn: isOn) {
                Text(label)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
            }
            Text(help)
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.inkSoft)
        }
    }

    /// "Last synced" row inside the Sync section. Renders the
    /// most recent successful-sync timestamp from `SyncStateStore`,
    /// or "Never" on a fresh install before any pass has landed.
    /// When `syncState.state.pendingCount > 0`, surfaces a
    /// "N pending" chip — rows that failed the most recent pass
    /// and will retry on the next tick.
    @ViewBuilder
    private var lastSyncedRow: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Text("Last synced")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.ink)
            Spacer()
            if isSyncingFlash {
                // Inline progress + label so the user sees something
                // happen the instant they tap Sync now / Restore.
                ProgressView()
                    .controlSize(.small)
                    .tint(QuickInkColors.accent)
                Text("Syncing now…")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.accent)
            } else {
                if syncState.state.pendingCount > 0 {
                    Text("\(syncState.state.pendingCount) pending")
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.accent)
                        .padding(.trailing, QuickInkSpacing.s2)
                }
                Text(relativeSyncTimestamp(syncState.state.lastFullSyncAt) ?? "Never")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
        }
    }

    /// Optimistic "we're working on it" flash — kicks immediately on
    /// every Sync now / Restore tap so the user gets feedback even
    /// when the underlying pass throws silently (e.g. stub Drive
    /// client without a configured GIDClientID). Auto-reverts so the
    /// real `syncState`-driven row takes back over.
    private func flashSyncing() {
        isSyncingFlash = true
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            isSyncingFlash = false
        }
    }

    /// Mirror of HomeScreen's relative-time formatter: "moments ago"
    /// / "5m ago" / "2h ago" / "yesterday" / "3d ago" / "Apr 28".
    /// Nil / unparsable returns nil so the row falls back to "Never".
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

    /// Slice 4.2d — Sync now / Restore from Drive button row.
    /// Mirror of Releaf's `DriveSettingsSection` CTAs. Both call
    /// `requestImmediate`; sync is bidirectional, so a manual
    /// kick of the same worker handles both push (sync now) and
    /// pull (restore) — the labels just frame the intent. Taps
    /// on the signed-out state no-op because
    /// `QuickInkSyncEnvironment.scheduler.runOnce` short-circuits
    /// without a session.
    @ViewBuilder
    private var syncControlsRow: some View {
        let isSignedIn: Bool = {
            if case .signedIn = authStore.state { return true }
            return false
        }()
        HStack(spacing: QuickInkSpacing.s3) {
            AppButton("Sync now", variant: .secondary) {
                if isSignedIn {
                    QuickInkSyncEnvironment.shared.scheduler.requestImmediate()
                    flashSyncing()
                }
            }
            .frame(maxWidth: .infinity)

            AppButton("Restore from Drive", variant: .secondary) {
                // Distinct path from "Sync now" — kicks the
                // pull-only restore worker via SyncRepository.restore
                // instead of the bidirectional sync. Same
                // signed-in gate.
                if isSignedIn {
                    QuickInkSyncEnvironment.shared.requestRestore()
                    flashSyncing()
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    /// Drive folder link — opens the user's Drive in the system
    /// browser at a search query for "QuickInk", which lands them on
    /// the per-app folder created by `SyncRepository.ensureRootFolder`.
    /// Direct deep-link to a specific folder ID would need the ID
    /// from the manifest; until that round-trips through the UI,
    /// the search-based link is a low-friction stand-in.
    @ViewBuilder
    private var driveFolderRow: some View {
        Link(destination: driveFolderURL) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Open Drive folder")
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.ink)
                    Text("Browse your scans + notes on Google Drive.")
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.inkSoft)
                }
                Spacer()
                Image(systemName: "arrow.up.right.square")
                    .font(.system(size: 16))
                    .foregroundStyle(QuickInkColors.accent)
            }
            .contentShape(Rectangle())
        }
    }

    private var driveFolderURL: URL {
        // `?q=QuickInk` lands the user on a Drive search results
        // page filtered to items with "QuickInk" in the name —
        // close enough to "open the QuickInk folder" without
        // needing the actual folder ID. Authenticated users see
        // their own folder; signed-out users see Drive's sign-in
        // page first.
        URL(string: "https://drive.google.com/drive/u/0/search?q=QuickInk")!
    }

    /// "About" section content — app version + a brief blurb. The
    /// version string comes from `CFBundleShortVersionString` so it
    /// reflects whatever the Xcode app target's MARKETING_VERSION is.
    @ViewBuilder
    private var aboutRow: some View {
        let version = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "—"
        let build   = (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? "—"
        VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
            HStack {
                Text("App version")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                Spacer()
                Text("\(version) (\(build))")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            Text("QuickInk by Releaf — scans go to your own Google Drive folder. Nothing leaves the device until you sign in and turn Drive backup on.")
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.inkSoft)
        }
    }

    // MARK: - Appearance section

    /// Three-segment toggle for the user's theme override (System /
    /// Light / Dark). The active segment paints `accent` over
    /// `accentSoft`; inactive segments stay transparent on the
    /// section's white card. Bound directly to
    /// `settings.themeMode`; QuickInkRoot reads it on every render
    /// to apply `.preferredColorScheme(...)` to the whole tree.
    @ViewBuilder
    private var themeModeRow: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text("Theme")
                .font(QuickInkText.label)
                .foregroundStyle(QuickInkColors.ink)
            HStack(spacing: 4) {
                ForEach(ThemeMode.allCases, id: \.self) { mode in
                    let active = mode == settings.themeMode
                    Button(action: { settings.themeMode = mode }) {
                        Text(mode.displayName)
                            .font(QuickInkText.label)
                            .foregroundStyle(active ? QuickInkColors.textOnAccent : QuickInkColors.ink)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, QuickInkSpacing.s2)
                            .background(
                                RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                                    .fill(active ? QuickInkColors.accent : Color.clear)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(4)
            .background(
                RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                    .fill(QuickInkColors.borderSoft)
            )
        }
    }

    /// Row of swatch circles, one per `PrimaryColor`. The picked
    /// swatch gets a thicker ring; the others are flat discs. Each
    /// swatch displays the family's DEEP variant — that's the
    /// variant that lights up in light mode (where most users are),
    /// so the picker preview matches what you'll see on FAB / CTAs.
    @ViewBuilder
    private var primaryColorRow: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack {
                Text("Primary color")
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                Spacer()
                Text(settings.primaryColor.displayName)
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            HStack(spacing: QuickInkSpacing.s3) {
                ForEach(PrimaryColor.allCases, id: \.self) { hue in
                    let active = hue == settings.primaryColor
                    Button(action: { settings.primaryColor = hue }) {
                        Circle()
                            .fill(hue.deep)
                            .frame(width: 40, height: 40)
                            .overlay(
                                Circle().stroke(
                                    active ? hue.deep : QuickInkColors.border,
                                    lineWidth: active ? 3 : 1
                                )
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(hue.displayName))
                    .accessibilityAddTraits(active ? [.isSelected] : [])
                }
            }
        }
    }
}
