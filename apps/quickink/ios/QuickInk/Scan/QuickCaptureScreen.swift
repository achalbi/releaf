/*
 * QuickCaptureScreen.swift
 *
 * Pre-capture surface — the dark, editorial scan UI from the
 * mockup brief. Per the brief:
 *
 *   - Dark camera UI
 *   - Animated detection corners on a tilted page preview
 *   - Mode selector (Single / Multi-page / Auto)
 *   - 78px shutter with Zap icon and page count badge
 *
 * Architecturally this sits between Home (the Zap FAB tap) and
 * the system document scanner (`VNDocumentCameraViewController`
 * via `DocumentScannerView` in ReleafCoreScan). It's a mode-picker
 * + visual hand-off, not a custom AVFoundation camera — building
 * a full custom camera here would replace VisionKit's already-
 * mature edge detection and not yield a meaningfully different
 * scan, so the tradeoff favors VisionKit for capture and a polish
 * surface here for brand identity.
 *
 * On Zap shutter tap, this screen dismisses and the system scanner
 * presents — its result flows back through the existing
 * `ScanFlowController.onScanComplete` pipeline.
 *
 * Mirror of Android `QuickCaptureScreen.kt`.
 */

import SwiftUI
import ReleafCoreScan

struct QuickCaptureScreen: View {
    let controller: ScanFlowController
    let onDismiss: () -> Void

    @State private var mode: CaptureMode = .single
    @State private var pageCount: Int = 0
    @State private var sweepOffset: CGFloat = -50
    @State private var showSystemScanner = false

    enum CaptureMode: String, CaseIterable {
        case single, multiPage, auto

        var label: String {
            switch self {
            case .single:    return "Single"
            case .multiPage: return "Multi-page"
            case .auto:      return "Auto"
            }
        }
    }

