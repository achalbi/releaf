/*
 * QuickCaptureScreen.swift
 *
 * Top-level capture surface. Owns the cross-surface chrome —
 * close button, the Document/Business-Card mode pill — and
 * dispatches the live area below the top bar to one of three
 * child surfaces:
 *
 *   - `DocumentCaptureSurface`     — the existing VisionKit
 *                                    document scanner flow
 *                                    (Single/Multi-page
 *                                    pill + page-mock +
 *                                    shutter that presents
 *                                    `VNDocumentCameraViewController`).
 *   - `BusinessCardCaptureSurface` — an in-app
 *                                    AVCaptureSession preview
 *                                    with a card-shaped guide
 *                                    overlay and a custom
 *                                    detector that auto-captures
 *                                    on a stable quad.
 *   - `PhotoCaptureSurface`        — a plain single-shot still
 *                                    camera. No pill slot —
 *                                    entered transiently via
 *                                    the bottom-nav ⚡ FAB's
 *                                    long-press (`initialMode:
 *                                    .photo` on this screen)
 *                                    or via the Photo icon in
 *                                    the other two surfaces'
 *                                    shutter rows. The pill
 *                                    keeps highlighting the
 *                                    last pill-selected mode
 *                                    while the photo surface
 *                                    is up, so tapping a pill
 *                                    flips back with one tap.
 *
 * Why two surfaces instead of one shared camera session:
 * Document mode is hosted by Apple's
 * `VNDocumentCameraViewController`, which owns its own
 * `AVCaptureSession` internally — we can't share it with an
 * in-process camera. The toggle picks the surface; mode-switch
 * latency is dominated by SwiftUI's mount/unmount of one of
 * the child views, which is well under the spec's 100 ms
 * budget.
 *
 * The mode pill lives in the top bar (where the "Capture"
 * title used to sit). That's the canonical tabbed-camera
 * position — Instagram, Apple Camera, every modern OS camera
 * app puts the mode picker right there — and it saves vertical
 * space against the existing layout's per-surface chrome at
 * the bottom.
 *
 * Mirror of Android `QuickCaptureScreen.kt`.
 */

import SwiftUI
import ReleafCoreScan

struct QuickCaptureScreen: View {

    let controller: ScanFlowController
    let onDismiss: () -> Void

    /// Persisted starting mode. Read once from SettingsState
    /// at view init; subsequent flips persist back through the
    /// CaptureModeCoordinator.
    @StateObject private var coordinator: CaptureModeCoordinator

    /// The most recent pill-selected mode (Document or Business
    /// Card — never `.photo`). Drives the highlighted option in
    /// the top-bar pill. Diverges from `coordinator.mode` when
    /// the user enters `.photo` transiently via the FAB long-
    /// press or the shutter-row Photo icon: the pill keeps
    /// showing whatever pill choice the user last made, so on
    /// the photo surface the user still sees "Document" or
    /// "Business Card" highlighted and can flip back with one
    /// tap. Matches spec test #5 ("top-bar pill should still
    /// read 'Document'") and spec §11 ("the user can switch to
    /// Document mode via the pill without re-granting").
    @State private var lastPillMode: CaptureMode

    /// Optional override for the starting surface. `nil` (the
    /// default) reads `quickink.capture.last_mode` from
    /// UserDefaults as before. Passing `.photo` lets the bottom-
    /// nav ⚡ FAB's long-press jump straight into the photo
    /// surface without disturbing the user's pill choice — the
    /// coordinator below uses a no-op persist on the long-press
    /// path so the next tap on the FAB still lands on the
    /// previously-selected pill mode (Document or Business Card).
    init(
        controller: ScanFlowController,
        initialMode: CaptureMode? = nil,
        onDismiss: @escaping () -> Void,
    ) {
        self.controller = controller
        self.onDismiss = onDismiss
        let persistedPillMode = CaptureMode.fromAnalyticsKey(
            UserDefaults.standard.string(forKey: "quickink.capture.last_mode")
        )
        let starting: CaptureMode = initialMode ?? persistedPillMode
        // `lastPillMode` always tracks a pill-eligible mode
        // (Document / Business Card). On long-press entry the
        // surface starts on `.photo` but the pill still shows
        // the persisted pill choice — never highlight `.photo`
        // in the pill, since `.photo` doesn't have a pill slot.
        _lastPillMode = State(initialValue: persistedPillMode == .photo ? .document : persistedPillMode)
        // Long-press → `.photo` is transient: it should NOT
        // overwrite the user's last pill choice. Gate the
        // persist hook so only pill-eligible modes round-trip
        // to UserDefaults. If the user later flips the pill
        // from inside this transient surface, that pill-driven
        // select() will land here too and re-persist normally.
        _coordinator = StateObject(wrappedValue: CaptureModeCoordinator(
            initial: starting,
            persist: { mode in
                guard mode != .photo else { return }
                UserDefaults.standard.set(mode.analyticsKey, forKey: "quickink.capture.last_mode")
            },
        ))
    }

