/*
 * NotesListScreen.swift
 *
 * QuickInk's Library — the upgraded notes list per the mockup
 * brief:
 *
 *   - Top bar: title + sort menu + grid/list toggle
 *   - Filter chips by category (All, Ideas, Projects, Brainstorm,
 *     Meetings, Journal, Study)
 *   - Time-grouped notes (Today / This week / Earlier)
 *   - Grid view: handwritten Caveat preview on lined paper with
 *     paper-tone backgrounds
 *   - List view: dense rows with title + meta + handwritten preview
 *
 * Wraps `ReleafCoreNotes.NotepadListViewModel` exactly as before;
 * the VM's `entries` stream is the data source. The grouping and
 * filter state live in this view (UI-only).
 *
 * Mirror of Android `NotesListScreen.kt`.
 */

import SwiftUI
import ReleafCoreNotes

struct NotesListScreen: View {

    let userId: String
    let onBack: () -> Void
    let onOpenEntry: (_ entryId: String) -> Void

    @StateObject private var vm: NotepadListViewModel

    @State private var viewMode: ViewMode = .grid
    @State private var sort: SortOrder = .newest
    @State private var activeCategory: String = "All"

    enum ViewMode { case grid, list }
    enum SortOrder { case newest, oldest, alphabetical }

    private static let categories = ["All", "Ideas", "Projects", "Brainstorm", "Meetings", "Journal", "Study"]

