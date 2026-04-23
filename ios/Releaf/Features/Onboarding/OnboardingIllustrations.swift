/*
 * OnboardingIllustrations.swift
 *
 * The 10 step-specific illustrations used by [OnboardingWizard]. All
 * visuals are built from SwiftUI primitives (shapes, text, Canvas) —
 * no raster assets — mirroring the web source at
 * docs/onboarding/source/_onboarding_wizard.html.erb.
 *
 * Every coral accent has been wired through
 * `@Environment(\.accentPalette)` so the wizard re-tints with the
 * user's chosen primary colour (Coral / Green / Yellow / Dry).
 */

import SwiftUI
import ReleafDesignSystem

private let illustrationHeight: CGFloat = 140

struct IllustrationFrame<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack { content() }
            .frame(maxWidth: .infinity)
            .frame(height: illustrationHeight)
    }
}

// Step 1 — Welcome: app icon + sparkles ───────────────────────────
struct WelcomeIllustration: View {
    @Environment(\.accentPalette) private var accent

    var body: some View {
        IllustrationFrame {
            ZStack {
                AppIconMark(size: 72)
                Text("✦")
                    .font(.system(size: 14))
                    .foregroundStyle(accent.primary.opacity(0.6))
                    .offset(x: 44, y: -44)
                Text("✦")
                    .font(.system(size: 17))
                    .foregroundStyle(accent.primary.opacity(0.6))
                    .offset(x: -56, y: -8)
                Text("✦")
                    .font(.system(size: 12))
                    .foregroundStyle(accent.primary.opacity(0.6))
                    .offset(x: 52, y: 40)
            }
        }
    }
}

struct AppIconMark: View {
    @Environment(\.accentPalette) private var accent
    let size: CGFloat

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [OnboardTokens.iconGradientStart, OnboardTokens.iconGradientEnd],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            IconInterior(size: size)
                .frame(width: size * 0.63, height: size * 0.66)
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size / 4, style: .continuous))
        .shadow(color: accent.primary.opacity(0.25), radius: 12, x: 0, y: 8)
    }
}

private struct IconInterior: View {
    let size: CGFloat

    var body: some View {
        Canvas { ctx, canvasSize in
            let corner = size / 18
            let rect = CGRect(origin: .zero, size: canvasSize)
            ctx.fill(
                Path(roundedRect: rect, cornerRadius: corner),
                with: .color(OnboardTokens.iconSurface)
            )

            // Horizontal lines (iOS app-icon.svg has 3)
            let lineStroke = size / 13
            let w = canvasSize.width
            let h = canvasSize.height
            let inset = w * 0.13

            func line(_ y: CGFloat, endX: CGFloat) {
                var p = Path()
                p.move(to: CGPoint(x: inset, y: y))
                p.addLine(to: CGPoint(x: endX, y: y))
                ctx.stroke(p, with: .color(OnboardTokens.iconLine),
                           style: StrokeStyle(lineWidth: lineStroke, lineCap: .round))
            }
            line(h * 0.26, endX: w - inset)
            line(h * 0.50, endX: w - inset)
            line(h * 0.74, endX: w * 0.70)

            // Orange dot + check
            let dotR = w * 0.145
            let dotCenter = CGPoint(x: w * 0.775, y: h * 0.76)
            ctx.fill(
                Path(ellipseIn: CGRect(
                    x: dotCenter.x - dotR, y: dotCenter.y - dotR,
                    width: dotR * 2, height: dotR * 2
                )),
                with: .color(OnboardTokens.iconDotFill)
            )
            var check = Path()
            check.move(to: CGPoint(x: dotCenter.x - dotR * 0.45, y: dotCenter.y))
            check.addLine(to: CGPoint(x: dotCenter.x - dotR * 0.1,  y: dotCenter.y + dotR * 0.38))
            check.addLine(to: CGPoint(x: dotCenter.x + dotR * 0.5,  y: dotCenter.y - dotR * 0.5))
            ctx.stroke(check, with: .color(OnboardTokens.iconSurface),
                       style: StrokeStyle(lineWidth: size / 24, lineCap: .round))
        }
    }
}

