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

    @State private var showQuickCapture = false
    @StateObject private var notesVM: NotepadListViewModel
    @ObservedObject private var syncState = SyncStateStore.shared

    init(
        controller: ScanFlowController,
        userId: String,
        onOpenNotes: @escaping () -> Void,
        onOpenSettings: @escaping () -> Void,
        onOpenSearch: (() -> Void)? = nil,
        onTapCategory: ((String) -> Void)? = nil,
        onOpenEntry: ((String) -> Void)? = nil
    ) {
        self.controller = controller
        self.userId = userId
        self.onOpenNotes = onOpenNotes
        self.onOpenSettings = onOpenSettings
        self.onOpenSearch = onOpenSearch
        self.onTapCategory = onTapCategory
        self.onOpenEntry = onOpenEntry

        let repository = NotepadRepository(dbQueue: QuickInkDatabase.shared.dbQueue)
        _notesVM = StateObject(
            wrappedValue: NotepadListViewModel(
                repository: repository,
                userId:     userId
            )
        )
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                    headerBlock
                    searchBar
                    syncStatusPill
                    recentRail
                    categoryGrid
                    Spacer(minLength: 100) // Reserve space behind nav bar.
                }
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, QuickInkSpacing.s4)
            }
            .background(QuickInkColors.bg.ignoresSafeArea())

            bottomNavBar
        }
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task {
            // Open the FTS observation stream backing `entries`.
            notesVM.start()
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
        VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
            Text(greeting)
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.muted)
            Text("Quickink")
                .font(QuickInkText.display)
                .foregroundStyle(QuickInkColors.ink)
        }
    }

    /// Time-of-day-aware greeting. Pure UI — independent of any
    /// signed-in display name (we don't promise a "Hi Achal" because
    /// onboarding doesn't capture a friendly name).
    private var greeting: String {
        let hour = Calendar.current.component(.hour, from: Date())
        switch hour {
        case 5..<12:  return "Good morning"
        case 12..<18: return "Good afternoon"
        default:      return "Good evening"
        }
    }

    // MARK: - Search bar

    @ViewBuilder
    private var searchBar: some View {
        Button(action: { onOpenSearch?() }) {
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(QuickInkColors.muted)
                Text("Search notes & OCR text")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.muted)
                Spacer()
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.vertical, QuickInkSpacing.s3)
            .background(QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
        }
        .buttonStyle(.plain)
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
        return .synced(lastSyncAt: syncState.state.lastFullSyncAt)
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

            if notesVM.entries.isEmpty {
                emptyRecentRail
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: QuickInkSpacing.s3) {
                        ForEach(Array(notesVM.entries.prefix(8).enumerated()), id: \.element.id) { index, entry in
                            RecentNoteThumb(entry: entry, seed: index)
                                .onTapGesture { onOpenEntry?(entry.id) }
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

    private static let categories: [(name: String, icon: String)] = [
        ("Ideas",      "lightbulb"),
        ("Projects",   "folder"),
        ("Brainstorm", "sparkles"),
        ("Meetings",   "person.3"),
        ("Journal",    "book.closed"),
        ("Study",      "graduationcap"),
    ]

    @ViewBuilder
    private var categoryGrid: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            Text("CATEGORIES")
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)

            LazyVGrid(
                columns: [
                    GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                    GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                ],
                spacing: QuickInkSpacing.s3
            ) {
                ForEach(Self.categories, id: \.name) { cat in
                    CategoryTile(name: cat.name, icon: cat.icon, count: 0)
                        .onTapGesture { onTapCategory?(cat.name) }
                }
            }
        }
    }

    // MARK: - Bottom nav with Zap FAB

    @ViewBuilder
    private var bottomNavBar: some View {
        HStack(spacing: 0) {
            navIcon(systemName: "house.fill", label: "Home", active: true) { /* current screen */ }
            navIcon(systemName: "list.bullet.rectangle", label: "Library", active: false, action: onOpenNotes)
            zapFab
            navIcon(systemName: "magnifyingglass", label: "Search", active: false) { onOpenSearch?() }
            navIcon(systemName: "gearshape", label: "Settings", active: false, action: onOpenSettings)
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.top, QuickInkSpacing.s3)
        .padding(.bottom, QuickInkSpacing.s5)
        .background(
            QuickInkColors.surface
                .clipShape(
                    UnevenRoundedRectangle(
                        cornerRadii: .init(
                            topLeading: QuickInkRadius.lg,
                            bottomLeading: 0,
                            bottomTrailing: 0,
                            topTrailing: QuickInkRadius.lg
                        ),
                        style: .continuous
                    )
                )
                .shadow(color: QuickInkColors.ink.opacity(0.06), radius: 12, x: 0, y: -4)
        )
    }

    @ViewBuilder
    private func navIcon(systemName: String, label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: systemName)
                    .font(.system(size: 18, weight: active ? .semibold : .regular))
                    .foregroundStyle(active ? QuickInkColors.accent : QuickInkColors.muted)
                Text(label)
                    .font(QuickInkText.caption)
                    .foregroundStyle(active ? QuickInkColors.accent : QuickInkColors.muted)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }

    /// The signature ⚡ Zap FAB — center of the nav bar, raised.
    @ViewBuilder
    private var zapFab: some View {
        Button(action: { showQuickCapture = true }) {
            Image(systemName: "bolt.fill")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(QuickInkColors.textOnAccent)
                .frame(width: 56, height: 56)
                .background(
                    Circle().fill(QuickInkColors.accent)
                )
                .shadow(color: QuickInkColors.accent.opacity(0.4), radius: 10, x: 0, y: 4)
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
        .offset(y: -16) // Raised above the bar.
    }
}

// MARK: - Component: RecentNoteThumb

/// Small note thumbnail used in the home "Recent" horizontal rail.
/// Paper-toned background with lined-paper rule lines and a brief
/// handwritten preview (Caveat). Tap routes to the editor — wired
/// up at QuickInkRoot in a follow-up.
struct RecentNoteThumb: View {
    let entry: NotepadEntry
    let seed: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topLeading) {
                QuickInkLinedPaper(tone: QuickInkColors.paper(for: seed.hashValue), lineSpacing: 12, lineOpacity: 0.14)
                Text(handwrittenPreview)
                    .font(QuickInkFont.handwritten(16))
                    .foregroundStyle(QuickInkColors.ink.opacity(0.75))
                    .padding(QuickInkSpacing.s3)
                    .lineLimit(4)
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
                Text(entry.entryDate)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }
            .padding(.top, QuickInkSpacing.s2)
        }
        .frame(width: 140)
    }

    private var displayTitle: String {
        if let t = entry.title, !t.isEmpty { return t }
        return "Untitled"
    }

    private var handwrittenPreview: String {
        if !entry.notes.isEmpty {
            return String(entry.notes.prefix(50))
        }
        return displayTitle
    }
}

// MARK: - Component: CategoryTile

struct CategoryTile: View {
    let name: String
    let icon: String
    let count: Int

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            ZStack {
                Circle()
                    .fill(QuickInkColors.accentSoft)
                    .frame(width: 36, height: 36)
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(QuickInkColors.accent)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(QuickInkText.heading)
                    .foregroundStyle(QuickInkColors.ink)
                Text(count == 0 ? "No notes yet" : "\(count) note\(count == 1 ? "" : "s")")
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
}
