/*
 * ProfileScreen.swift
 *
 * Profile editor reachable from the Home avatar's dropdown menu
 * (alongside "Sign out"). Editorial refresh — leads with the user's
 * punchline as headline, drops a 3-card stats row (Notes / Streak /
 * Tags), consolidates contact + identity fields into a single white
 * card, and pins live sync + last-scan timestamps to the footer so
 * the page does double-duty as a "where am I" status surface.
 *
 *   - Profile photo  — picked via `PhotosPicker`, copied into the
 *                      app's Documents directory as
 *                      `profile_photo.jpg`. The file:// URI is
 *                      persisted in `SettingsState.profilePhotoUri`
 *                      so the home avatar can render it on next
 *                      launch.
 *   - Phone number   — free-form string (no E.164 normalization);
 *                      cosmetic field for the user's reference.
 *   - Punchline      — one-line "personality punchline" the user
 *                      writes for themselves. Surfaced twice: once
 *                      as the italic serif tagline under the name
 *                      (display only, falls back to email when
 *                      empty), and once as an editable row in the
 *                      consolidated list. Same single source of
 *                      truth (`SettingsState.personalityPunchline`),
 *                      two postures.
 *
 * Stats and footer pull from live data:
 *
 *   - Notes  — count of active captures for the user.
 *   - Tags   — count of active categories for the user.
 *   - Streak — placeholder ("—"); a real streak needs a daily-active
 *              roll-up that doesn't yet exist (see TODO at call site).
 *   - Last synced — observed via `SyncStateStore.shared.state.lastFullSyncAt`.
 *   - Last scan   — `created_at` of the user's most recent capture.
 *
 * Mirror of Android `ProfileScreen.kt`.
 */

import SwiftUI
import PhotosUI
import ReleafCoreAuth
import ReleafCoreDesignSystem
import ReleafCoreSync

struct ProfileScreen: View {

    let onBack: () -> Void
    @ObservedObject var authStore: AuthStore
    @ObservedObject var settings: SettingsState

    /// Selected `PhotosPickerItem` from the gallery picker. Loading
    /// happens in `.onChange` — the `Data` is decoded to `UIImage`,
    /// then written to `profile_photo.jpg` in Documents and the URI
    /// saved to `settings.profilePhotoUri`. `nil` after the load
    /// completes so picking the same image twice still triggers the
    /// handler.
    @State private var pickedItem: PhotosPickerItem?

    /// Displayed UIImage — kept in @State so the avatar updates
    /// immediately on pick (without waiting for a SettingsState
    /// round-trip via UserDefaults).
    @State private var avatarImage: UIImage?

    /// Drives the action sheet that appears when the user taps the
    /// avatar — the choice between "Take Photo" and "Choose from
    /// Library". The two sub-flows are themselves driven by their
    /// own bools below, so the user can flow back to the chooser if
    /// they cancel one of them.
    @State private var showingSourceSheet = false
    /// Programmatic trigger for `.photosPicker(isPresented:)` — the
    /// gallery branch of the action sheet. Picking sets `pickedItem`
    /// the same way the previous direct-PhotosPicker did.
    @State private var showingPhotosPicker = false
    /// Camera capture branch — full-screen cover hosting
    /// `CameraPicker`, which wraps `UIImagePickerController` with the
    /// camera source. Requires `NSCameraUsageDescription` in the
    /// app's Info.plist (added alongside this change).
    @State private var showingCamera = false

    /// Rotating index into [PRESET_PUNCHLINES]. Seeded off the
    /// current minute so two simultaneous launches don't show the
    /// same line, then advanced every ~3.5s by the `.task` rotator
    /// while the screen is on. Used only when the user hasn't saved
    /// a custom punchline.
    @State private var rotatingPunchlineIndex: Int =
        Int((Date().timeIntervalSince1970 / 60).truncatingRemainder(dividingBy: Double(presetPunchlines.count)))

    /// Live data — Notes / Tags counts and the user's last-capture
    /// timestamp pulled straight from the same VM/store the rest of
    /// the app reads. `userId` resolves to "" when signed out; the
    /// VMs then publish empty arrays and the stats render their
    /// empty state, which is the right behaviour.
    @StateObject private var capturesVM: CaptureListViewModel
    @StateObject private var categoriesVM: CategoryListViewModel
    @ObservedObject private var syncState = SyncStateStore.shared