// Step 2 — Notebooks: stacked cards ───────────────────────────────
struct NotebooksIllustration: View {
    var body: some View {
        IllustrationFrame {
            ZStack {
                CardMock(emoji: "📑", rotation: -8, offsetX: -6, offsetY: 6, opacity: 0.5)
                CardMock(emoji: "📄", rotation: -3, offsetX: -2, offsetY: 2, opacity: 0.75)
                CardMock(emoji: "📓", rotation: 0,  offsetX: 0,  offsetY: 0, opacity: 1.0)
            }
            .frame(width: 80, height: 100)
        }
    }
}

private struct CardMock: View {
    let emoji: String
    let rotation: Double
    let offsetX: CGFloat
    let offsetY: CGFloat
    let opacity: Double
    var width: CGFloat = 72
    var height: CGFloat = 88
    var fontSize: CGFloat = 32

    var body: some View {
        Text(emoji)
            .font(.system(size: fontSize))
            .frame(width: width, height: height)
            .background(OnboardTokens.cardBg.opacity(opacity))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .shadow(color: Color.black.opacity(0.12), radius: 6, x: 0, y: 4)
            .rotationEffect(.degrees(rotation))
            .offset(x: offsetX, y: offsetY)
    }
}

// Step 3 — Notepad: calendar ──────────────────────────────────────
struct NotepadIllustration: View {
    @Environment(\.accentPalette) private var accent
    private var day: Int { Calendar.current.component(.day, from: Date()) }

    var body: some View {
        IllustrationFrame {
            VStack(alignment: .leading, spacing: 0) {
                Text("APRIL")
                    .font(OnboardTokens.calendarHeader)
                    .tracking(0.5)
                    .foregroundStyle(accent.primary)
                    .padding(.bottom, 4)
                Text("\(day)")
                    .font(OnboardTokens.calendarNumber)
                    .foregroundStyle(OnboardTokens.textPrimary)
                    .padding(.bottom, 10)
                Rectangle()
                    .fill(OnboardTokens.lineFill)
                    .frame(height: 6)
                    .clipShape(RoundedRectangle(cornerRadius: 3))
                    .padding(.bottom, 6)
                Rectangle()
                    .fill(OnboardTokens.lineFill)
                    .frame(width: 44, height: 6)
                    .clipShape(RoundedRectangle(cornerRadius: 3))
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)
            .padding(.bottom, 14)
            .frame(width: 100, alignment: .leading)
            .background(OnboardTokens.cardBg)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .shadow(color: Color.black.opacity(0.12), radius: 10, x: 0, y: 6)
        }
    }
}

// Step 4 — Photos: frame + pill ───────────────────────────────────
struct PhotosIllustration: View {
    @Environment(\.accentPalette) private var accent

    var body: some View {
        IllustrationFrame {
            VStack(spacing: 10) {
                Text("📷")
                    .font(.system(size: 34))
                    .frame(width: 80, height: 80)
                    .background(OnboardTokens.photoFrameBg)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(OnboardTokens.photoFrameBorder, lineWidth: 2)
                    )
                    .shadow(color: Color.black.opacity(0.08), radius: 4, x: 0, y: 2)

                Text("Settings → Quality")
                    .font(OnboardTokens.photoBadge)
                    .foregroundStyle(accent.primary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 3)
                    .background(accent.soft)
                    .clipShape(Capsule())
                    .overlay(Capsule().stroke(accent.border, lineWidth: 1))
            }
        }
    }
}

// Step 5 — Voice notes ────────────────────────────────────────────
struct VoiceIllustration: View {
    @Environment(\.accentPalette) private var accent
    private let heights: [CGFloat] = [10, 20, 28, 18, 30, 22, 14, 24, 10]

    var body: some View {
        IllustrationFrame {
            VStack(spacing: 12) {
                Text("🎙️").font(.system(size: 38))
                HStack(spacing: 4) {
                    ForEach(Array(heights.enumerated()), id: \.offset) { _, h in
                        Capsule()
                            .fill(accent.primary.opacity(0.7))
                            .frame(width: 4, height: h)
                    }
                }
                .frame(height: 32)
            }
        }
    }
}

// Step 6 — To-do list ─────────────────────────────────────────────
struct TodoIllustration: View {
    @Environment(\.accentPalette) private var accent

