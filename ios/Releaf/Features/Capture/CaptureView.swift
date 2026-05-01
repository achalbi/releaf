/*
 * CaptureView.swift
 *
 * The Capture page — promoted from the v6 .sheet(isPresented:) modal
 * to a real top-level destination per docs/CAPTURE_TAB_PLAN.md.
 * Mirrors features/capture/CaptureScreen.kt on Android.
 *
 * Layout (top to bottom):
 *   - Page header     :  "RELEAF" eyebrow + "Capture" serif title +
 *                        Search + Calendar icon buttons.
 *   - Day | Recents   :  Segmented control. Day = capture surface,
 *                        Recents = list of recent captures (Phase 4).
 *   - Scan hero       :  Mustard "Scan a page" card with Scan/Import.
 *   - Pre-tag chips   :  Horizontal scroll row of the user's top-N
 *                        scan-tagged categories.
 *   - "Or capture differently" divider.
 *   - 6-tile grid     :  Notes · Photo · Voice · Todo · Contact · Pin.
 *   - Footer hint     :  "Hold the green Leaf button to record voice…"
 *
 * Status:
 *   - Phase 5: ✓ scaffold mirroring Android CaptureScreen.kt.
 *               △ Pre-tag chip aggregate query needs a tagRepository
 *                  that doesn't exist yet — chips render from
 *                  `stubPretagChips` until the schema lands.
 *               △ Recents tab content is also blocked on
 *                  captureRepository; the toggle works as state but
 *                  the Recents view is a no-op for now.
 */

import SwiftUI
import ReleafDesignSystem

// MARK: - Public model

/// The six capture tiles the page surfaces. Distinct from the
/// existing [CaptureMode] enum because that's the page-detail tab
/// bar's vocabulary (which still has Overview / Scans). The Capture
/// page surfaces a curated subset + Notes (text).
public enum CaptureTile: String, CaseIterable, Identifiable, Sendable {
    case notes
    case photo
    case voice
    case todo
    case contact
    case pin

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .notes:   return "Notes"
        case .photo:   return "Photo"
        case .voice:   return "Voice"
        case .todo:    return "Todo"
        case .contact: return "Contact"
        case .pin:     return "Pin"
        }
    }

    public var hint: String {
        switch self {
        case .notes:   return "keyboard up"
        case .photo:   return "camera"
        case .voice:   return "tap or hold FAB"
        case .todo:    return "checklist"
        case .contact: return "phone · email"
        case .pin:     return "tag this place"
        }
    }

    public var systemIcon: String {
        switch self {
        case .notes:   return "square.and.pencil"
        case .photo:   return "camera"
        case .voice:   return "mic"
        case .todo:    return "checkmark.square"
        case .contact: return "person.crop.circle"
        case .pin:     return "mappin.and.ellipse"
        }
    }

    /// Translate to the existing page-detail [CaptureMode] used by
    /// `beginQuickCapture(mode:)`.
    public func toCaptureMode() -> CaptureMode {
        switch self {
        case .notes:   return .notes
        case .photo:   return .photos
        case .voice:   return .voice
        case .todo:    return .todo
        case .contact: return .contacts
        case .pin:     return .location
        }
    }
}

/// A pre-tag category chip in the horizontal-scroll row.
public struct PretagChip: Identifiable, Equatable {
    public let id: String
    public let name: String
    public let countLabel: String
    public let systemIcon: String
    public let isActive: Bool

    public init(
        id: String,
        name: String,
        countLabel: String,
        systemIcon: String,
        isActive: Bool = false
    ) {
        self.id = id
        self.name = name
        self.countLabel = countLabel
        self.systemIcon = systemIcon
        self.isActive = isActive
    }
}

public enum CaptureScope: String, CaseIterable {
    case day
    case recents
}

// MARK: - View

public struct CaptureView: View {
    private let onSelectTile: (CaptureTile) -> Void
    private let onScanNow: () -> Void
    private let onOpenSearch: () -> Void
    private let onOpenCalendar: () -> Void
    private let onSelectPretag: (PretagChip) -> Void
    private let onAddPretag: () -> Void
    private let pretagChips: [PretagChip]