    init(
        onBack: @escaping () -> Void,
        authStore: AuthStore,
        settings: SettingsState
    ) {
        self.onBack = onBack
        self.authStore = authStore
        self.settings = settings
        let userId: String = {
            if case .signedIn(let s) = authStore.state { return s.userId }
            return ""
        }()
        _capturesVM   = StateObject(wrappedValue: CaptureListViewModel(userId: userId))
        _categoriesVM = StateObject(wrappedValue: CategoryListViewModel(userId: userId))
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                VStack(spacing: QuickInkSpacing.s4) {
                    identityBlock
                    statsRow
                    fieldsCard
                    syncFooter
                }
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s8)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task {
            avatarImage = ProfilePhotoStore.load(uri: settings.profilePhotoUri)
            capturesVM.start()
            categoriesVM.start()
        }
        // Re-keyed on whether the user has a custom punchline so the
        // rotator restarts when they clear their text and bails out
        // (immediately, not on the next 3s tick) the moment they
        // start typing one. Keeps the user's chosen line frozen on
        // screen — never overwritten mid-read by a preset.
        .task(id: hasCustomPunchline) {
            if hasCustomPunchline { return }
            // Cycle through the preset taglines while the screen is
            // composed. The task is auto-cancelled on disappear, so
            // the loop stops naturally — no explicit teardown needed.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                if Task.isCancelled { break }
                await MainActor.run {
                    rotatingPunchlineIndex =
                        (rotatingPunchlineIndex + 1) % Self.presetPunchlines.count
                }
            }
        }
        .onChange(of: pickedItem) { newItem in
            guard let newItem else { return }
            Task {
                guard let data = try? await newItem.loadTransferable(type: Data.self),
                      let image = UIImage(data: data) else { return }
                applyPickedImage(image)
                await MainActor.run { pickedItem = nil }
            }
        }
        // Bottom-drawer modal shown when the avatar is tapped —
        // splits the single old PhotosPicker tap target into the
        // two distinct sources users expect on this kind of
        // surface. Uses `.sheet` + `.presentationDetents` rather
        // than `.confirmationDialog` so the surface is styled to
        // match the rest of the screen (cream surface, rounded
        // rows, accent icons) instead of the system gray action
        // sheet. Cancelling either branch leaves the existing
        // avatar in place — nothing is dropped on the floor.
        .sheet(isPresented: $showingSourceSheet) {
            PhotoSourceSheet(
                onTakePhoto: {
                    showingSourceSheet = false
                    // Brief delay so the sheet finishes its
                    // dismiss transition before the camera
                    // fullScreenCover takes over — without it
                    // SwiftUI sometimes drops the second
                    // presentation on iOS 16 / 17.
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 250_000_000)
                        showingCamera = true
                    }
                },
                onChooseFromGallery: {
                    showingSourceSheet = false
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 250_000_000)
                        showingPhotosPicker = true
                    }
                },
                onCancel: { showingSourceSheet = false }
            )
            .presentationDetents([.height(280)])
            .presentationDragIndicator(.visible)
        }
        // Programmatic-trigger gallery picker — same `pickedItem`
        // binding the .onChange handler reads from, so the existing
        // load + save path is unchanged.
        .photosPicker(
            isPresented: $showingPhotosPicker,
            selection:    $pickedItem,
            matching:     .images,
            photoLibrary: .shared()
        )
        // Camera branch — UIKit `UIImagePickerController` wrapped in
        // a SwiftUI representable. Returns either an image (saved +
        // mirrored to `avatarImage` via `applyPickedImage`) or a
        // cancel.
        .fullScreenCover(isPresented: $showingCamera) {
            CameraPicker(
                onPicked: { image in
                    applyPickedImage(image)
                    showingCamera = false
                },
                onCancel: { showingCamera = false }
            )
            .ignoresSafeArea()
        }
    }

    /// Shared post-pick handler — used by both the gallery path and
    /// the camera path. Persists the JPEG to disk, refreshes the
    /// in-screen avatar, and updates `SettingsState` so the home
    /// avatar reflects the new photo on next render.
    private func applyPickedImage(_ image: UIImage) {
        let uri = ProfilePhotoStore.save(image: image)
        Task { @MainActor in
            avatarImage = image
            settings.profilePhotoUri = uri ?? ""
        }
    }

    // MARK: - Top bar

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

            Text("Profile")
                .font(QuickInkText.pageTitle)
                .foregroundStyle(QuickInkColors.ink)

            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
    }

    // MARK: - Identity (avatar, name, punchline-as-headline, pill)

    /// Top identity block: avatar with edit affordance, name in
    /// serif, and either the punchline (italic serif, when set) or
    /// the email (muted sans, fallback) underneath. A single "Local
    /// only" pill grounds the privacy story of the page.
    @ViewBuilder
    private var identityBlock: some View {
        let punchline = settings.personalityPunchline.trimmingCharacters(in: .whitespacesAndNewlines)

        VStack(spacing: QuickInkSpacing.s2) {
            // Avatar — tap opens an action sheet that lets the user
            // pick between Take Photo (camera) and Choose from
            // Library (gallery). The sheet itself is attached at the
            // root of `body` via `.confirmationDialog`.
            Button {
                showingSourceSheet = true
            } label: {
                ZStack(alignment: .bottomTrailing) {
                    Circle()
                        .fill(QuickInkColors.accentSoft)
                        .frame(width: 112, height: 112)
                        .overlay(
                            Group {
                                if let img = avatarImage {
                                    Image(uiImage: img)
                                        .resizable()
                                        .scaledToFill()
                                        .clipShape(Circle())
                                } else if let initial = displayNameInitial {
                                    Text(initial)
                                        // Cormorant Light at the avatar size —
                                        // the airy didone hairlines feel right
                                        // inside the framed initial circle.
                                        .font(QuickInkFont.serif(48, weight: .light))
                                        .foregroundStyle(QuickInkColors.accent)
                                } else {
                                    Image(systemName: "person.crop.circle.fill")
                                        .font(.system(size: 72))
                                        .foregroundStyle(QuickInkColors.accent)
                                }
                            }
                        )
                        .overlay(
                            Circle().stroke(QuickInkColors.border, lineWidth: 1)
                        )

                    // Coral camera badge sits over the avatar's
                    // bottom-right. The 2pt paper-coloured ring
                    // punches it visually off the avatar so it reads
                    // as an "edit" affordance.
                    ZStack {
                        Circle()
                            .fill(QuickInkColors.accent)
                            .frame(width: 32, height: 32)
                            .overlay(
                                Circle().stroke(QuickInkColors.bg, lineWidth: 2)
                            )
                        Image(systemName: "camera.fill")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(QuickInkColors.textOnAccent)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Change profile photo")

            Spacer().frame(height: QuickInkSpacing.s2)

            Text(resolvedDisplayName)
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)

            // Tagline slot — punchline (italic serif). User's own
            // punchline wins when set; otherwise the slot cycles
            // through `presetPunchlines` every few seconds (driven
            // by the `.task` rotator) so the page reads as a "mood"
            // line instead of a static empty state. Animated with a
            // soft fade keyed on the active line.
            let tagline: String = !punchline.isEmpty
                ? "\u{201C}\(punchline)\u{201D}"
                : "\u{201C}\(Self.presetPunchlines[rotatingPunchlineIndex])\u{201D}"
            Text(tagline)
                .font(QuickInkText.bodyItalic)
                .foregroundStyle(QuickInkColors.inkSoft)
                .multilineTextAlignment(.center)
                .padding(.horizontal, QuickInkSpacing.s5)
                .id(tagline)
                .transition(.opacity)
                .animation(.easeInOut(duration: 0.6), value: tagline)

            Spacer().frame(height: QuickInkSpacing.s1)

            // Local-only pill — the field helper text used to
            // repeat "saved on this device" twice; consolidating
            // that promise up here lets the field rows stay terse.
            Text("Local only")
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.inkSoft)
                .padding(.horizontal, QuickInkSpacing.s3)
                .padding(.vertical, QuickInkSpacing.s1)
                .background(QuickInkColors.borderSoft)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
        }
        .frame(maxWidth: .infinity)
        .padding(.top, QuickInkSpacing.s3)
    }

    private var resolvedDisplayName: String {
        let custom = settings.customDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !custom.isEmpty { return custom }
        if case .signedIn(let s) = authStore.state {
            let google = s.displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !google.isEmpty { return google }
        }
        return "QuickInk"
    }

    /// `true` when the user has saved their own punchline. Drives both
    /// the tagline render branch and the rotator's `task(id:)` key so
    /// the preset cycle freezes the moment the user types theirs.
    private var hasCustomPunchline: Bool {
        !settings.personalityPunchline
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .isEmpty
    }

    private var displayNameInitial: String? {
        guard let first = resolvedDisplayName.first else { return nil }
        return String(first).uppercased()
    }

    // MARK: - Stats

    /// 3-up stats row: Notes / Streak / Tags. Streak renders the
    /// muted em-dash empty state until the daily-active roll-up
    /// exists — see TODO below. A literal "0" would imply "we
    /// measured this and you have none of it"; the dash says "we
    /// haven't computed it yet".
    @ViewBuilder
    private var statsRow: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            // TODO(streak): derive consecutive-days-with-captures.
            statCard(label: "NOTES",  value: "\(capturesVM.captures.count)")
            statCard(label: "STREAK", value: nil)
            statCard(label: "TAGS",   value: "\(categoriesVM.categories.count)")
        }
    }

    @ViewBuilder
    private func statCard(label: String, value: String?) -> some View {
        VStack(spacing: QuickInkSpacing.s1) {
            Text(value ?? "—")
                .font(QuickInkText.heading)
                .foregroundStyle(value != nil ? QuickInkColors.ink : QuickInkColors.muted)
            Text(label)
                .font(QuickInkText.caption)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, QuickInkSpacing.s3)
        .padding(.horizontal, QuickInkSpacing.s2)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    // MARK: - Field list

    /// Single white card hosting the editable + read-only profile
    /// fields. Each row carries its own divider; the last row drops
    /// it. Inputs stay inline (no modal sheets) so the page is never
    /// more than one tap from edit mode.
    @ViewBuilder
    private var fieldsCard: some View {
        let session: GoogleAuthSession? = {
            if case .signedIn(let s) = authStore.state { return s }
            return nil
        }()
        VStack(spacing: 0) {
            readonlyRow(label: "Email", value: session?.email ?? "Not signed in", trailing: "Read-only")
            rowDivider()
            editableRow(
                label:       "Phone",
                placeholder: "Add a phone number",
                text:        $settings.phoneNumber,
                keyboard:    .phonePad,
                multiline:   false
            )
            rowDivider()
            editableRow(
                label:       "Punchline",
                placeholder: "e.g. \"Curious by default, dangerous with a marker\"",
                text:        $settings.personalityPunchline,
                keyboard:    .default,
                multiline:   true
            )
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    @ViewBuilder
    private func readonlyRow(label: String, value: String, trailing: String) -> some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
                Text(value)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
            }
            Spacer()
            Text(trailing)
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.muted)
        }
        .padding(.vertical, QuickInkSpacing.s3)
    }

    @ViewBuilder
    private func editableRow(
        label: String,
        placeholder: String,
        text: Binding<String>,
        keyboard: UIKeyboardType,
        multiline: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
            Text(label)
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.muted)
            if multiline {
                TextField(placeholder, text: text, axis: .vertical)
                    .lineLimit(1...4)
                    .keyboardType(keyboard)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                    .textFieldStyle(.plain)
            } else {
                TextField(placeholder, text: text)
                    .keyboardType(keyboard)
                    .textContentType(keyboard == .phonePad ? .telephoneNumber : .none)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                    .textFieldStyle(.plain)
            }
        }
        .padding(.vertical, QuickInkSpacing.s3)
    }

    @ViewBuilder
    private func rowDivider() -> some View {
        Rectangle()
            .fill(QuickInkColors.border)
            .frame(height: 1)
    }

    // MARK: - Sync footer

    /// Two-row status footer. Each row: small dot + label + relative
    /// timestamp. The sync dot lights green when there's *any* sync
    /// record (data has reached the cloud at least once); the scan
    /// dot stays neutral — it's informational, not a freshness
    /// signal.
    @ViewBuilder
    private var syncFooter: some View {
        let lastSyncIso = syncState.state.lastFullSyncAt
        let lastScanIso = capturesVM.captures.first?.createdAt
        VStack(spacing: QuickInkSpacing.s1) {
            statusRow(
                label:    "Last synced",
                value:    relativeTimestamp(lastSyncIso) ?? "Never",
                dotColor: lastSyncIso != nil ? QuickInkColors.success : QuickInkColors.muted
            )
            statusRow(
                label:    "Last scan",
                value:    relativeTimestamp(lastScanIso) ?? "No scans yet",
                dotColor: QuickInkColors.muted
            )
        }
        .padding(.horizontal, QuickInkSpacing.s2)
    }

    @ViewBuilder
    private func statusRow(label: String, value: String, dotColor: Color) -> some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Circle()
                .fill(dotColor)
                .frame(width: 6, height: 6)
            Text(label)
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.muted)
            Spacer()
            Text(value)
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.inkSoft)
        }
    }

    /// Mirror of `relativeSyncTimestamp` from HomeScreen — duplicated
    /// locally rather than promoted to a util because the sites
    /// might want to diverge (different "Never" / threshold choices)
    /// and a one-liner that copy-pastes is cheaper than the
    /// indirection. If a fourth caller appears, lift this to
    /// `QuickInk/Util/RelativeTime.swift`.
    private func relativeTimestamp(_ iso: String?) -> String? {
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
        case 0..<60:            return "moments ago"
        case 60..<3600:         return "\(seconds / 60)m ago"
        case 3600..<86_400:     return "\(seconds / 3600)h ago"
        case 86_400..<172_800:  return "yesterday"
        case 172_800..<604_800: return "\(seconds / 86_400)d ago"
        default:
            let formatter = DateFormatter()
            formatter.dateFormat = "MMM d"
            return formatter.string(from: date)
        }
    }

    /// 30 stand-in punchlines that rotate in the tagline slot when
    /// the user hasn't written their own. Editorial / writerly tone —
    /// leans into QuickInk's ink + paper identity. Order matters:
    /// the first entry is the same example shown in the empty-state
    /// placeholder so a new user sees a familiar line on first paint.
    /// Mirror of Android `PRESET_PUNCHLINES`.
    static let presetPunchlines: [String] = [
        "Curious by default, dangerous with a marker",
        "Half the ideas, twice the speed",
        "Margins-of-the-page energy",
        "Notes today, novels someday",
        "Future me, you owe present me",
        "Caffeinated and slightly italicized",
        "Reads dictionaries for fun, no apologies",
        "Built this thought in 3 cups of coffee",
        "Permanent draft, occasional masterpiece",
        "Underlined twice, still not sure",
        "Made of footnotes and good intentions",
        "More ink than free time",
        "Currently overthinking a sticky note",
        "Procrastinator with a five-year plan",
        "Lost the pen but found the point",
        "One bullet point away from genius",
        "Lives in the margins, dreams in serif",
        "Allergic to blank pages",
        "Notes it down, then forgets where",
        "Filed under: future-me's problem",
        "Reading between the lines I drew",
        "Half-finished thoughts, fully committed",
        "Stationery aficionado, deadline survivor",
        "Will scan it. Eventually. Probably.",
        "More post-its than personality",
        "Born to underline, forced to highlight",
        "Indexed but not organized",
        "Wrote it down so I could forget it safely",
        "Pen-first thinker, rules-second",
        "Drafting my way through the day",
    ]
}

