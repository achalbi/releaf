/*
 * QuickInkTimeBar.swift
 *
 * Slim top bar that shows the current time and date in a single
 * line with a center-dot separator ("9:35 AM · Tue 19 Jan"). Sits
 * above the NavigationStack in `QuickInkRoot` on every screen —
 * functions as the app's status strip now that the system bar is
 * hidden app-wide. Refreshes every 60s; only the minute moves
 * below the hour scale.
 *
 * Mirror of Android `QuickInkTimeBar.kt`. Keep the layout (top-
 * left, 12pt medium, muted color, 10pt top + s5 leading padding)
 * in sync between the two. The leading inset matches the Home
 * screen's content margin so the bar text lines up with the
 * "Good evening" greeting below it.
 */

import SwiftUI

struct QuickInkTimeBar: View {
    @State private var now = Date()
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "h:mm a · EEE d MMM"
        return f
    }()
    private let timer = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    var body: some View {
        HStack {
            Text(Self.formatter.string(from: now))
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(QuickInkColors.muted)
            Spacer()
        }
        .padding(.top, 10)
        .padding(.leading, QuickInkSpacing.s5)
        .background(QuickInkColors.bg)
        .onReceive(timer) { _ in now = Date() }
    }
}