    @State private var scope: CaptureScope = .day

    public init(
        onSelectTile: @escaping (CaptureTile) -> Void = { _ in },
        onScanNow: @escaping () -> Void = {},
        onOpenSearch: @escaping () -> Void = {},
        onOpenCalendar: @escaping () -> Void = {},
        onSelectPretag: @escaping (PretagChip) -> Void = { _ in },
        onAddPretag: @escaping () -> Void = {},
        pretagChips: [PretagChip] = stubPretagChips
    ) {
        self.onSelectTile = onSelectTile
        self.onScanNow = onScanNow
        self.onOpenSearch = onOpenSearch
        self.onOpenCalendar = onOpenCalendar
        self.onSelectPretag = onSelectPretag
        self.onAddPretag = onAddPretag
        self.pretagChips = pretagChips
    }

    public var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                pageHeader
                scopeRow
                ScanHeroCard(onScanNow: onScanNow)
                pretagSection
                divider
                tileGrid
                footerHint
                Spacer().frame(height: AppSpacing.s10)
            }
            .padding(.horizontal, AppSpacing.s4)
            .padding(.top, AppSpacing.s2)
        }
        .background(AppColors.canvas.ignoresSafeArea())
    }

    // MARK: Page header

    private var pageHeader: some View {
        HStack(alignment: .bottom, spacing: AppSpacing.s3) {
            VStack(alignment: .leading, spacing: 2) {
                Text("RELEAF")
                    .font(AppText.eyebrow)
                    .foregroundColor(AppColors.coral)
                Text("Capture")
                    .font(.system(size: 30, weight: .semibold, design: .serif))
                    .foregroundColor(AppColors.textPrimary)
            }
            Spacer()
            HeadIconButton(systemIcon: "magnifyingglass", label: "Search", onTap: onOpenSearch)
            HeadIconButton(systemIcon: "calendar", label: "Calendar", onTap: onOpenCalendar)
        }
    }

    // MARK: Scope row

    private var scopeRow: some View {
        HStack(alignment: .center) {
            HStack(spacing: 0) {
                ScopeSegment(label: "Day",     selected: scope == .day)     { scope = .day }
                ScopeSegment(label: "Recents", selected: scope == .recents) { scope = .recents }
            }
            .padding(3)
            .background(
                Capsule()
                    .fill(AppColors.subtle)
            )
            Spacer()
            Text(Self.todayDateLabel())
                .font(AppText.meta)
                .foregroundColor(AppColors.textTertiary)
        }
    }

    // MARK: Pretag section

    private var pretagSection: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            HStack(alignment: .lastTextBaseline) {
                Text("PRE-TAG & SCAN")
                    .font(AppText.eyebrow)
                    .foregroundColor(AppColors.textSecondary)
                Spacer()
                Text("scroll for more →")
                    .font(AppText.tag)
                    .foregroundColor(AppColors.textTertiary)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: AppSpacing.s2) {
                    ForEach(pretagChips) { chip in
                        PretagChipView(chip: chip) { onSelectPretag(chip) }
                    }
                    AddPretagChipView(onTap: onAddPretag)
                }
                .padding(.vertical, 2)
            }
        }
    }

    // MARK: Divider

    private var divider: some View {
        HStack(spacing: AppSpacing.s3) {
            Rectangle()
                .fill(AppColors.borderDefault)
                .frame(height: 1)
            Text("OR CAPTURE DIFFERENTLY")
                .font(AppText.eyebrow)
                .foregroundColor(AppColors.textTertiary)
            Rectangle()
                .fill(AppColors.borderDefault)
                .frame(height: 1)
        }
    }

    // MARK: Tile grid

    private var tileGrid: some View {
        let cols = Array(
            repeating: GridItem(.flexible(), spacing: AppSpacing.s2),
            count: 3
        )
        return LazyVGrid(columns: cols, spacing: AppSpacing.s2) {
            ForEach(CaptureTile.allCases) { tile in
                CaptureTileView(tile: tile) { onSelectTile(tile) }
            }
        }
    }

    // MARK: Footer

    private var footerHint: some View {
        HStack {
            Spacer()
            Text("Hold the green Leaf button on any tab to record voice without leaving.")
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(AppColors.textTertiary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .padding(.top, AppSpacing.s2)
    }

    private static func todayDateLabel() -> String {
        let f = DateFormatter()
        f.dateFormat = "EEEE · MMM d"
        return f.string(from: Date())
    }
}

// MARK: - Sub-views

private struct HeadIconButton: View {
    let systemIcon: String
    let label: String
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            Image(systemName: systemIcon)
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(AppColors.textPrimary)
                .frame(width: 36, height: 36)
                .background(
                    Circle()
                        .fill(AppColors.cardSolid)
                )
                .overlay(
                    Circle()
                        .strokeBorder(AppColors.borderDefault, lineWidth: 1)
                )
                .appShadow(.sm)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }
}