// MARK: - ProfilePhotoStore

/// Tiny disk helper for the user's chosen profile photo. The image
/// is JPEG-encoded and written to a single fixed path in the app's
/// Documents directory; subsequent picks overwrite. Returns the
/// `file://` URI string for persistence in `SettingsState`.
enum ProfilePhotoStore {

    private static let filename = "profile_photo.jpg"

    private static var documentsURL: URL? {
        FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)
            .first
    }

    /// JPEG-encode and overwrite the on-disk profile photo. Returns
    /// the `file://` URI string on success, `nil` if the directory
    /// is unavailable (sandbox-locked simulator runs) or encoding
    /// failed.
    static func save(image: UIImage) -> String? {
        guard let dir = documentsURL else { return nil }
        guard let data = image.jpegData(compressionQuality: 0.85) else { return nil }
        let target = dir.appendingPathComponent(filename)
        do {
            try data.write(to: target, options: .atomic)
            return target.absoluteString
        } catch {
            return nil
        }
    }

    /// Load the persisted photo as a `UIImage`. Returns `nil` when
    /// the URI is empty or the file is missing — callers fall back
    /// to the initial / glyph avatar.
    static func load(uri: String) -> UIImage? {
        guard !uri.isEmpty else { return nil }
        let path: String? = {
            if let url = URL(string: uri), url.isFileURL { return url.path }
            return uri
        }()
        guard let path else { return nil }
        return UIImage(contentsOfFile: path)
    }
}