    var body: some View {
        ZStack {
            Color(hex: 0x0F0E0D).ignoresSafeArea()

            VStack(spacing: 0) {
                topBar
                // Surface dispatch — render exactly one of the
                // two surfaces; never both. SwiftUI tears down
                // the inactive branch's `.onAppear` /
                // `.onDisappear` automatically, so flipping
                // the toggle releases the previous surface's
                // resources (e.g. AVCaptureSession) without
                // any explicit cleanup wiring here.
                Group {
                    switch coordinator.mode {
                    case .document:
                        DocumentCaptureSurface(
                            controller:     controller,
                            onDismiss:      onDismiss,
                            onSelectPhoto:  { coordinator.select(.photo) },
                        )
                    case .businessCard:
                        BusinessCardCaptureSurface(
                            controller:     controller,
                            onDismiss:      onDismiss,
                            onSelectPhoto:  { coordinator.select(.photo) },
                        )
                    case .photo:
                        PhotoCaptureSurface(
                            controller: controller,
                            onDismiss:  onDismiss,
                        )
                    }
                }
                .frame(maxHeight: .infinity)
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            // First render → log `capture_mode_selected` with
            // the persisted value so dashboards see a "user
            // is in this mode" signal even when they don't
            // touch the toggle.
            CaptureAnalytics.modeSelected(coordinator.mode)
        }
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        HStack {
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .frame(width: 36, height: 36)
                    .background(Color.white.opacity(0.10))
                    .clipShape(Circle())
            }
            .accessibilityLabel("Close scan")

            Spacer()

            // Pill stays two-wide (Document / Business Card)
            // AND stays visible on every surface — including
            // `.photo`. `current:` reads from `lastPillMode`,
            // not `coordinator.mode`, so on the photo surface
            // the user still sees their previous pill choice
            // highlighted (per spec test #5) and can flip back
            // to Document / Business Card with one tap (per
            // spec §11, which expects the pill to be available
            // as an escape hatch when camera permission is
            // denied for photo mode).
            ModeTogglePill(
                current:  lastPillMode,
                onSelect: { mode in
                    lastPillMode = mode
                    coordinator.select(mode)
                },
            )

            Spacer()

            // Right-slot spacer — matches the 36pt close button
            // footprint so the pill stays centered.
            Spacer().frame(width: 36, height: 36)
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.top, QuickInkSpacing.s7)
        .padding(.bottom, QuickInkSpacing.s4)
    }
}

// MARK: - Mode toggle pill

private struct ModeTogglePill: View {
    let current: CaptureMode
    let onSelect: (CaptureMode) -> Void

    /// The pill is intentionally two-wide on a 393pt device —
    /// three pills crowd the top bar against the close button
    /// and right-slot spacer. `.photo` is a transient surface
    /// (long-press FAB / shutter-row icon), so it doesn't take
    /// a pill slot. If a third pill ever ships, the
    /// Instagram-style ordering (Document / Photo / Business
    /// Card) reduces accidental Business-Card taps.
    private static let pillModes: [CaptureMode] = [.document, .businessCard]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(Self.pillModes, id: \.self) { m in
                let active = (m == current)
                Button(action: { tap(m) }) {
                    Text(m.pillLabel)
                        .font(QuickInkText.label)
                        .foregroundStyle(active ? .white : Color.white.opacity(0.55))
                        .padding(.horizontal, QuickInkSpacing.s4)
                        .padding(.vertical, QuickInkSpacing.s2)
                        .background(
                            RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                                .fill(active ? QuickInkColors.accent : .clear)
                        )
                }
                .buttonStyle(.plain)
                .animation(.easeOut(duration: 0.15), value: active)
            }
        }
        .padding(4)
        .background(Color.white.opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
    }

    private func tap(_ mode: CaptureMode) {
        if mode != current {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            onSelect(mode)
        }
    }
}
