/*
 * HomeTimelineCard.swift
 *
 * Recent-activity card on Home. Reads from `RecentActivityViewModel`
 * (synthesizes ActivityItems from live notebook + notepad observables)
 * and renders one of two visual styles based on `UiPreferences`:
 *
 *   .classic — dot-on-rail timeline (the default; ships unchanged).
 *   .bramble — editorial vine variant powered by `ActivityTimeline`
 *              from the design system. Same data, different paint.
 *
 * The user picks between them in Settings via `setTimelineStyle(_:)`.
 *
 * Phase note: phase 1 of the activity-log work uses synthesized rows
 * (one entry per notebook + one per notepad entry, action inferred
 * from updated_at vs created_at). Phase 2 will swap the underlying
 * source to a real `audit_events` table written by the four user-
 * facing repositories on every mutation, mirroring Android.
 */

import SwiftUI
import ReleafData
import ReleafDesignSystem

public struct HomeTimelineCard: View {
    @StateObject private var viewModel: RecentActivityViewModel
    @EnvironmentObject private var prefs: UiPreferences
    let onSeeAll: () -> Void

    public init(
        userId: String,
        onSeeAll: @escaping () -> Void = {}
    ) {
        _viewModel = StateObject(wrappedValue: RecentActivityViewModel(userId: userId))
        self.onSeeAll = onSeeAll
    }

    public var body: some View {
        Group {
            switch prefs.state.timelineStyle {
            case .classic:
                ClassicTimelineCard(
                    items: viewModel.items,
                    onSeeAll: onSeeAll
                )
            case .bramble:
                BrambleTimelineCard(
                    items: viewModel.items,
                    onSeeAll: onSeeAll
                )
            }
        }
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.stop() }
    }
}

// MARK: - Classic (dot-on-rail)

private struct ClassicTimelineCard: View {
    let items: [ActivityItem]
    let onSeeAll: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            header

            if items.isEmpty {
                Text("No activity yet — start by adding a note.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
            } else {
                VStack(alignment: .leading, spacing: AppSpacing.s3) {
                    ForEach(items) { item in
                        row(for: item)
                    }
                }
                .overlay(alignment: .leading) {
                    Rectangle()
                        .fill(AppColors.subtle)
                        .frame(width: 1.5)
                        .padding(.leading, 9)
                        .padding(.vertical, 6)
                }
            }

            Button(action: onSeeAll) {
                Text("See full timeline  →")
                    .font(AppText.button)
                    .foregroundStyle(AppColors.coral)
            }
            .buttonStyle(.plain)
        }
        .padding(AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("RECENT · \(dateLabel)")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Spacer()
            Text("\(items.count)")
                .font(AppText.tag)
                .foregroundStyle(AppColors.textTertiary)
        }
    }

    private var dateLabel: String {
        let f = DateFormatter()
        f.dateFormat = "EEE MMM d"
        return f.string(from: Date()).uppercased()
    }

    private func row(for item: ActivityItem) -> some View {
        HStack(alignment: .top, spacing: AppSpacing.s3) {
            Circle()
                .fill(accentFor(item.kind))
                .frame(width: 10, height: 10)
                .padding(.top, 4)
                .overlay(
                    Circle().stroke(AppColors.cardSolid, lineWidth: 2)
                )
            VStack(alignment: .leading, spacing: 2) {
                Text(relativeTimeAgo(item.timestamp))
                    .font(AppText.tag)
                    .foregroundStyle(AppColors.textSecondary)
                Text(labelFor(item))
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textPrimary)
                    .lineLimit(2)
            }
        }
    }
}

// MARK: - Bramble (vine + flowers)

private struct BrambleTimelineCard: View {
    let items: [ActivityItem]
    let onSeeAll: () -> Void

    /// When there's no real activity yet, render a single placeholder
    /// entry so the bramble visual is still visible — otherwise flipping
    /// the toggle on a fresh install looks identical to Classic and the
    /// user has no way to know the switch worked.
    private var entries: [ActivityEntry] {
        if items.isEmpty {
            return [
                ActivityEntry(
                    date: "When you start",
                    title: "Your activity will appear here",
                    preview: "Add a note or open a notebook to begin.",
                    theme: .coral,
                    prominence: .featured
                ),
            ]
        }
        return items.enumerated().map { index, item in
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

    var body: some View {
        Button(action: onSeeAll) {
            ActivityTimeline(entries: entries, header: "ACTIVITY", showsArrow: true)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Mappings shared by both styles

/// Per-kind dot color for the Classic rendering. Mirrors the Android
/// `accentFor` swatches so the two platforms render the same hues.
private func accentFor(_ kind: ActivityKind) -> Color {
    switch kind {
    case .notepadEntry: return Color(hex: 0xE77850)  // coral
    case .page:         return Color(hex: 0x1E5943)  // green
    case .chapter:      return Color(hex: 0xB8956A)  // dry
    case .notebook:     return Color(hex: 0xF4C430)  // yellow
    case .photo:        return Color(hex: 0xF4C430)
    case .scan:         return Color(hex: 0xE77850)
    case .voice:        return Color(hex: 0xFCEAE0)
    case .todo:         return Color(hex: 0x7AA874)
    case .contact:      return Color(hex: 0xD9EDE2)
    case .location:     return Color(hex: 0xB8956A)
    }
}

/// ActivityKind → AccentPaletteID for the Bramble rendering. Mirrors
/// the Android `paletteFor` mapping.
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

/// Best-effort "4 days ago" / "2h ago" — ISO-8601 input.
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