// MARK: - PhotoSourceSheet

/// Bottom-drawer chooser used by the Profile screen's avatar tap.
/// Two big editorial rows — Take Photo (camera) and Choose from
/// Library (gallery) — over a small Cancel affordance. Hosted in a
/// `.sheet` so iOS gives us the swipe-to-dismiss + drag-indicator
/// chrome for free, with `.presentationDetents` clamping it to a
/// short modal anchored at the bottom of the screen.
private struct PhotoSourceSheet: View {
    let onTakePhoto: () -> Void
    let onChooseFromGallery: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            Text("Update profile photo")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
                .padding(.top, QuickInkSpacing.s4)

            VStack(spacing: QuickInkSpacing.s2) {
                row(icon: "camera.fill",         label: "Take Photo",         action: onTakePhoto)
                row(icon: "photo.on.rectangle",  label: "Choose from Library", action: onChooseFromGallery)
            }

            Button(action: onCancel) {
                Text("Cancel")
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.muted)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, QuickInkSpacing.s2)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.bottom, QuickInkSpacing.s5)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        // Cream-on-cream so the sheet matches the rest of the
        // Profile screen instead of using the system's default
        // sheet surface. `.presentationBackground` would do this at
        // the sheet level but is iOS 16.4+; this fills the content
        // bounds, which is enough to cover the visible sheet area
        // at our pinned detent.
        .background(QuickInkColors.bg)
    }

    @ViewBuilder
    private func row(icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: QuickInkSpacing.s3) {
                ZStack {
                    Circle()
                        .fill(QuickInkColors.accentSoft)
                        .frame(width: 36, height: 36)
                    Image(systemName: icon)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(QuickInkColors.accent)
                }
                Text(label)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                Spacer()
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.vertical, QuickInkSpacing.s3)
            .background(QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - CameraPicker

/// Thin SwiftUI wrapper around `UIImagePickerController` configured
/// for `.camera`. The PhotosPicker we use for the gallery branch can
/// only read existing assets, so we still need UIKit's classic
/// picker for capture. Uses the rear camera by default (front camera
/// is preferred for selfies but not all devices have one — falling
/// back to rear is safer than crashing). The capture screen handles
/// the actual front/rear flip itself.
///
/// `NSCameraUsageDescription` must be set in the host Info.plist or
/// the OS terminates the app on first capture attempt.
struct CameraPicker: UIViewControllerRepresentable {
    let onPicked: (UIImage) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        // Belt-and-braces: if the simulator (or a cameraless device)
        // doesn't expose `.camera`, fall back to the photo library
        // so the surface still works in DEBUG / Simulator. Production
        // devices always have a camera available.
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera)
            ? .camera : .photoLibrary
        picker.allowsEditing = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPicked: onPicked, onCancel: onCancel)
    }

    final class Coordinator: NSObject,
                             UIImagePickerControllerDelegate,
                             UINavigationControllerDelegate {
        let onPicked: (UIImage) -> Void
        let onCancel: () -> Void

        init(onPicked: @escaping (UIImage) -> Void, onCancel: @escaping () -> Void) {
            self.onPicked = onPicked
            self.onCancel = onCancel
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            // `.editedImage` is nil because `allowsEditing = false`;
            // `.originalImage` carries the captured frame.
            if let image = info[.originalImage] as? UIImage {
                onPicked(image)
            } else {
                onCancel()
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onCancel()
        }
    }
}