    var body: some View {
        IllustrationFrame {
            VStack(spacing: 8) {
                TodoRow(checked: true,  label: "Buy groceries", strike: true)
                TodoRow(checked: false, label: "Call dentist",  trailing: {
                    AnyView(Text("⏰").font(.system(size: 13)))
                })
                TodoRow(checked: false, label: "Finish report", trailing: {
                    AnyView(
                        Text("Task")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(accent.deep)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 1)
                            .background(accent.soft)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    )
                })
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(width: 220)
            .background(OnboardTokens.cardBg)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .shadow(color: Color.black.opacity(0.09), radius: 8, x: 0, y: 4)
        }
    }
}

private struct TodoRow: View {
    @Environment(\.accentPalette) private var accent
    let checked: Bool
    let label: String
    var strike: Bool = false
    var trailing: (() -> AnyView)? = nil

    var body: some View {
        HStack(spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 4)
                    .fill(checked ? accent.primary : Color.clear)
                    .frame(width: 16, height: 16)
                    .overlay(
                        RoundedRectangle(cornerRadius: 4)
                            .stroke(checked ? accent.primary : OnboardTokens.borderRest,
                                    lineWidth: 2)
                    )
                if checked {
                    Text("✓")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                }
            }
            Text(label)
                .font(OnboardTokens.todoItem)
                .foregroundStyle(strike ? Color(hex: 0x999999) : Color(hex: 0x3D3A35))
                .strikethrough(strike)
                .frame(maxWidth: .infinity, alignment: .leading)
            trailing?()
        }
    }
}

// Step 7 — Scan → PDF ────────────────────────────────────────────
struct ScanIllustration: View {
    @Environment(\.accentPalette) private var accent

    var body: some View {
        IllustrationFrame {
            VStack(spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 6)
                        .fill(OnboardTokens.cardBg)
                        .frame(width: 80, height: 100)
                        .shadow(color: Color.black.opacity(0.12), radius: 4, x: 0, y: 2)

                    VStack(spacing: 6) {
                        ForEach(0..<4) { _ in
                            RoundedRectangle(cornerRadius: 3)
                                .fill(OnboardTokens.lineFill)
                                .frame(width: 52, height: 5)
                        }
                    }

                    CornerMarks()
                        .stroke(accent.primary, lineWidth: 2)
                        .frame(width: 80, height: 100)
                }

                Text("PDF ✓")
                    .font(OnboardTokens.badge)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 3)
                    .background(accent.primary)
                    .clipShape(Capsule())

                HStack(spacing: 5) {
                    ScanPill(label: "Capture", kind: .done)
                    ScanPill(label: "Detect",  kind: .done)
                    ScanPill(label: "Enhance", kind: .done)
                    ScanPill(label: "Save",    kind: .active)
                }
                .padding(.top, 2)
            }
        }
    }
}

private struct CornerMarks: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let inset: CGFloat = 6
        let L: CGFloat = 12
        // TL
        p.move(to: CGPoint(x: inset, y: inset + L))
        p.addLine(to: CGPoint(x: inset, y: inset))
        p.addLine(to: CGPoint(x: inset + L, y: inset))
        // TR
        p.move(to: CGPoint(x: rect.maxX - inset - L, y: inset))
        p.addLine(to: CGPoint(x: rect.maxX - inset,  y: inset))
        p.addLine(to: CGPoint(x: rect.maxX - inset,  y: inset + L))
        // BL
        p.move(to: CGPoint(x: inset, y: rect.maxY - inset - L))
        p.addLine(to: CGPoint(x: inset, y: rect.maxY - inset))
        p.addLine(to: CGPoint(x: inset + L, y: rect.maxY - inset))
        // BR
        p.move(to: CGPoint(x: rect.maxX - inset - L, y: rect.maxY - inset))
        p.addLine(to: CGPoint(x: rect.maxX - inset,  y: rect.maxY - inset))
        p.addLine(to: CGPoint(x: rect.maxX - inset,  y: rect.maxY - inset - L))
        return p
    }
}

private struct ScanPill: View {
    @Environment(\.accentPalette) private var accent
    enum Kind { case rest, done, active }
    let label: String
    let kind: Kind

    private var bg: Color {
        switch kind {
        case .rest:   return OnboardTokens.lineFill
        case .done:   return accent.soft
        case .active: return accent.primary
        }
    }
    private var fg: Color {
        switch kind {
        case .rest:   return OnboardTokens.textSubtle
        case .done:   return accent.deep
        case .active: return .white
        }
    }

    var body: some View {
        Text(label)
            .font(OnboardTokens.scanPill)
            .foregroundStyle(fg)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(bg)
            .clipShape(Capsule())
    }
}

