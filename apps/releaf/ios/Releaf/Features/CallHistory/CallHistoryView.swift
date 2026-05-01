/*
 * CallHistoryView.swift
 *
 * Timeline-style list of outbound calls placed from inside the
 * app. Each row renders a coral-outlined badge (brand leaf)
 * connected to its neighbours via a vertical rail, with the date,
 * contact name, and a "phone · duration" subtitle on the right.
 *
 * Duration is nil for rows that ended before CXCallObserver saw a
 * connect — those surface as "Not connected" so the user can
 * tell the call didn't go through.
 */

import SwiftUI
import ReleafData
import ReleafDesignSystem

// Shared geometry — the connector-line math in `TimelineRail`
// needs to match these exact values so the rail lines up with
// each row's badge.
private let timelineRailWidth:  CGFloat = 60
private let timelineBadgeSize:  CGFloat = 48
private let timelineBadgeTop:   CGFloat = 16
private let timelineRowBottom:  CGFloat = 24

public struct CallHistoryView: View {
    @StateObject private var viewModel: CallHistoryViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showClearConfirm: Bool = false

    public init(userId: String) {
        _viewModel = StateObject(wrappedValue: CallHistoryViewModel(userId: userId))
    }

    public var body: some View {
        ZStack {
            AppColors.canvas.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                content
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .hidesBottomBar()
        .task { viewModel.start() }
        .onDisappear { viewModel.stop() }
        .confirmationDialog(
            "Clear call history?",
            isPresented: $showClearConfirm,
            titleVisibility: .visible
        ) {
            Button("Clear", role: .destructive) { viewModel.clearAll() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes every call from the log. It doesn't affect the OS call log.")
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            HStack {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .light))
                        .foregroundStyle(AppColors.textPrimary)
                        .frame(width: 24, height: 24)
                }
                .buttonStyle(.plain)
                Spacer()
                if !viewModel.state.entries.isEmpty {
                    Button {
                        showClearConfirm = true
                    } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 16, weight: .light))
                            .foregroundStyle(AppColors.textPrimary)
                            .frame(width: 24, height: 24)
                    }
                    .buttonStyle(.plain)
                }
            }
            Text("CALLS")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coral)
            Text("Call history")
                .font(AppText.editorialTitle)
                .foregroundStyle(AppColors.textPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, AppSpacing.s4)
        .padding(.top, AppSpacing.s3)
        .padding(.bottom, AppSpacing.s3)
    }

    // MARK: - Content

    @ViewBuilder private var content: some View {
        if viewModel.state.isLoading {
            Text("Loading…")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(AppSpacing.s4)
        } else if viewModel.state.isEmpty {
            EmptyCard()
                .padding(.horizontal, AppSpacing.s4)
                .padding(.top, AppSpacing.s2)
        } else {
            ScrollView {
                let entries = viewModel.state.entries
                let lastIndex = entries.count - 1
                LazyVStack(spacing: 0) {
                    ForEach(Array(entries.enumerated()), id: \.element.id) { idx, record in
                        TimelineRow(
                            record:  record,
                            isFirst: idx == 0,
                            isLast:  idx == lastIndex
                        )
                    }
                    Spacer(minLength: AppSpacing.s10)
                }
                .padding(.top, AppSpacing.s2)
            }
        }
    }
}

// MARK: - Row

/// Single timeline entry: coral-outlined brand-leaf badge on the
/// left, date / name / subtitle on the right. The connector line
/// is drawn in a `.background` GeometryReader so the badge (with
/// canvas fill) naturally masks the middle portion.
private struct TimelineRow: View {
    let record: CallHistoryRecord
    let isFirst: Bool
    let isLast: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            // Reserve the rail width; the badge + line are drawn
            // via overlay/background so the row height follows the
            // right-side content.
            Color.clear.frame(width: timelineRailWidth)
            VStack(alignment: .leading, spacing: 4) {
                Text(formatDateTime(record.startedAt))
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
                Text(record.contactName)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text("\(record.phoneNumber) \u{00B7} \(formatDuration(record))")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
            .padding(.top, timelineBadgeTop)
            .padding(.bottom, timelineRowBottom)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, AppSpacing.s4)
        .background(alignment: .topLeading) {
            GeometryReader { geo in
                Path { path in
                    let centerX  = AppSpacing.s4 + timelineRailWidth / 2
                    let topY     = timelineBadgeTop
                    let bottomY  = timelineBadgeTop + timelineBadgeSize
                    if !isFirst {
                        path.move(to:    CGPoint(x: centerX, y: 0))
                        path.addLine(to: CGPoint(x: centerX, y: topY))
                    }
                    if !isLast {
                        path.move(to:    CGPoint(x: centerX, y: bottomY))
                        path.addLine(to: CGPoint(x: centerX, y: geo.size.height))
                    }
                }
                .stroke(AppColors.borderDefault, lineWidth: 1)
            }
        }
        .overlay(alignment: .topLeading) {
            // Coral-ringed badge with a canvas fill so the
            // connector line passes cleanly underneath. The brand
            // leaf's own cream outline blends into the canvas at
            // this scale, leaving the green body as the visible
            // mark.
            ZStack {
                Circle()
                    .fill(AppColors.canvas)
                    .frame(width: timelineBadgeSize, height: timelineBadgeSize)
                Circle()
                    .stroke(AppColors.coral, lineWidth: 2)
                    .frame(width: timelineBadgeSize, height: timelineBadgeSize)
                ReleafLogo(size: 24, lineWidth: 1)
            }
            .padding(.top, timelineBadgeTop)
            .padding(.leading, AppSpacing.s4 + (timelineRailWidth - timelineBadgeSize) / 2)
        }
    }
}

// MARK: - Empty state

private struct EmptyCard: View {
    var body: some View {
        VStack(spacing: AppSpacing.s1) {
            Text("No calls yet")
                .font(AppText.sectionTitle)
                .foregroundStyle(AppColors.textPrimary)
            Text("Calls placed from Contacts show up here with duration.")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textTertiary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(AppSpacing.s6)
        .background(AppColors.cardSolid)
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
    }
}

// MARK: - Formatting

private func formatDuration(_ record: CallHistoryRecord) -> String {
    if let seconds = record.durationSeconds, seconds > 0 {
        return prettyDuration(seconds)
    }
    if record.connectedAt != nil && record.endedAt == nil { return "In progress" }
    if record.wasMissedOrCancelled { return "Not connected" }
    if record.durationSeconds == 0 { return "Under 1s" }
    return "Duration unavailable"
}

private func prettyDuration(_ totalSeconds: Int64) -> String {
    let h = totalSeconds / 3600
    let m = (totalSeconds % 3600) / 60
    let s = totalSeconds % 60
    var out = ""
    if h > 0 { out += "\(h)h " }
    if h > 0 || m > 0 { out += "\(m)m " }
    out += "\(s)s"
    return out
}

private let fullDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "MMMM d, yyyy"
    return f
}()

private let timeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "h:mm a"
    return f
}()

/// Matches the timeline design: "Month d, yyyy · h:mm a" for
/// every entry, with "Today" / "Yesterday" substituted for recent
/// buckets so the common cases read faster.
private func formatDateTime(_ date: Date) -> String {
    let cal = Calendar.current
    let time = timeFormatter.string(from: date)
    if cal.isDateInToday(date)     { return "Today \u{00B7} \(time)" }
    if cal.isDateInYesterday(date) { return "Yesterday \u{00B7} \(time)" }
    return "\(fullDateFormatter.string(from: date)) \u{00B7} \(time)"
}
