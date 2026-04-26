/*
 * ActivityScreen.swift
 *
 * Full-screen activity log — same data source as the Home timeline
 * card, just a wider window (full limit vs HOME_LIMIT) so the user
 * can scroll back through everything they've touched.
 *
 * Mirrors `features/activity/ActivityScreen.kt` on Android. Uses the
 * Bramble timeline visual treatment (vine + flowers) as the primary
 * surface since the user opted into it via Settings; the Classic
 * dot-on-rail rendering is still available on Home.
 */

import SwiftUI
import ReleafData
import ReleafDesignSystem

public struct ActivityScreen: View {
    @StateObject private var viewModel: RecentActivityViewModel
    @ObservedObject private var prefs: UiPreferences

    public init(userId: String, prefs: UiPreferences = .shared) {
        _viewModel = StateObject(
            wrappedValue: RecentActivityViewModel(
                userId: userId,
                maxItems: 200
            )
        )
        self.prefs = prefs
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                header
                if viewModel.items.isEmpty {
                    emptyState
                } else {
                    ActivityTimeline(
                        entries: timelineEntries,
                        header: "All activity"
                    )
                }
                Spacer(minLength: AppSpacing.s10)
            }
            .padding(.horizontal, AppSpacing.s5)
            .padding(.top, AppSpacing.s5)
        }
        .background(AppColors.canvas.ignoresSafeArea())
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.stop() }
    }

    // MARK: - Pieces

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s1) {
            Text("ACTIVITY")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Text("Timeline")
                .font(AppText.editorialTitle)
                .foregroundStyle(AppColors.textPrimary)
            Text("\(viewModel.items.count) item\(viewModel.items.count == 1 ? "" : "s")")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
        }
    }

    private var emptyState: some View {
        Text("No activity yet — start by adding a note.")
            .font(AppText.body)
            .foregroundStyle(AppColors.textSecondary)
            .padding(.vertical, AppSpacing.s6)
            .frame(maxWidth: .infinity)
    }

    private var timelineEntries: [ActivityEntry] {
        viewModel.items.enumerated().map { index, item in
            ActivityEntry(
                id: UUID(uuidString: item.id) ?? UUID(),
                date: relativeTimeAgo(item.timestamp),
                title: labelFor(item),
                preview: item.context,
                theme: paletteFor(item.kind),
                prominence: index == 0 ? .featured : .routine
            )
        }
    }
}

// MARK: - Mappings

/// Mirrors the Home-card mapping. Sub-event kinds (photo, scan, etc.)
/// route to the same palette as on the timeline card.
private func paletteFor(_ kind: ActivityKind) -> AccentPaletteID {
    switch kind {
    case .notepadEntry, .voice:               return .coral
    case .notebook, .page, .chapter:          return .green
    case .photo, .scan, .todo:                return .yellow
    case .contact, .location:                 return .dry
    }
}

/// "<verb> <thing> · <title>", e.g. "Updated note · Morning notes".
private func labelFor(_ item: ActivityItem) -> String {
    let verb: String = {
        switch item.action {
        case .created:  return "Created"
        case .updated:  return "Updated"
        case .deleted:  return "Deleted"
        case .restored: return "Restored"
        case .merged:   return "Merged"
        case .moved:    return "Moved"
        }
    }()
    let noun: String = {
        switch item.kind {
        case .notepadEntry: return "note"
        case .page:         return "page"
        case .chapter:      return "chapter"
        case .notebook:     return "notebook"
        case .photo:        return "photo"
        case .scan:         return "scan"
        case .voice:        return "voice note"
        case .todo:         return "todo"
        case .contact:      return "contact"
        case .location:     return "location"
        }
    }()
    return "\(verb) \(noun) · \(item.title)"
}

private func relativeTimeAgo(_ iso: String) -> String {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    let parsed = f.date(from: iso) ?? {
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: iso)
    }()
    guard let date = parsed else { return iso }
    let delta = max(0, Int(Date().timeIntervalSince(date)))
    if delta < 60         { return "just now" }
    if delta < 3600       { return "\(delta / 60)m ago" }
    if delta < 86_400     { return "\(delta / 3600)h ago" }
    if delta < 2 * 86_400 { return "yesterday" }
    if delta < 7 * 86_400 { return "\(delta / 86_400) days ago" }
    let fmt = DateFormatter()
    fmt.dateFormat = "MMM d"
    return fmt.string(from: date)
}