// Step 8 — Migrate: calendar → notebook ──────────────────────────
struct MigrateIllustration: View {
    @Environment(\.accentPalette) private var accent
    private var day: Int { Calendar.current.component(.day, from: Date()) }

    var body: some View {
        IllustrationFrame {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("APR")
                        .font(OnboardTokens.calendarHeader)
                        .tracking(0.5)
                        .foregroundStyle(accent.primary)
                    Text("\(day)")
                        .font(.system(size: 22, weight: .heavy))
                        .foregroundStyle(OnboardTokens.textPrimary)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .frame(width: 64, height: 72, alignment: .leading)
                .background(OnboardTokens.cardBg)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .shadow(color: Color.black.opacity(0.10), radius: 4, x: 0, y: 2)

                Text("→")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(accent.primary)

                Text("📓")
                    .font(.system(size: 32))
                    .frame(width: 64, height: 72)
                    .background(OnboardTokens.cardBg)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .shadow(color: Color.black.opacity(0.10), radius: 4, x: 0, y: 2)
            }
        }
    }
}

// Step 9 — Backup: app → Google Drive ────────────────────────────
struct BackupIllustration: View {
    @Environment(\.accentPalette) private var accent

    var body: some View {
        IllustrationFrame {
            HStack(spacing: 16) {
                AppIconMark(size: 56)
                Text("→")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(accent.primary)
                GoogleDriveLogo(size: 36)
                    .frame(width: 56, height: 56)
                    .background(OnboardTokens.cardBg)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .shadow(color: Color.black.opacity(0.10), radius: 4, x: 0, y: 2)
            }
        }
    }
}

private struct GoogleDriveLogo: View {
    let size: CGFloat

    var body: some View {
        Canvas { ctx, canvas in
            let w = canvas.width
            let h = canvas.height
            let topX = w * 0.5
            let topY = h * 0.08
            let leftMid  = CGPoint(x: w * 0.08, y: h * 0.64)
            let rightMid = CGPoint(x: w * 0.92, y: h * 0.64)
            let leftBot  = CGPoint(x: w * 0.30, y: h * 0.92)
            let rightBot = CGPoint(x: w * 0.70, y: h * 0.92)
            let centerL  = CGPoint(x: w * 0.36, y: h * 0.62)
            let centerR  = CGPoint(x: w * 0.64, y: h * 0.62)
            let centerB  = CGPoint(x: w * 0.50, y: h * 0.66)

            func fill(_ pts: [CGPoint], _ color: Color) {
                var p = Path()
                p.move(to: pts[0])
                pts.dropFirst().forEach { p.addLine(to: $0) }
                p.closeSubpath()
                ctx.fill(p, with: .color(color))
            }

            fill([CGPoint(x: topX, y: topY), centerL, leftMid],   Color(hex: 0x00AC47)) // green
            fill([leftMid, leftBot, centerB],                    Color(hex: 0x0066DA)) // blue
            fill([CGPoint(x: topX, y: topY), centerR, rightMid], Color(hex: 0xFFBA00)) // yellow
            fill([rightMid, rightBot, centerB],                  Color(hex: 0xEA4335)) // red
            fill([leftBot, rightBot, centerB],                   Color(hex: 0x2684FC)) // light blue
        }
        .frame(width: size, height: size)
    }
}

// Step 10 — Done: checkmark circle ───────────────────────────────
struct DoneIllustration: View {
    @Environment(\.accentPalette) private var accent

    var body: some View {
        IllustrationFrame {
            ZStack {
                Circle()
                    .fill(accent.soft)
                Circle()
                    .stroke(accent.primary, lineWidth: 3)
                CheckShape()
                    .stroke(
                        accent.primary,
                        style: StrokeStyle(lineWidth: 3.5, lineCap: .round, lineJoin: .round)
                    )
                    .padding(16)
            }
            .frame(width: 72, height: 72)
        }
    }
}

private struct CheckShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.minX + rect.width * 0.08, y: rect.midY))
        p.addLine(to: CGPoint(x: rect.midX - rect.width * 0.08, y: rect.maxY - rect.height * 0.18))
        p.addLine(to: CGPoint(x: rect.maxX - rect.width * 0.02, y: rect.minY + rect.height * 0.12))
        return p
    }
}