private struct ScopeSegment: View {
    let label: String
    let selected: Bool
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(selected ? AppColors.canvas : AppColors.textSecondary)
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(
                    Capsule()
                        .fill(selected ? scopeActiveBg : Color.clear)
                )
        }
        .buttonStyle(.plain)
    }
}

private let scopeActiveBg = Color(red: 0x1E / 255.0, green: 0x59 / 255.0, blue: 0x43 / 255.0)

// MARK: Scan hero

private struct ScanHeroCard: View {
    let onScanNow: () -> Void

    private static let mustardLight = Color(red: 0xC6 / 255.0, green: 0x86 / 255.0, blue: 0x28 / 255.0)
    private static let mustard      = Color(red: 0xB2 / 255.0, green: 0x7A / 255.0, blue: 0x2A / 255.0)
    private static let mustardDeep  = Color(red: 0x8E / 255.0, green: 0x5F / 255.0, blue: 0x1F / 255.0)

    var body: some View {
        ZStack(alignment: .topLeading) {
            // Background gradient.
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [Self.mustardLight, Self.mustard, Self.mustardDeep],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )

            // Right-side decoration: brackets + rotated paper.
            ScanHeroDecorations()
                .frame(width: 110, height: 130)
                .padding(.top, 16)
                .padding(.trailing, 16)
                .frame(maxWidth: .infinity, alignment: .topTrailing)

            // Left-side text + buttons.
            VStack(alignment: .leading, spacing: 6) {
                Text("TODAY'S FIRST CAPTURE")
                    .font(.system(size: 10.5, weight: .bold))
                    .tracking(1.0)
                    .foregroundColor(AppColors.canvas.opacity(0.78))
                Text("Scan a page")
                    .font(.system(size: 28, weight: .bold, design: .serif))
                    .foregroundColor(AppColors.textPrimary)
                Text("Auto-crops, OCRs, and files under today.")
                    .font(.system(size: 13.5))
                    .foregroundColor(AppColors.canvas)
                    .lineLimit(2)
                Spacer().frame(height: AppSpacing.s2)
                // Single primary action — Import was dropped to keep
                // the hero focused on the live scanner. Re-add when an
                // import-from-Photos flow is built.
                ScanButtonPrimary(onTap: onScanNow)
            }
            .frame(maxWidth: .infinity * 0.62, alignment: .leading)
            .padding(.horizontal, AppSpacing.s5)
            .padding(.top, AppSpacing.s5)
            .padding(.bottom, AppSpacing.s4)
        }
        .appShadow(.md)
    }
}

private struct ScanHeroDecorations: View {
    var body: some View {
        ZStack {
            // Crop-corner brackets.
            CropCornersShape()
                .stroke(Color.white.opacity(0.82), style: .init(lineWidth: 2.5, lineCap: .round))

            // Rotated paper card with text lines.
            RotatedPaper()
                .frame(width: 78, height: 96)
        }
    }
}