    var body: some View {
        ZStack {
            // Dark canvas — stone-950 overlay covering the full frame.
            Color(hex: 0x0F0E0D).ignoresSafeArea()

            VStack(spacing: 0) {
                topBar
                Spacer()
                pagePreview
                Spacer()
                shutterRow
                modeSelector
                    .padding(.bottom, QuickInkSpacing.s7)
            }
        }
        .preferredColorScheme(.dark)
        .fullScreenCover(isPresented: $showSystemScanner) {
            DocumentScannerView(
                onComplete: { pdfURL, previewURL, pageURLs in
                    showSystemScanner = false
                    controller.onScanComplete(
                        pdfURL:     pdfURL,
                        previewURL: previewURL,
                        pageURLs:   pageURLs
                    )
                    onDismiss()
                },
                onCancel: {
                    showSystemScanner = false
                }
            )
            .ignoresSafeArea()
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

            Text("Capture")
                .font(QuickInkText.label)
                .foregroundStyle(Color.white.opacity(0.85))

            Spacer()

            // Flash toggle placeholder.
            Button(action: { /* flash mode follow-up */ }) {
                Image(systemName: "bolt.slash")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .frame(width: 36, height: 36)
                    .background(Color.white.opacity(0.10))
                    .clipShape(Circle())
            }
            .accessibilityLabel("Flash")
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        // Generous top padding so the close button clears the notch
        // with breathing room. Doubles as a vertical-centering nudge:
        // the bottom region (shutter + mode-selector + s7 bottom pad)
        // is heavier than the top, so without this the page mock
        // appeared visibly above center.
        .padding(.top, QuickInkSpacing.s7)
    }

    // MARK: - Page preview

    @ViewBuilder
    private var pagePreview: some View {
        ZStack {
            // Tilted lined-paper page mock — Caveat handwriting for
            // texture; the real camera frame replaces this when we
            // build a custom AVFoundation surface (out of scope for
            // QuickInk MVP).
            ZStack(alignment: .topLeading) {
                QuickInkLinedPaper(
                    tone: QuickInkColors.surface,
                    lineSpacing: 18,
                    lineOpacity: 0.10
                )
                HStack(spacing: 0) {
                    Rectangle()
                        .fill(QuickInkColors.accent.opacity(0.6))
                        .frame(width: 1.5)
                        .padding(.leading, 24)
                    Spacer()
                }
                .padding(.vertical, 14)

                Text("Brainstorm — Q3\nGoals, opportunities,\nand notes…")
                    .font(QuickInkFont.handwritten(20))
                    .foregroundStyle(QuickInkColors.ink.opacity(0.78))
                    .padding(.leading, 36)
                    .padding(.top, QuickInkSpacing.s5)
            }
            .frame(width: 240, height: 320)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            .rotation3DEffect(.degrees(8), axis: (x: 1, y: 0, z: 0))
            .rotation3DEffect(.degrees(-4), axis: (x: 0, y: 1, z: 0))
            .shadow(color: .black.opacity(0.5), radius: 24, x: 0, y: 12)

            // Animated coral scan line.
            Rectangle()
                .fill(
                    LinearGradient(
                        colors: [
                            QuickInkColors.accent.opacity(0),
                            QuickInkColors.accent.opacity(0.7),
                            QuickInkColors.accent,
                            QuickInkColors.accent.opacity(0.7),
                            QuickInkColors.accent.opacity(0),
                        ],
                        startPoint: .leading, endPoint: .trailing
                    )
                )
                .frame(width: 240, height: 2)
                .offset(y: sweepOffset)
                .shadow(color: QuickInkColors.accent.opacity(0.6), radius: 8, x: 0, y: 0)

            // Detection corners.
            DetectionCorner(rotation: 0)        .offset(x: -120, y: -160)
            DetectionCorner(rotation: 90)       .offset(x:  120, y: -160)
            DetectionCorner(rotation: 270)      .offset(x: -120, y:  160)
            DetectionCorner(rotation: 180)      .offset(x:  120, y:  160)
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true)) {
                sweepOffset = 130
            }
        }
    }

    // MARK: - Shutter row

    @ViewBuilder
    private var shutterRow: some View {
        HStack(alignment: .center) {
            // Page count badge — visible once user has captured at
            // least one in multi-mode.
            if mode == .multiPage {
                pageBadge
                    .frame(width: 64)
            } else {
                Spacer().frame(width: 64)
            }

            Spacer()

            shutterButton

            Spacer()

            // Done button (multi-mode finish) — placeholder.
            if mode == .multiPage && pageCount > 0 {
                Button(action: { showSystemScanner = true }) {
                    Text("Done")
                        .font(QuickInkText.label)
                        .foregroundStyle(.white)
                        .frame(width: 64, height: 36)
                        .background(QuickInkColors.accent)
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                }
                .buttonStyle(.plain)
            } else {
                Spacer().frame(width: 64)
            }
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.bottom, QuickInkSpacing.s4)
    }

    @ViewBuilder
    private var shutterButton: some View {
        Button(action: triggerScan) {
            ZStack {
                Circle()
                    .stroke(Color.white.opacity(0.6), lineWidth: 3)
                    .frame(width: 78, height: 78)
                Circle()
                    .fill(QuickInkColors.accent)
                    .frame(width: 64, height: 64)
                Image(systemName: "bolt.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .shadow(color: QuickInkColors.accent.opacity(0.5), radius: 16, x: 0, y: 6)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Capture page")
    }

    @ViewBuilder
    private var pageBadge: some View {
        VStack(spacing: 2) {
            Text("\(pageCount)")
                .font(QuickInkText.heading)
                .foregroundStyle(.white)
            Text(pageCount == 1 ? "page" : "pages")
                .font(QuickInkText.caption)
                .foregroundStyle(Color.white.opacity(0.7))
        }
    }

    // MARK: - Mode selector

    @ViewBuilder
    private var modeSelector: some View {
        HStack(spacing: 4) {
            ForEach(CaptureMode.allCases, id: \.self) { m in
                let active = (m == mode)
                Button(action: { mode = m }) {
                    Text(m.label)
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
            }
        }
        .padding(4)
        .background(Color.white.opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
    }

    // MARK: - Actions

    private func triggerScan() {
        // Single + Auto: hand off to system scanner immediately.
        // Multi-page: increment badge for visual polish, then on
        // "Done" tap, hand off. (Real multi-page accumulation is
        // VisionKit's job; this badge is visual only until that
        // pipeline lands.)
        switch mode {
        case .single, .auto:
            showSystemScanner = true
        case .multiPage:
            pageCount += 1
            if pageCount == 1 {
                // First page in multi mode: present scanner; the
                // user can keep capturing within it. When they
                // finish, the result flows through onScanComplete.
                showSystemScanner = true
            }
        }
    }
}

// MARK: - Detection corner

struct DetectionCorner: View {
    let rotation: Double
    var body: some View {
        Path { path in
            path.move(to: CGPoint(x: 0, y: 18))
            path.addLine(to: CGPoint(x: 0, y: 0))
            path.addLine(to: CGPoint(x: 18, y: 0))
        }
        .stroke(QuickInkColors.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
        .frame(width: 18, height: 18)
        .rotationEffect(.degrees(rotation))
    }
}
