/*
 * QuickInkTimeBar.swift
 *
 * Slim top bar that shows just the current wall-clock time aligned
 * to the right edge. Sits above the NavigationStack in `QuickInkRoot`
 * on every non-Home, non-ScanDetail surface, and is reused inside
 * `ScanDetailScreen` for its auto-hide-on-scroll variant.
 *
 * Replaces the editorial `DaylightStatusBar` that previously occupied
 * this slot — the time chip reads as quiet ambient context without
 * crowding the screen below it. The bar refreshes every 60s, which
 * is sufficient resolution for a glanceable time chip.
 *
 * Mirror of Android `QuickInkTimeBar.kt`. Keep the layout (right-
 * aligned, 12pt medium, muted color) in sync between the two.
 */

import SwiftUI

struct QuickInkTimeBar: View {
    @State private var now = Date()
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.timeStyle = .short
        return f
    }()
    private let timer = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    var body: some View {
        HStack {
            Spacer()
            Text(Self.formatter.string(from: now))
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(QuickInkColors.muted)
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.top, 4)
        .padding(.bottom, QuickInkSpacing.s1)
        .background(QuickInkColors.bg)
        .onReceive(timer) { _ in now = Date() }
    }
}
