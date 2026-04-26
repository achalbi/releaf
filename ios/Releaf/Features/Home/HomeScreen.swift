/*
 * HomeScreen.swift
 *
 * Signed-in Home. Keeps the existing greeting / onboarding / tasks
 * card, and appends two Room/GRDB-backed summary cards (Notebook +
 * Notepad) at the end — a compact dashboard view of what the user
 * has actually captured. The mid-screen raw notebook list from the
 * classic design is gone; the Notebook summary card covers that
 * affordance.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct HomeScreen: View {
    @EnvironmentObject private var authStore: AuthStore
    @Environment(\.showOnboardingWizard) private var showOnboarding
    @Environment(\.accentPalette) private var accent
    @StateObject private var viewModel: HomeDashboardViewModel
    @StateObject private var shelvesVM: ShelvesViewModel = ShelvesViewModel()

    private let userId: String
    private let onOpenNotebook: (String) -> Void
    private let onOpenNotebooksTab: () -> Void
    private let onOpenNotepadTab: () -> Void
    private let onOpenNotepadEntry: (String) -> Void
    private let onOpenContacts: () -> Void
    private let onOpenDrawer: () -> Void
    private let onOpenActivityLog: () -> Void

    public init(
        userId: String,
        onOpenNotebook: @escaping (String) -> Void = { _ in },
        onOpenNotebooksTab: @escaping () -> Void = {},
        onOpenNotepadTab: @escaping () -> Void = {},
        onOpenNotepadEntry: @escaping (String) -> Void = { _ in },
        onOpenContacts: @escaping () -> Void = {},
        onOpenDrawer: @escaping () -> Void = {},
        onOpenActivityLog: @escaping () -> Void = {}
    ) {
        self.userId = userId
        _viewModel = StateObject(wrappedValue: HomeDashboardViewModel(userId: userId))
        self.onOpenNotebook      = onOpenNotebook
        self.onOpenNotebooksTab  = onOpenNotebooksTab
        self.onOpenNotepadTab    = onOpenNotepadTab
        self.onOpenNotepadEntry  = onOpenNotepadEntry
        self.onOpenContacts      = onOpenContacts
        self.onOpenDrawer        = onOpenDrawer
        self.onOpenActivityLog   = onOpenActivityLog
    }

    public var body: some View {
        ZStack {
            DotGridBackground().ignoresSafeArea()
            content
        }
        .task {
            viewModel.start()
            shelvesVM.start()
        }
        .onDisappear {
            viewModel.stop()
            shelvesVM.stop()
        }
        .toolbar(.hidden, for: .navigationBar)
    }

    @ViewBuilder private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s6) {
                header

                // Animation stays on top
                TreesSavedHeroView(counts: viewModel.state.captureCounts)

                // Stats chip row hidden — the full cards below cover
                // the same actions with more context.
                // HomeActionChipsRow(
                //     onOpenTasks:     { /* TODO: onOpenTasks route */ },
                //     onOpenReminders: { /* TODO: onOpenReminders route */ },
                //     onOpenContacts:  onOpenContacts
                // )

                OnboardingQuickGuideCard(onShowIntro: showOnboarding)
                HomeTasksCard()
                HomeContactsCard(onOpenContacts: onOpenContacts)

                // New: combined library card + quick-capture pills + dummy timeline
                if viewModel.state.isLoading {
                    ProgressView()
                        .tint(AppColors.coral)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s6)
                } else {
                    VStack(spacing: AppSpacing.s4) {
                        librarySection
                        // Highlight card hidden per design feedback —
                        // Trees Saved hero + library card already cover
                        // the per-mode totals. Quick-capture routing
                        // moves to the center-tab FAB.
                        // HomeQuickCaptureSection(
                        //     counts: quickCaptureCounts,
                        //     onCapture: { _ in onOpenNotepadTab() }
                        // )
                        HomeTimelineCard(userId: userId, onSeeAll: onOpenActivityLog)
                    }
                }

                Spacer(minLength: AppSpacing.s10)
            }
            .padding(AppSpacing.s4)
        }
    }

    // MARK: - Header

    private var header: some View {
        // Leaf-glyph eyebrow + serif greeting. Avatar stays as the
        // drawer affordance on the left so navigation is discoverable
        // even with the chrome stripped down. No view toggle on Home —
        // there's no list/grid duality here; the dashboard cards are
        // editorially fixed.
        HStack(alignment: .top, spacing: AppSpacing.s3) {
            Button(action: onOpenDrawer) {
                AvatarCircle(initial: initialLetter)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Open menu")

            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                LeafEyebrow("releaf")
                Text(greeting)
                    .font(.system(size: 32, weight: .regular, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
            }

            Spacer(minLength: 0)
        }
    }

    private var initialLetter: String {
        let trimmed = (authStore.session?.displayName ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "?" : String(trimmed.prefix(1).uppercased())
    }

    private var greeting: String {
        if let name = authStore.session?.displayName, !name.isEmpty {
            return "Hi, \(name)"
        }
        return "Good morning"
    }

    // MARK: - Quick-capture totals

    /// Sums per-mode counts across notebook + notepad. Notebook-side
    /// photo / scan / voice / contact counts stay at 0 until the
    /// captures-table migration lands; notepad-side counts are real.
    private var quickCaptureCounts: [QuickCaptureMode: Int] {
        let s = viewModel.state
        let notebookPages     = s.captureCounts.notes      // pages stand in as notes
        let notebookPhotos    = s.captureCounts.photos     // 0 until captures table ships
        let notebookScans     = s.captureCounts.scans
        let notebookVoice     = s.captureCounts.voice
        return [
            .notes:    notebookPages + s.totalNotepadEntries,
            .photos:   notebookPhotos + s.totalNotepadPhotos,
            .scans:    notebookScans  + s.totalNotepadScans,
            .voice:    notebookVoice  + s.totalNotepadVoice,
            .todos:    s.openNotepadTodos,
            // Location count comes from notepad `locations` JSON; a
            // notebook-side count will be added when captures ship.
            .location: s.totalNotepadLocations,
        ]
    }

    // MARK: - Library section (combined notebooks + notepad)

    @ViewBuilder private var librarySection: some View {
        switch shelvesVM.state {
        case .loading:
            ProgressView()
                .tint(AppColors.coral)
                .frame(maxWidth: .infinity)
                .padding(.vertical, AppSpacing.s4)
        case .loaded(let loaded):
            HomeLibrarySection(
                notebooks:           loaded.notebooks,
                totalNotepadEntries: viewModel.state.totalNotepadEntries,
                totalNotepadPhotos:  viewModel.state.totalNotepadPhotos,
                openNotepadTodos:    viewModel.state.openNotepadTodos,
                todayNotepadCount:   viewModel.state.todayNotepadCount,
                onOpenNotebooks:     onOpenNotebooksTab,
                onOpenNotepad:       onOpenNotepadTab
            )
        }
    }
}

// MARK: - Avatar circle

private struct AvatarCircle: View {
    let initial: String

    var body: some View {
        Text(initial)
            .font(.system(size: 18, weight: .medium))
            .foregroundStyle(AppColors.onAccent)
            .frame(width: 44, height: 44)
            .background(Circle().fill(AppColors.coral))
            .overlay(
                Circle().stroke(AppColors.borderDefault, lineWidth: 0.5)
            )
    }
}

// MARK: - Drawer overlay (Canopy header · B)
//
// "B · Canopy header" — forest banner at top, cream menu list with
// colored leaf glyphs and per-row status metadata, dashed green stem
// dividers, earth-brown footer with grass blades. Matches the same
// treatment on Android (HomeScreen.kt).

private let canopyBg       = Color(red: 0x1E / 255.0, green: 0x59 / 255.0, blue: 0x43 / 255.0)
// Tree silhouette palette — mirrors TreesSavedHeroView's TreeGlyph
// so the drawer's forest banner reads as the same "world" as the
// hero card.
private let drawerTreeTop    = Color(red: 0x7A / 255.0, green: 0xA8 / 255.0, blue: 0x74 / 255.0)
private let drawerTreeMid    = Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 0x52 / 255.0)
private let drawerTreeBottom = Color(red: 0x3E / 255.0, green: 0x6B / 255.0, blue: 0x3B / 255.0)
private let drawerTrunk      = Color(red: 0x3E / 255.0, green: 0x2A / 255.0, blue: 0x18 / 255.0)
private let canopyCream    = Color(red: 0xFF / 255.0, green: 0xF8 / 255.0, blue: 0xEE / 255.0)
private let canopyStem     = Color(red: 0x7A / 255.0, green: 0xA8 / 255.0, blue: 0x74 / 255.0)
private let canopyEarth    = Color(red: 0x8B / 255.0, green: 0x73 / 255.0, blue: 0x55 / 255.0)
private let canopyGrass    = Color(red: 0x6F / 255.0, green: 0xA0 / 255.0, blue: 0x64 / 255.0)

// Yellow leaf avatar — light top → deep bottom, with a trunk-brown
// initial for high-contrast reading on the yellow surface.
private let leafYellowLight = Color(red: 0xF9 / 255.0, green: 0xDB / 255.0, blue: 0x7F / 255.0)
private let leafYellowDeep  = Color(red: 0xC8 / 255.0, green: 0x9B / 255.0, blue: 0x1A / 255.0)
private let leafInitialInk  = Color(red: 0x3E / 255.0, green: 0x2A / 255.0, blue: 0x18 / 255.0)
private let yellowLeafGradient = LinearGradient(
    colors: [leafYellowLight, leafYellowDeep],
    startPoint: .top,
    endPoint: .bottom
)

// Per-row leaf glyph colors — echo the Trees Saved hero palette so
// the drawer reads as the same little world.
private let leafTimeline   = Color(red: 0xE7 / 255.0, green: 0x78 / 255.0, blue: 0x50 / 255.0) // coral "golden hour"
private let leafLibrary    = Color(red: 0x7A / 255.0, green: 0xA8 / 255.0, blue: 0x74 / 255.0) // leaf green
private let leafNotepad    = Color(red: 0xF5 / 255.0, green: 0xC4 / 255.0, blue: 0xB3 / 255.0) // light coral
private let leafTasks      = Color(red: 0xF4 / 255.0, green: 0xC4 / 255.0, blue: 0x30 / 255.0) // dark yellow
private let leafReminders  = Color(red: 0xB8 / 255.0, green: 0x95 / 255.0, blue: 0x6A / 255.0) // dry
private let leafContacts   = Color(red: 0x3E / 255.0, green: 0x6B / 255.0, blue: 0x3B / 255.0) // deep green
private let leafSettings   = Color(red: 0xF2 / 255.0, green: 0xC9 / 255.0, blue: 0x4C / 255.0) // yellow

public struct HomeDrawerOverlay: View {
    let displayName: String
    let email: String
    let librarySubtitle: String
    let notepadSubtitle: String
    let tasksSubtitle: String
    let remindersSubtitle: String
    let contactsSubtitle: String
    let onClose: () -> Void
    let onSignOut: () -> Void

    public init(
        displayName: String,
        email: String,
        librarySubtitle: String   = "—",
        notepadSubtitle: String   = "—",
        tasksSubtitle: String     = "—",
        remindersSubtitle: String = "—",
        contactsSubtitle: String  = "—",
        onClose: @escaping () -> Void,
        onSignOut: @escaping () -> Void
    ) {
        self.displayName       = displayName
        self.email             = email
        self.librarySubtitle   = librarySubtitle
        self.notepadSubtitle   = notepadSubtitle
        self.tasksSubtitle     = tasksSubtitle
        self.remindersSubtitle = remindersSubtitle
        self.contactsSubtitle  = contactsSubtitle
        self.onClose           = onClose
        self.onSignOut         = onSignOut
    }

    // Read safe-area insets via UIApplication so the canopy can bleed
    // behind the status bar / notch and the earth footer can bleed
    // behind the home indicator, while the avatar / sign-out content
    // stays visually inside the safe area.
    private var topInset: CGFloat {
        UIApplication.shared
            .connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first(where: \.isKeyWindow)?
            .safeAreaInsets.top ?? 44
    }

    private var bottomInset: CGFloat {
        UIApplication.shared
            .connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first(where: \.isKeyWindow)?
            .safeAreaInsets.bottom ?? 34
    }

    public var body: some View {
        HStack(spacing: 0) {
            sheet
                .frame(maxWidth: 300)
                .frame(maxHeight: .infinity)
                .background(canopyCream)
            scrim
        }
        // Bleed into both safe areas — canopy reaches behind the
        // status bar / notch at the top, earth reaches behind the
        // tab bar / home indicator at the bottom. canopyHeader /
        // earthFooter re-inset their content via topInset / bottomInset.
        .edgesIgnoringSafeArea(.all)
    }

    private var scrim: some View {
        Color.black.opacity(0.35)
            .onTapGesture(perform: onClose)
    }

    private var sheet: some View {
        VStack(alignment: .leading, spacing: 0) {
            canopyHeader

            VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: AppSpacing.s3)
                DrawerItem(leafColor: leafTimeline,   label: "Timeline",  meta: "recent activity",        action: { /* TODO: route to activity log */ })
                DashedStemSeparator()
                DrawerItem(leafColor: leafLibrary,    label: "Library",   meta: librarySubtitle,           action: {})
                DashedStemSeparator()
                DrawerItem(leafColor: leafNotepad,    label: "Notepad",   meta: notepadSubtitle,           action: {})
                DashedStemSeparator()
                DrawerItem(leafColor: leafTasks,      label: "Tasks",     meta: tasksSubtitle,             action: {})
                DashedStemSeparator()
                DrawerItem(leafColor: leafReminders,  label: "Reminders", meta: remindersSubtitle,         action: {})
                DashedStemSeparator()
                DrawerItem(leafColor: leafContacts,   label: "Contacts",  meta: contactsSubtitle,          action: {})
                DashedStemSeparator()
                DrawerItem(leafColor: leafSettings,   label: "Settings",  meta: "theme · sync · account", action: {})
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer(minLength: 0)

            earthFooter
        }
    }

    // MARK: - Canopy header (forest banner)
    //
    // Overall height = 175pt + top safe-area inset. The deep-green bg
    // + tree Canvas fill the entire area so the forest reaches the
    // very top edge of the screen (behind the status bar / notch).
    // Avatar + name sit in the bottom 175pt via `topInset` padding,
    // so they remain safely below any system UI.

    private var canopyHeader: some View {
        ZStack(alignment: .leading) {
            canopyBg

            // Tree silhouettes drawn behind avatar/name — 4 stacked
            // 3-tier pines mirroring TreesSavedHeroView.TreeGlyph so
            // the banner lines up visually with the Home-tab hero.
            Canvas { ctx, size in
                let w = size.width
                let h = size.height
                let trees: [(cx: CGFloat, heightFrac: CGFloat)] = [
                    (0.12, 0.52),
                    (0.34, 0.56),
                    (0.60, 0.62),
                    (0.84, 0.78),
                ]
                for t in trees {
                    let treeH = h * t.heightFrac
                    let treeW = treeH * 0.8
                    let left  = w * t.cx - treeW / 2
                    let top   = h * 0.96 - treeH   // baseline near footer

                    func px(_ fx: CGFloat) -> CGFloat { left + treeW * fx }
                    func py(_ fy: CGFloat) -> CGFloat { top + treeH * fy }

                    var topCanopy = Path()
                    topCanopy.move(to: CGPoint(x: px(0.50), y: py(0.04)))
                    topCanopy.addLine(to: CGPoint(x: px(0.25), y: py(0.44)))
                    topCanopy.addLine(to: CGPoint(x: px(0.75), y: py(0.44)))
                    topCanopy.closeSubpath()
                    ctx.fill(topCanopy, with: .color(drawerTreeTop))

                    var midCanopy = Path()
                    midCanopy.move(to: CGPoint(x: px(0.50), y: py(0.28)))
                    midCanopy.addLine(to: CGPoint(x: px(0.15), y: py(0.68)))
                    midCanopy.addLine(to: CGPoint(x: px(0.85), y: py(0.68)))
                    midCanopy.closeSubpath()
                    ctx.fill(midCanopy, with: .color(drawerTreeMid))

                    var botCanopy = Path()
                    botCanopy.move(to: CGPoint(x: px(0.50), y: py(0.44)))
                    botCanopy.addLine(to: CGPoint(x: px(0.06), y: py(0.88)))
                    botCanopy.addLine(to: CGPoint(x: px(0.94), y: py(0.88)))
                    botCanopy.closeSubpath()
                    ctx.fill(botCanopy, with: .color(drawerTreeBottom))

                    let trunk = CGRect(
                        x: px(0.44),
                        y: py(0.88),
                        width: treeW * 0.12,
                        height: treeH * 0.12
                    )
                    ctx.fill(Path(trunk), with: .color(drawerTrunk))
                }
            }

            // Avatar + name — top-aligned under the top safe area,
            // with a little extra top breathing room.
            // Trees fill the rest of the 175pt canopy behind the row.
            HStack(alignment: .top, spacing: AppSpacing.s3) {
                Text(initialLetter)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(leafInitialInk)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(yellowLeafGradient))
                VStack(alignment: .leading, spacing: 2) {
                    Text(displayName.isEmpty ? "Guest" : displayName)
                        .font(AppText.sectionTitle)
                        .foregroundStyle(canopyCream)
                        .lineLimit(1)
                    if !email.isEmpty {
                        Text(email)
                            .font(AppText.meta)
                            .foregroundStyle(canopyCream.opacity(0.80))
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, AppSpacing.s5)
            // Push below the notch / status bar plus a little extra
            // breathing room so the row sits slightly down from the
            // very top of the canopy.
            .padding(.top, topInset + AppSpacing.s4)
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .frame(height: 175 + topInset)
        .clipped()
    }

    // MARK: - Earth-brown footer
    //
    // Overall height = 80pt + bottom safe-area inset. The brown bg +
    // grass-blade Canvas fill the entire area so the "earth" reaches
    // the bottom edge of the screen (behind the tab bar / home
    // indicator). The sign-out row lives in the top 80pt via
    // `bottomInset` padding so it stays above the tab bar.

    private var earthFooter: some View {
        Button(action: onSignOut) {
            ZStack(alignment: .top) {
                canopyEarth

                // Grass blades along top edge
                Canvas { ctx, size in
                    let w = size.width
                    let h = size.height
                    let blades = 22
                    for i in 0..<blades {
                        let x = (CGFloat(i) + 0.5) / CGFloat(blades) * w
                        let bladeH = h * (0.55 + CGFloat(i % 3) * 0.15)
                        var path = Path()
                        path.move(to: CGPoint(x: x - 1.5, y: h))
                        path.addQuadCurve(
                            to: CGPoint(x: x, y: h - bladeH),
                            control: CGPoint(x: x - 0.5, y: h - bladeH * 0.8)
                        )
                        path.addQuadCurve(
                            to: CGPoint(x: x + 1.5, y: h),
                            control: CGPoint(x: x + 0.5, y: h - bladeH * 0.8)
                        )
                        path.closeSubpath()
                        ctx.fill(path, with: .color(i % 2 == 0 ? canopyGrass : canopyStem))
                    }
                }
                .frame(height: 10)
                .frame(maxWidth: .infinity)

                HStack(spacing: AppSpacing.s3) {
                    Image(systemName: "arrow.right")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(canopyCream)
                        .frame(width: 20, height: 20)
                    Text("Sign out")
                        .font(AppText.body)
                        .foregroundStyle(canopyCream)
                    Spacer()
                }
                .padding(.horizontal, AppSpacing.s5)
                .padding(.top, 28)
                .padding(.bottom, bottomInset)
                .frame(maxHeight: .infinity, alignment: .top)
            }
            .frame(height: 80 + bottomInset)
        }
        .buttonStyle(.plain)
    }

    private var initialLetter: String {
        let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "?" : String(trimmed.prefix(1).uppercased())
    }
}

// MARK: - Drawer item (leaf glyph + label + metadata)

private struct DrawerItem: View {
    let leafColor: Color
    let label: String
    let meta: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: AppSpacing.s3) {
                LeafGlyph(color: leafColor)
                    .frame(width: 26, height: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textPrimary)
                    Text(meta)
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, AppSpacing.s5)
            .padding(.vertical, AppSpacing.s3)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Leaf glyph

private struct LeafGlyph: View {
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            var leaf = Path()
            leaf.move(to: CGPoint(x: w * 0.15, y: h * 0.85))
            leaf.addCurve(
                to: CGPoint(x: w * 0.85, y: h * 0.20),
                control1: CGPoint(x: w * 0.05, y: h * 0.55),
                control2: CGPoint(x: w * 0.35, y: h * 0.05)
            )
            leaf.addCurve(
                to: CGPoint(x: w * 0.15, y: h * 0.85),
                control1: CGPoint(x: w * 0.70, y: h * 0.55),
                control2: CGPoint(x: w * 0.50, y: h * 0.95)
            )
            leaf.closeSubpath()
            ctx.fill(leaf, with: .color(color))

            // Midrib
            var rib = Path()
            rib.move(to: CGPoint(x: w * 0.15, y: h * 0.85))
            rib.addLine(to: CGPoint(x: w * 0.80, y: h * 0.25))
            ctx.stroke(
                rib,
                with: .color(color.opacity(0.55)),
                style: StrokeStyle(lineWidth: 1.2, lineCap: .round)
            )
        }
    }
}

// MARK: - Dashed stem separator

private struct DashedStemSeparator: View {
    var body: some View {
        Canvas { ctx, size in
            let y = size.height / 2
            var line = Path()
            line.move(to: CGPoint(x: 0, y: y))
            line.addLine(to: CGPoint(x: size.width, y: y))
            ctx.stroke(
                line,
                with: .color(canopyStem.opacity(0.55)),
                style: StrokeStyle(
                    lineWidth: 1.2,
                    lineCap: .round,
                    dash: [6, 5]
                )
            )
        }
        .frame(height: 1)
        .padding(.horizontal, AppSpacing.s5)
    }
}