private struct CropCornersShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let len: CGFloat = 18
        let w = rect.width
        let h = rect.height
        // top-left
        p.move(to: CGPoint(x: 0, y: 0));     p.addLine(to: CGPoint(x: len, y: 0))
        p.move(to: CGPoint(x: 0, y: 0));     p.addLine(to: CGPoint(x: 0, y: len))
        // top-right
        p.move(to: CGPoint(x: w, y: 0));     p.addLine(to: CGPoint(x: w - len, y: 0))
        p.move(to: CGPoint(x: w, y: 0));     p.addLine(to: CGPoint(x: w, y: len))
        // bottom-left
        p.move(to: CGPoint(x: 0, y: h));     p.addLine(to: CGPoint(x: len, y: h))
        p.move(to: CGPoint(x: 0, y: h));     p.addLine(to: CGPoint(x: 0, y: h - len))
        // bottom-right
        p.move(to: CGPoint(x: w, y: h));     p.addLine(to: CGPoint(x: w - len, y: h))
        p.move(to: CGPoint(x: w, y: h));     p.addLine(to: CGPoint(x: w, y: h - len))
        return p
    }
}

private struct RotatedPaper: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 6, style: .continuous)
            .fill(AppColors.canvas)
            .overlay(
                PaperLines()
                    .stroke(
                        AppColors.textTertiary.opacity(0.4),
                        style: .init(lineWidth: 2, lineCap: .round)
                    )
                    .padding(EdgeInsets(top: 16, leading: 12, bottom: 16, trailing: 14))
            )
            .rotationEffect(.degrees(8))
            .appShadow(.md)
    }
}

private struct PaperLines: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let widths: [CGFloat] = [1.0, 0.78, 1.0, 0.86, 1.0, 0.66]
        let gap: CGFloat = 8
        for (i, frac) in widths.enumerated() {
            let y = CGFloat(i) * gap + 4
            p.move(to: CGPoint(x: 0, y: y))
            p.addLine(to: CGPoint(x: rect.width * frac, y: y))
        }
        return p
    }
}

private struct ScanButtonPrimary: View {
    let onTap: () -> Void
    private let mustardDeep = Color(red: 0x8E / 255.0, green: 0x5F / 255.0, blue: 0x1F / 255.0)
    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Image(systemName: "camera")
                    .font(.system(size: 12, weight: .bold))
                Text("Scan now")
                    .font(.system(size: 13, weight: .bold))
            }
            .foregroundColor(mustardDeep)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Capsule().fill(AppColors.canvas))
            .appShadow(.sm)
        }
        .buttonStyle(.plain)
    }
}

// MARK: Pretag chips

private struct PretagChipView: View {
    let chip: PretagChip
    let onTap: () -> Void

    private static let letterBg     = Color(red: 0xF0 / 255.0, green: 0xDD / 255.0, blue: 0xA8 / 255.0)
    private static let letterDeep   = Color(red: 0x8E / 255.0, green: 0x5F / 255.0, blue: 0x1F / 255.0)
    private static let forestLeaf   = Color(red: 0x1E / 255.0, green: 0x59 / 255.0, blue: 0x43 / 255.0)

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: AppSpacing.s2) {
                Image(systemName: chip.systemIcon)
                    .font(.system(size: 18, weight: .regular))
                    .foregroundColor(chip.isActive ? Self.letterDeep : Self.forestLeaf)
                VStack(alignment: .leading, spacing: 1) {
                    Text(chip.name)
                        .font(.system(size: 12.5, weight: .bold))
                        .foregroundColor(AppColors.textPrimary)
                    Text(chip.countLabel)
                        .font(.system(size: 10.5, weight: chip.isActive ? .semibold : .regular))
                        .foregroundColor(chip.isActive ? Self.letterDeep : AppColors.textTertiary)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md)
                    .fill(chip.isActive ? Self.letterBg : AppColors.cardSolid)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md)
                    .strokeBorder(
                        chip.isActive ? Self.letterDeep.opacity(0.25) : AppColors.borderDefault,
                        lineWidth: 1
                    )
            )
            .appShadow(.sm)
        }
        .buttonStyle(.plain)
    }
}

