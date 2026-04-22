/*
 * NotepadView.swift
 *
 * Top-level Notepad tab — lists the signed-in user's entries and surfaces
 * a coral "new entry" FAB. Mirror of Android's NotepadScreen:
 *   - Date-grouped cards (`entry_date` headers) when not searching
 *   - Flat rank-ordered cards when a search query is active
 *   - Swipe-to-delete on each row with a 4s Undo toast
 *
 * Composition: `NotepadView` reads the signed-in user from the environment
 * AuthStore and hands the id to a private inner view that owns the
 * `NotepadListViewModel`. Splitting the two lets the VM's `@StateObject`
 * capture a real user id at init time instead of a placeholder.
 *
 * Navigation: the editor destination is registered inside this view so
 * `NotepadEditorRoute` pushes land on the Notepad tab's stack regardless
 * of whether the caller is the FAB or a row tap.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct NotepadView: View {
    @EnvironmentObject private var authStore: AuthStore

    public init() {}

    public var body: some View {
        Group {
            if let session = authStore.session {
                NotepadListContent(
                    userId: session.userId,
                    repository: NotepadRepository()
                )
            } else {
                // MainShell already gates on signed-in, but guard anyway
                // so the preview/unsigned-in path lands on a safe state.
                NotepadListUnavailableView()
            }
        }
        // Dot-grid canvas behind the whole Notepad tab so the entry
        // cards float on the same textured paper the editor uses —
        // matches the Releaf Branding template's "Recent Entries"
        // background.
        .background(DotGridBackground().ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }
}

// MARK: - Inner content (owns the VM)

private struct NotepadListContent: View {
    @StateObject private var vm: NotepadListViewModel

    init(userId: String, repository: NotepadRepository) {
        _vm = StateObject(wrappedValue: NotepadListViewModel(
            repository: repository,
            userId: userId
        ))
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(spacing: 0) {
                header
                content
            }

            composeFab

            // Undo snackbar — bottom-centered, floats above the FAB's
            // row by sitting in a separate ZStack layer.
            //
            // Vertical clearance: FAB is 56pt tall + 16pt bottom pad = 72pt
            // from the container bottom. Pushing the toast 88pt up leaves
            // a 16pt gap above the FAB's top edge so they don't collide.
            if let toast = vm.toast {
                UndoToastView(
                    onUndo: { vm.undoDelete(toast.entryId) },
                    onDismiss: { vm.dismissToast() }
                )
                .padding(.horizontal, AppSpacing.s4)
                .padding(.bottom, 88)
                .frame(maxWidth: .infinity, alignment: .bottom)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .zIndex(1)
            }
        }
        .animation(.easeInOut(duration: 0.18), value: vm.toast)
        .onAppear { vm.start() }
    }

    // MARK: Header (eyebrow, title, search)

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            Text("NOTEPAD")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coral)

            Text("Quick scratch")
                .font(AppText.editorialTitle)
                .foregroundStyle(AppColors.textPrimary)

            SearchField(
                query: Binding(get: { vm.query }, set: { vm.query = $0 }),
                onClear: { vm.clearQuery() }
            )
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.top, AppSpacing.s4)
        .padding(.bottom, AppSpacing.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Content variants (empty / search-empty / list)

    @ViewBuilder
    private var content: some View {
        let trimmedQuery = vm.query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !vm.entries.isEmpty {
            EntryList(
                entries: vm.entries,
                grouped: trimmedQuery.isEmpty,
                onDelete: { vm.softDelete($0) }
            )
        } else if !trimmedQuery.isEmpty {
            EmptyStateView(
                title: "No matches",
                subtitle: "Nothing in your notepad matches \u{201C}\(vm.query)\u{201D}."
            )
        } else {
            EmptyStateView(
                title: "Nothing here yet",
                subtitle: "Tap the + button to jot something down."
            )
        }
    }

    // MARK: Compose FAB

    private var composeFab: some View {
        NavigationLink(value: NotepadEditorRoute(entryId: NotepadEditorViewModel.newEntryId)) {
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(AppColors.onAccent)
                .frame(width: 56, height: 56)
                .background(AppColors.coral)
                .clipShape(Circle())
                .shadow(color: .black.opacity(0.18), radius: 6, x: 0, y: 3)
        }
        .accessibilityLabel("New entry")
        .padding(.trailing, AppSpacing.s4)
        .padding(.bottom, AppSpacing.s4)
    }
}

// MARK: - Unavailable (no signed-in user)

private struct NotepadListUnavailableView: View {
    var body: some View {
        VStack(spacing: AppSpacing.s2) {
            Text("Sign in to use the notepad")
                .font(AppText.sectionTitle)
                .foregroundStyle(AppColors.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(AppSpacing.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Search field

private struct SearchField: View {
    @Binding var query: String
    let onClear: () -> Void

    var body: some View {
        HStack(spacing: AppSpacing.s2) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .regular))
                .foregroundStyle(AppColors.textTertiary)

            TextField("Search notes…", text: $query)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)

            if !query.isEmpty {
                Button(action: onClear) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(AppColors.textTertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.vertical, AppSpacing.s3)
        .background(AppColors.cardSolid)
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.pill, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.pill, style: .continuous))
    }
}

// MARK: - Entry list

private struct EntryList: View {
    let entries: [NotepadEntry]
    /// True when the list should show sticky date-group headers. False
    /// during search, where rank order is more useful than date order.
    let grouped: Bool
    let onDelete: (NotepadEntry) -> Void

    var body: some View {
        List {
            if grouped {
                ForEach(groupedSections, id: \.date) { section in
                    Section(header: DateHeader(isoDate: section.date)) {
                        ForEach(section.entries, id: \.id) { entry in
                            EntryRow(entry: entry, onDelete: onDelete)
                        }
                    }
                }
            } else {
                ForEach(entries, id: \.id) { entry in
                    EntryRow(entry: entry, onDelete: onDelete)
                }
            }
            // Bottom clearance so the FAB doesn't visually overlap the
            // last row on short lists.
            Color.clear
                .frame(height: AppSpacing.s10)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        // Clear so the dot-grid canvas on the parent `NotepadView`
        // reads through between cards. Sticky date headers keep their
        // own opaque canvas background so they don't bleed onto rows
        // scrolling underneath.
        .background(Color.clear)
    }

    /// Groups entries by entry_date, preserves the repo's
    /// updated_at-desc order within a group, and sorts groups by date DESC
    /// (ISO strings sort lexically = chronologically).
    private var groupedSections: [(date: String, entries: [NotepadEntry])] {
        var buckets: [String: [NotepadEntry]] = [:]
        var dateOrder: [String] = []
        for entry in entries {
            if buckets[entry.entryDate] == nil {
                buckets[entry.entryDate] = []
                dateOrder.append(entry.entryDate)
            }
            buckets[entry.entryDate]?.append(entry)
        }
        // Sort dates DESC. The insertion order above came from the
        // repo-sorted entries, but if two dates interleave we want the
        // more recent one on top regardless.
        let sortedDates = dateOrder.sorted(by: >)
        return sortedDates.map { ($0, buckets[$0] ?? []) }
    }
}

// MARK: - Entry row

private struct EntryRow: View {
    let entry: NotepadEntry
    let onDelete: (NotepadEntry) -> Void

    var body: some View {
        NavigationLink(value: NotepadEditorRoute(entryId: entry.id)) {
            EntryCardBody(entry: entry)
        }
        // iOS's built-in swipe actions give the slide-reveal + threshold +
        // haptics for free. `allowsFullSwipe: true` matches Android's
        // one-swipe-commits behavior.
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) {
                onDelete(entry)
            } label: {
                Label("Delete", systemImage: "trash")
            }
            .tint(AppColors.danger)
        }
        .listRowInsets(EdgeInsets(
            top: AppSpacing.s1, leading: AppSpacing.s4,
            bottom: AppSpacing.s1, trailing: AppSpacing.s4
        ))
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }
}

private struct EntryCardBody: View {
    let entry: NotepadEntry

    var body: some View {
        // Mirrors the "Recent Entries" card in the Releaf Branding
        // template — date label at the top as a small meta row, then
        // title + body preview below. Bumps radius + padding to 16 /
        // 24 so cards read as editorial containers rather than compact
        // tiles. Matches the Android twin on `EntryCard`.
        Card(padding: AppSpacing.s6, radius: AppRadius.lg) {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text(Self.formatDateLabel(entry.entryDate))
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)

                Text(displayTitle)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                    .lineLimit(1)
                    .truncationMode(.tail)

                if let preview = previewBody, !preview.isEmpty {
                    Text(preview)
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textSecondary)
                        .lineLimit(2)
                        .truncationMode(.tail)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// "Today" / "Yesterday" / "April 21, 2026" — same formatting
    /// rules as `DateHeader.formatted`. Duplicated instead of shared
    /// because `DateHeader` nests its formatters in `static let`s; a
    /// simple local helper is cleaner than plumbing them out.
    private static func formatDateLabel(_ iso: String) -> String {
        guard let date = isoFormatter.date(from: iso) else { return iso }
        let cal = Calendar.current
        if cal.isDateInToday(date) { return "Today" }
        if cal.isDateInYesterday(date) { return "Yesterday" }
        return longFormatter.string(from: date)
    }

    private static let isoFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        return f
    }()

    private static let longFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMMM d, yyyy"
        return f
    }()

    /// Title if set, otherwise the first non-empty line of the body.
    private var displayTitle: String {
        if let t = entry.title?.trimmingCharacters(in: .whitespacesAndNewlines),
           !t.isEmpty {
            return t
        }
        let firstLine = entry.notes
            .split(whereSeparator: \.isNewline)
            .lazy
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .first(where: { !$0.isEmpty })
        return firstLine ?? "Untitled"
    }

    /// Body preview — skip the first line if we're already using it as the
    /// display title, otherwise show the body as-is.
    private var previewBody: String? {
        let trimmed = entry.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let titleFromNotes = (entry.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "").isEmpty
        if titleFromNotes {
            let remainder = trimmed
                .split(separator: "\n", omittingEmptySubsequences: false)
                .dropFirst()
                .joined(separator: "\n")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return remainder.isEmpty ? nil : remainder
        }
        return trimmed
    }
}

// MARK: - Date header (sticky section header)

private struct DateHeader: View {
    let isoDate: String

    var body: some View {
        Text(formatted)
            .font(AppText.meta)
            .foregroundStyle(AppColors.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, AppSpacing.s2)
            .padding(.bottom, AppSpacing.s1)
            .padding(.horizontal, AppSpacing.s4)
            .background(AppColors.canvas)
            .listRowInsets(EdgeInsets())
    }

    /// "Today" / "Yesterday" / "April 21, 2026" — matches the Android
    /// formatter rules.
    private var formatted: String {
        guard let date = Self.isoFormatter.date(from: isoDate) else { return isoDate }
        let cal = Calendar.current
        if cal.isDateInToday(date) { return "Today" }
        if cal.isDateInYesterday(date) { return "Yesterday" }
        return Self.longDateFormatter.string(from: date)
    }

    private static let isoFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        return f
    }()

    private static let longDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMMM d, yyyy"
        return f
    }()
}

// MARK: - Empty states

private struct EmptyStateView: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: AppSpacing.s2) {
            Text(title)
                .font(AppText.sectionTitle)
                .foregroundStyle(AppColors.textSecondary)
                .multilineTextAlignment(.center)
            Text(subtitle)
                .font(AppText.body)
                .foregroundStyle(AppColors.textTertiary)
                .multilineTextAlignment(.center)
        }
        .padding(AppSpacing.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Undo toast

private struct UndoToastView: View {
    let onUndo: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: AppSpacing.s4) {
            Text("Entry deleted")
                .font(AppText.body)
                .foregroundStyle(AppColors.onAccent)

            Spacer(minLength: AppSpacing.s2)

            Button(action: onUndo) {
                Text("Undo")
                    .font(AppText.button)
                    .foregroundStyle(AppColors.coral)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.vertical, AppSpacing.s3)
        .background(Color(white: 0.12))
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
        .shadow(color: .black.opacity(0.2), radius: 8, x: 0, y: 4)
        .onTapGesture { onDismiss() }
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    NavigationStack {
        NotepadView()
    }
    .environmentObject(AuthStore(client: StubGoogleAuthClient()))
}
