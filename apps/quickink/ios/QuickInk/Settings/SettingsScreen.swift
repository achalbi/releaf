/*
 * SettingsScreen.swift
 *
 * Slice 5 — two persisted toggles. Account row, theme override,
 * version info, etc. land in later slices alongside the auth
 * wiring + brand pass.
 *
 * Mirror of Android `SettingsScreen.kt`.
 */

import Combine
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

    init(
        onBack: @escaping () -> Void,
        authStore: AuthStore,
        settings: SettingsState,
        onManageCategories: (() -> Void)? = nil
    ) {
        self.onBack = onBack
        self.authStore = authStore
        self.settings = settings
        self.onManageCategories = onManageCategories
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

    /// Repository handle for the transcription-language allowlist.
    /// Wrapped in @State so SwiftUI keeps a single instance across
    /// re-renders; the type itself is reference-semantics (final
    /// class) so equality-by-identity is what we want.
    @State private var profileSettingsRepository = ProfileSettingsRepository()

    /// Local mirror of the user's picked transcription languages.
    /// Driven by the `task(id:)` subscription to
    /// `ProfileSettingsRepository.observe(userId)` so a remote
    /// update on another device propagates here on next launch.
    /// Empty (= "no preference set") falls back to
    /// `TranscriptionLanguages.defaultAllowlist()` for display.
    @State private var transcriptionCodes: Set<String> = []

    /// True once we've received the first emission from the
    /// observer — distinguishes "loaded an empty allowlist" from
    /// "still loading", so the picker doesn't flicker to defaults
    /// then back to the user's pick on first render.
    @State private var transcriptionDidLoad: Bool = false

    /// User id when signed in; nil otherwise. Drives the transcription-
    /// language picker's subscription target and write target — the
    /// row keys to `userId` so the same Apple ID across devices reads
    /// the same allowlist.
    private var signedInUserId: String? {
        if case .signedIn(let s) = authStore.state { return s.userId }
        return nil
    }

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

                    section(title: "Transcription") {
                        transcriptionLanguagesPicker
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
                        toggleRow(
                            label: "Public link sharing",
                            help:  "Lets the share sheet publish a story as a public web page. The backend service is in development — TestFlight users see a stubbed slug; the real link goes live when the server ships.",
                            isOn:  $settings.experimentalPublicLinksEnabled
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
        .task(id: signedInUserId ?? "") {
            // Subscribe to the picked-language allowlist while the
            // Settings screen is visible. Combine publisher → async
            // sequence keeps the subscription tied to SwiftUI's task
            // lifecycle so it auto-cancels on dismiss / userId change.
            guard let userId = signedInUserId else {
                transcriptionCodes = []
                transcriptionDidLoad = true
                return
            }
            transcriptionDidLoad = false
            for await row in profileSettingsRepository
                .observe(userId: userId)
                .replaceError(with: nil)
                .values
            {
                let parsed = TranscriptionLanguages.parse(row?.transcriptionLanguages)
                transcriptionCodes = Set(parsed.map(\.code))
                transcriptionDidLoad = true
            }
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

    /// Multi-select chip grid for the user's transcription-language
    /// allowlist. Mirror of Android's `TranscriptionLanguagesPicker`.
    /// Selected chips paint accent-filled; unselected paint outlined.
    /// Tap toggles in/out; the last remaining chip resists deselection
    /// (the LID pipeline needs ≥ 1 candidate).
    ///
    /// When signed-out, renders the chips muted and ignores taps —
    /// the user sees what the catalog looks like but writes don't
    /// land until they sign in (no userId to key the row by).
    @ViewBuilder
    private var transcriptionLanguagesPicker: some View {
        let isSignedIn = (signedInUserId != nil)
        // Resolved selection for display: parsed codes if the user
        // has saved a set, else the device-locale + English default
        // so first-time users see something sensible pre-checked.
        let displayCodes: Set<String> = {
            if !transcriptionDidLoad || transcriptionCodes.isEmpty {
                return Set(TranscriptionLanguages.defaultAllowlist().map(\.code))
            }
            return transcriptionCodes
        }()

        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text(
                "Pick the languages you'll speak in voice notes. " +
                "Recordings are transcribed in the matching language; " +
                "pick more than one for code-switching."
            )
            .font(QuickInkText.meta)
            .foregroundStyle(QuickInkColors.inkSoft)
            .fixedSize(horizontal: false, vertical: true)

            TranscriptionFlowLayout(spacing: QuickInkSpacing.s2) {
                ForEach(TranscriptionLanguages.supported) { language in
                    transcriptionLanguageChip(
                        language: language,
                        selected: displayCodes.contains(language.code),
                        enabled:  isSignedIn,
                        onTap:    { toggleTranscriptionLanguage(language) }
                    )
                }
            }

            if !isSignedIn {
                Text("Sign in to save your language preferences across devices.")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.muted)
            }
        }
    }

    @ViewBuilder
    private func transcriptionLanguageChip(
        language: TranscriptionLanguage,
        selected: Bool,
        enabled: Bool,
        onTap: @escaping () -> Void
    ) -> some View {
        let bg: Color = {
            if !enabled { return Color.white.opacity(0.45) }
            return selected ? QuickInkColors.accent : Color.white.opacity(0.85)
        }()
        let borderColor: Color = {
            if !enabled { return QuickInkColors.accent.opacity(0.15) }
            return selected ? QuickInkColors.accent : QuickInkColors.accent.opacity(0.25)
        }()
        let textColor: Color = {
            if !enabled { return QuickInkColors.muted }
            return selected ? QuickInkColors.textOnAccent : QuickInkColors.ink
        }()
        let nativeColor: Color = {
            if !enabled { return QuickInkColors.muted }
            return selected ? QuickInkColors.textOnAccent.opacity(0.85) : QuickInkColors.inkSoft
        }()
        let shape = Capsule(style: .continuous)
        Button(action: onTap) {
            VStack(alignment: .center, spacing: 2) {
                Text(language.englishName)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(textColor)
                if language.nativeName != language.englishName {
                    Text(language.nativeName)
                        .font(.system(size: 11))
                        .foregroundStyle(nativeColor)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(bg)
            .clipShape(shape)
            .overlay(shape.stroke(borderColor, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    /// Toggle the picked language in the local mirror and persist
    /// the new allowlist via `ProfileSettingsRepository`. The last
    /// remaining chip refuses to deselect — a zero-pick state would
    /// break the LID step downstream (caller would silently fall
    /// back to English, which is more surprising than the chip just
    /// refusing the tap).
    private func toggleTranscriptionLanguage(_ language: TranscriptionLanguage) {
        guard let userId = signedInUserId else { return }
        // Seed local mirror from defaults if the user hasn't picked
        // anything yet — first toggle becomes "default minus / plus
        // the tapped chip" rather than "only the tapped chip."
        var next: Set<String> = transcriptionCodes.isEmpty
            ? Set(TranscriptionLanguages.defaultAllowlist().map(\.code))
            : transcriptionCodes

        if next.contains(language.code) {
            if next.count <= 1 { return }
            next.remove(language.code)
        } else {
            next.insert(language.code)
        }
        transcriptionCodes = next
        // Preserve catalog order in the stored string so a reader
        // gets stable display order — `parse` will re-sort by
        // catalog anyway, but a consistent wire form is easier to
        // diff across devices.
        let ordered = TranscriptionLanguages.supported.filter { next.contains($0.code) }
        let encoded = TranscriptionLanguages.encode(ordered)
        Task {
            try? await profileSettingsRepository.setTranscriptionLanguages(
                userId: userId,
                codes:  encoded
            )
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

/// Minimal wrapping HStack — places each subview at its natural size
/// and wraps to a new row when the next item won't fit. Used by the
/// transcription-language picker and any future variable-width chip
/// grid that needs to live inside Settings (where the existing
/// `ChipFlowLayout` in `ScanReviewScreen.swift` is intentionally
/// scoped private to that file).
private struct TranscriptionFlowLayout: Layout {
    let spacing: CGFloat

    init(spacing: CGFloat = 8) {
        self.spacing = spacing
    }

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalHeight: CGFloat = 0
        var rowCount: Int = 0
        for sub in subviews {
            let size = sub.sizeThatFits(.unspecified)
            if rowWidth + size.width > maxWidth, rowWidth > 0 {
                totalHeight += rowHeight + spacing
                rowWidth = size.width
                rowHeight = size.height
                rowCount += 1
            } else {
                rowWidth += size.width + (rowWidth > 0 ? spacing : 0)
                rowHeight = max(rowHeight, size.height)
            }
        }
        if rowWidth > 0 || rowHeight > 0 {
            totalHeight += rowHeight
            rowCount += 1
        }
        return CGSize(width: proposal.width ?? maxWidth, height: totalHeight)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for sub in subviews {
            let size = sub.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                y += rowHeight + spacing
                x = bounds.minX
                rowHeight = 0
            }
            sub.place(
                at: CGPoint(x: x, y: y),
                proposal: ProposedViewSize(width: size.width, height: size.height)
            )
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