private struct AddPretagChipView: View {
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            HStack(spacing: AppSpacing.s2) {
                Image(systemName: "plus")
                    .font(.system(size: 18, weight: .regular))
                    .foregroundColor(AppColors.textSecondary)
                Text("New tag")
                    .font(.system(size: 12.5, weight: .bold))
                    .foregroundColor(AppColors.textSecondary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md)
                    .strokeBorder(AppColors.borderStrong, style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: Capture tile

private struct CaptureTileView: View {
    let tile: CaptureTile
    let onTap: () -> Void

    var body: some View {
        let palette = tilePalette(for: tile)
        Button(action: onTap) {
            VStack(spacing: 6) {
                Image(systemName: tile.systemIcon)
                    .font(.system(size: 17, weight: .regular))
                    .foregroundColor(palette.fg)
                    .frame(width: 34, height: 34)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(palette.bg)
                    )
                Text(tile.title.uppercased())
                    .font(.system(size: 12, weight: .heavy))
                    .tracking(0.4)
                    .foregroundColor(AppColors.textPrimary)
                Text(tile.hint)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(AppColors.textTertiary)
            }
            .frame(maxWidth: .infinity)
            .aspectRatio(1.05, contentMode: .fit)
            .padding(AppSpacing.s2)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.lg)
                    .fill(AppColors.cardSolid)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.lg)
                    .strokeBorder(AppColors.borderDefault, lineWidth: 1)
            )
            .appShadow(.sm)
        }
        .buttonStyle(.plain)
    }

    private func tilePalette(for tile: CaptureTile) -> (bg: Color, fg: Color) {
        // Phase 1 self-contained palette colors. Phase 4 routes
        // these through `design-tokens.json` semantic.* roles.
        let info        = Color(red: 0x2E / 255.0, green: 0x6F / 255.0, blue: 0xB5 / 255.0)
        let infoSoft    = Color(red: 0xE1 / 255.0, green: 0xEC / 255.0, blue: 0xF8 / 255.0)
        let forest      = Color(red: 0x1E / 255.0, green: 0x59 / 255.0, blue: 0x43 / 255.0)
        let forestTint  = Color(red: 0xE8 / 255.0, green: 0xF0 / 255.0, blue: 0xE2 / 255.0)
        let warning     = Color(red: 0xA8 / 255.0, green: 0x74 / 255.0, blue: 0x18 / 255.0)
        let warningSoft = Color(red: 0xFB / 255.0, green: 0xEE / 255.0, blue: 0xCD / 255.0)
        let danger      = Color(red: 0xC8 / 255.0, green: 0x43 / 255.0, blue: 0x2E / 255.0)
        let dangerSoft  = Color(red: 0xFD / 255.0, green: 0xEE / 255.0, blue: 0xE9 / 255.0)
        switch tile {
        case .notes:   return (AppColors.subtle,    AppColors.textSecondary)
        case .photo:   return (AppColors.coralSoft, AppColors.coral)
        case .voice:   return (infoSoft,            info)
        case .todo:    return (forestTint,          forest)
        case .contact: return (warningSoft,         warning)
        case .pin:     return (dangerSoft,          danger)
        }
    }
}

// MARK: Stub data

public let stubPretagChips: [PretagChip] = [
    PretagChip(id: "t-letter",  name: "Letter",  countLabel: "3 this week", systemIcon: "doc.text",         isActive: true),
    PretagChip(id: "t-receipt", name: "Receipt", countLabel: "12 total",    systemIcon: "doc.append"),
    PretagChip(id: "t-medical", name: "Medical", countLabel: "5 total",     systemIcon: "cross.case"),
    PretagChip(id: "t-recipe",  name: "Recipe",  countLabel: "8",           systemIcon: "fork.knife"),
    PretagChip(id: "t-school",  name: "School",  countLabel: "4",           systemIcon: "graduationcap"),
]

// MARK: - Preview

#if DEBUG
struct CaptureView_Previews: PreviewProvider {
    static var previews: some View {
        CaptureView()
            .previewDisplayName("Releaf · Capture · v7")
    }
}
#endif