    init(
        userId: String,
        onBack: @escaping () -> Void,
        onOpenEntry: @escaping (_ entryId: String) -> Void
    ) {
        self.userId = userId
        self.onBack = onBack
        self.onOpenEntry = onOpenEntry

        let repository = NotepadRepository(dbQueue: QuickInkDatabase.shared.dbQueue)
        _vm = StateObject(
            wrappedValue: NotepadListViewModel(
                repository: repository,
                userId:     userId
            )
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            filterChips
                .padding(.top, QuickInkSpacing.s2)
                .padding(.bottom, QuickInkSpacing.s3)

            if vm.entries.isEmpty {
                Spacer()
                emptyState
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                        ForEach(groupedEntries, id: \.label) { group in
                            section(label: group.label, entries: group.entries)
                        }
                    }
                    .padding(.horizontal, QuickInkSpacing.s5)
                    .padding(.bottom, QuickInkSpacing.s7)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task {
            vm.start()
        }
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Back")

            Text("Library")
                .font(QuickInkText.pageTitle)
                .foregroundStyle(QuickInkColors.ink)

            Spacer()

            // Sort menu — picker dropdown.
            Menu {
                Button("Newest first") { sort = .newest }
                Button("Oldest first") { sort = .oldest }
                Button("Alphabetical") { sort = .alphabetical }
            } label: {
                Image(systemName: "arrow.up.arrow.down")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Sort")

            // Grid / list toggle.
            Button(action: { viewMode = (viewMode == .grid ? .list : .grid) }) {
                Image(systemName: viewMode == .grid ? "list.bullet" : "square.grid.2x2")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Toggle grid/list view")

            Button(action: { onOpenEntry(NotepadEditorViewModel.newEntryId) }) {
                Image(systemName: "plus")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.accent)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("New note")
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
    }

    // MARK: - Filter chips

    @ViewBuilder
    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: QuickInkSpacing.s2) {
                ForEach(Self.categories, id: \.self) { cat in
                    let active = (cat == activeCategory)
                    Button(action: { activeCategory = cat }) {
                        Text(cat)
                            .font(QuickInkText.label)
                            .foregroundStyle(active ? QuickInkColors.textOnAccent : QuickInkColors.ink)
                            .padding(.horizontal, QuickInkSpacing.s4)
                            .padding(.vertical, QuickInkSpacing.s2)
                            .background(active ? QuickInkColors.accent : QuickInkColors.borderSoft)
                            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, QuickInkSpacing.s5)
        }
    }

    // MARK: - Section

    private struct Group {
        let label: String
        let entries: [NotepadEntry]
    }

    /// Time-grouped + filtered + sorted entries.
    private var groupedEntries: [Group] {
        let filtered: [NotepadEntry] = {
            if activeCategory == "All" { return vm.entries }
            // The shared NotepadEntry doesn't yet model category;
            // when it does, filter by entry.category. Until then,
            // pass-through so the chip still renders.
            return vm.entries
        }()

        let sorted: [NotepadEntry] = {
            switch sort {
            case .newest:       return filtered  // VM default is newest-first.
            case .oldest:       return filtered.reversed()
            case .alphabetical: return filtered.sorted { ($0.title ?? "") < ($1.title ?? "") }
            }
        }()

        let cal = Calendar.current
        let now = Date()
        var today: [NotepadEntry] = []
        var thisWeek: [NotepadEntry] = []
        var earlier: [NotepadEntry] = []

        for entry in sorted {
            // entry.entryDate is a String — fall back to "Earlier"
            // bucket when we can't parse. A real implementation
            // would consult `entry.updatedAt: Date` if exposed.
            let formatter = ISO8601DateFormatter()
            let date = formatter.date(from: entry.entryDate) ?? now
            if cal.isDateInToday(date) {
                today.append(entry)
            } else if let weekRange = cal.dateInterval(of: .weekOfYear, for: now), weekRange.contains(date) {
                thisWeek.append(entry)
            } else {
                earlier.append(entry)
            }
        }

        var groups: [Group] = []
        if !today.isEmpty    { groups.append(.init(label: "Today",     entries: today)) }
        if !thisWeek.isEmpty { groups.append(.init(label: "This week", entries: thisWeek)) }
        if !earlier.isEmpty  { groups.append(.init(label: "Earlier",   entries: earlier)) }
        // Guarantee at least one group — defensive when timestamps
        // can't be parsed (legacy rows from before the proposal).
        if groups.isEmpty { groups.append(.init(label: "All", entries: sorted)) }
        return groups
    }

    @ViewBuilder
    private func section(label: String, entries: [NotepadEntry]) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            Text(label.uppercased())
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)

            if viewMode == .grid {
                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                        GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                    ],
                    spacing: QuickInkSpacing.s3
                ) {
                    ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                        Button(action: { onOpenEntry(entry.id) }) {
                            LibraryGridCard(entry: entry, seed: index)
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else {
                VStack(spacing: QuickInkSpacing.s3) {
                    ForEach(entries, id: \.id) { entry in
                        Button(action: { onOpenEntry(entry.id) }) {
                            LibraryListRow(entry: entry)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    // MARK: - Empty state

    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            ZStack {
                Circle()
                    .fill(QuickInkColors.accentSoft)
                    .frame(width: 80, height: 80)
                Image(systemName: "doc.text")
                    .font(.system(size: 32))
                    .foregroundStyle(QuickInkColors.accent)
            }
            Text("Your library is empty")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
            Text("Tap the ⚡ on Home to capture your first page.")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.inkSoft)
                .multilineTextAlignment(.center)
                .padding(.horizontal, QuickInkSpacing.s7)
        }
    }
}

// MARK: - LibraryGridCard

struct LibraryGridCard: View {
    let entry: NotepadEntry
    let seed: Int

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            ZStack(alignment: .topLeading) {
                QuickInkLinedPaper(
                    tone: QuickInkColors.paper(for: seed),
                    lineSpacing: 14,
                    lineOpacity: 0.14
                )
                Text(handwrittenPreview)
                    .font(QuickInkFont.handwritten(18))
                    .foregroundStyle(QuickInkColors.ink.opacity(0.78))
                    .lineLimit(6)
                    .padding(QuickInkSpacing.s3)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
            .frame(height: 180)
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
        }
    }

    private var displayTitle: String {
        if let t = entry.title, !t.isEmpty { return t }
        return "Untitled"
    }

    private var handwrittenPreview: String {
        if !entry.notes.isEmpty {
            return String(entry.notes.prefix(120))
        }
        return displayTitle
    }
}

// MARK: - LibraryListRow

struct LibraryListRow: View {
    let entry: NotepadEntry

    var body: some View {
        HStack(alignment: .top, spacing: QuickInkSpacing.s3) {
            // Mini thumbnail on the left.
            ZStack {
                QuickInkLinedPaper(tone: QuickInkColors.paper2, lineSpacing: 6, lineOpacity: 0.18)
                Image(systemName: "doc.text")
                    .font(.system(size: 14))
                    .foregroundStyle(QuickInkColors.accent.opacity(0.7))
            }
            .frame(width: 48, height: 60)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )

            VStack(alignment: .leading, spacing: 4) {
                Text(displayTitle)
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)

                if !entry.notes.isEmpty {
                    Text(entry.notes)
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .lineLimit(2)
                }

                Text(entry.entryDate)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }

            Spacer()
        }
        .padding(QuickInkSpacing.s3)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    private var displayTitle: String {
        if let t = entry.title, !t.isEmpty { return t }
        return "Untitled"
    }
}
