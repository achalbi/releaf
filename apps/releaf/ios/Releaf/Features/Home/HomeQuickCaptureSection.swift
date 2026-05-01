/*
 * HomeQuickCaptureSection.swift
 * Six quick-capture pills under the library card — one per capture
 * mode the notepad supports: notes, photos, scans, voice, todos,
 * contacts. Tap routes to the notepad tab (future iteration will
 * route through to a pre-selected capture mode on the new entry).
 *
 * Pure presentation today — onCapture receives the tapped mode and
 * the screen decides what to do with it.
 */

import SwiftUI
import ReleafDesignSystem

public enum QuickCaptureMode: String, CaseIterable, Identifiable {
    case notes, photos, scans, voice, todos, location
    public var id: String { rawValue }

    var label: String {
        switch self {
        case .notes:    return "Notes"
        case .photos:   return "Photos"
        case .scans:    return "Scans"
        case .voice:    return "Voice"
        case .todos:    return "Todos"
        case .location: return "Location"
        }
    }

    var symbol: String {
        switch self {
        case .notes:    return "square.and.pencil"
        case .photos:   return "photo"
        case .scans:    return "doc.viewfinder"
        case .voice:    return "mic"
        case .todos:    return "checklist"
        case .location: return "mappin"
        }
    }
}

public struct HomeQuickCaptureSection: View {
    let counts: [QuickCaptureMode: Int]
    let onCapture: (QuickCaptureMode) -> Void

    public init(
        counts: [QuickCaptureMode: Int] = [:],
        onCapture: @escaping (QuickCaptureMode) -> Void
    ) {
        self.counts = counts
        self.onCapture = onCapture
    }

    public var body: some View {
        let rows = QuickCaptureMode.allCases.chunked(into: 3)

        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            Text("HIGHLIGHT")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)

            VStack(spacing: 0) {
                ForEach(rows.indices, id: \.self) { rowIndex in
                    if rowIndex > 0 {
                        Divider().background(AppColors.borderDefault)
                    }
                    HStack(spacing: 0) {
                        ForEach(Array(rows[rowIndex].enumerated()), id: \.element) { colIndex, mode in
                            if colIndex > 0 {
                                Divider().background(AppColors.borderDefault)
                            }
                            Pill(
                                mode: mode,
                                count: counts[mode] ?? 0,
                                action: { onCapture(mode) }
                            )
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
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
}

private extension Array {
    func chunked(into size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map {
            Array(self[$0..<Swift.min($0 + size, count)])
        }
    }
}

private struct Pill: View {
    let mode: QuickCaptureMode
    let count: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: AppSpacing.s2) {
                Image(systemName: mode.symbol)
                    .font(.system(size: 32, weight: .regular))
                    .foregroundStyle(AppColors.coral)
                    .frame(height: 36)
                Text(mode.label.uppercased())
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                    .lineLimit(1)
                Text("\(count)")
                    .font(.system(size: 26, design: .serif))
                    .foregroundStyle(count > 0 ? AppColors.textPrimary : AppColors.textTertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
            .padding(.vertical, AppSpacing.s3)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }
}
