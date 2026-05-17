/*
 * StoryReaderScreen.swift
 *
 * Stories Phase 3 — the reader (§7.4 of the v3 mockup). Tapping the
 * editor's "Preview" button opens the story in this layout; once
 * Phase 6 lands, recipients of a published Drive link also see this
 * screen (identical render, different host).
 *
 *   ┌─────────────────────────┐
 *   │ ░ MAY 2026 ░░░░░░░░░░░  │   ← cover gradient + stamp
 *   │   Tokyo, May 2026       │   ← serif title
 *   │   a week of noodles…    │   ← handwritten subtitle
 *   ├─────────────────────────┤
 *   │   by Achal · 15 items   │   ← attribution
 *   │                         │
 *   │  ─ MAY 4 · EVENING ─    │   ← sticky day marker
 *   │  We landed Sunday…      │   ← text block
 *   │  ┌─────────────────┐    │
 *   │  │ photo           │    │   ← reader item
 *   │  │ Landing at…     │    │
 *   │  └─────────────────┘    │
 *   │  │ First udon shop…    │   ← pull quote (left rule)
 *   │  ─ MAY 5 · DUSK ─       │
 *   │  …                      │
 *   │                         │
 *   │  ┌─ THE END ─┐          │
 *   │  │ Reply  │ Make own │  │   ← end card actions
 *   │  └─────────────────────┘ │
 *   │  Made with QuickInk —   │
 *   └─────────────────────────┘
 *
 * Mirror of Android `StoryReaderScreen.kt`.
 */

import CoreImage
import GRDB
import SwiftUI
import UIKit

struct StoryReaderScreen: View {

    let storyId: String
    let userId: String
    var onBack: () -> Void

    @StateObject private var vm: StoryEditorViewModel
    @State private var toastMessage: String? = nil
    /// Computed via Core Image's `CIAreaAverage` on the cover_item's
    /// preview JPEG. Cached after the first compute keyed by capture
    /// id; missing or compute-failed → nil → falls back to the static
    /// palette in `StoryCoverColor.gradient(for:)`.
    @State private var dominantCoverColor: UIColor? = nil
    @State private var lastSampledCaptureId: String? = nil

    init(storyId: String, userId: String, onBack: @escaping () -> Void) {
        self.storyId = storyId
        self.userId  = userId
        self.onBack  = onBack
        // The reader reuses the editor's VM for the live story + item
        // observation. Reads only — no writes happen here.
        _vm = StateObject(wrappedValue: StoryEditorViewModel(storyId: storyId, userId: userId))
    }

    var body: some View {
        ZStack(alignment: .top) {
            QuickInkColors.bg.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(spacing: 0) {
                    coverCard
                    attribution
                    bodySections
                    endCard
                    footer
                }
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s6)
            }

            backButton

            if let toast = toastMessage {
                Text(toast)
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(QuickInkColors.surface))
                    .overlay(Capsule().strokeBorder(QuickInkColors.border, lineWidth: 0.5))
                    .shadow(color: QuickInkColors.ink.opacity(0.08), radius: 6, y: 2)
                    .padding(.top, 60)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .task {
            vm.start()
        }
        .onChange(of: vm.story?.coverItemId) { _ in
            Task { await refreshDominantCoverColor() }
        }
        .onChange(of: vm.items.first?.id) { _ in
            // When items load, if cover_item_id is unset, sample
            // from the first item as a default hero.
            Task { await refreshDominantCoverColor() }
        }
    }

    /// Resolve `story.coverItemId` → `story_item.refId` → capture
    /// `preview_uri` → dominant colour. Falls back to the first
    /// item's capture when cover_item_id isn't set.
    private func refreshDominantCoverColor() async {
        let coverItemId = vm.story?.coverItemId
        let firstItemId = vm.items.first?.id
        let targetItemId = coverItemId ?? firstItemId
        guard let itemId = targetItemId,
              let item = vm.items.first(where: { $0.id == itemId }),
              let captureId = item.refId
        else { return }
        if captureId == lastSampledCaptureId { return }
        lastSampledCaptureId = captureId

        let colour = await Task.detached(priority: .userInitiated) {
            await StoryCoverColor.dominantColor(captureId: captureId)
        }.value
        await MainActor.run { self.dominantCoverColor = colour }
    }

    // MARK: - Back button

    private var backButton: some View {
        HStack {
            Button(action: onBack) {
                ZStack {
                    Circle()
                        .fill(QuickInkColors.surface.opacity(0.92))
                        .frame(width: 36, height: 36)
                        .shadow(color: QuickInkColors.ink.opacity(0.12), radius: 4, y: 1)
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(QuickInkColors.ink)
                }
            }
            .buttonStyle(.plain)
            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.top, QuickInkSpacing.s3)
    }

    // MARK: - Cover

    private var coverCard: some View {
        ZStack(alignment: .bottomLeading) {
            // Cover gradient. When a dominant colour has been
            // extracted from the cover hero photo, use it; otherwise
            // fall back to the static palette keyed by `coverStyle`.
            StoryCoverColor.gradient(for: vm.story?.coverStyle, dominant: dominantCoverColor)
            // Subtle dark fade at bottom for legibility of title text.
            LinearGradient(
                colors: [.clear, QuickInkColors.ink.opacity(0.25)],
                startPoint: .top,
                endPoint: .bottom
            )

            VStack(alignment: .leading, spacing: 4) {
                Text(coverStamp)
                    .font(QuickInkText.bodyItalic)
                    .tracking(2)
                    .foregroundStyle(QuickInkColors.inkSoft)
                Text(vm.story?.title ?? "Untitled story")
                    .font(QuickInkFont.serif(28, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .multilineTextAlignment(.leading)
                if let subtitle = vm.story?.subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(QuickInkFont.handwritten(15))
                        .foregroundStyle(QuickInkColors.ink.opacity(0.85))
                }
            }
            .padding(QuickInkSpacing.s3 + 2)
        }
        .frame(height: 200)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.top, QuickInkSpacing.s5 + 12)
        .padding(.bottom, QuickInkSpacing.s3)
    }

    private var coverStamp: String {
        // Prefer the story's `time_range_start`; fall back to its
        // earliest item's effective date; fall back to its createdAt.
        let iso: String? = vm.story?.timeRangeStart
            ?? vm.items.first.map { $0.occurredAt ?? $0.createdAt }
            ?? vm.story?.createdAt
        guard let iso = iso, let month = StoryCoverColor.monthYearStamp(from: iso) else {
            return ""
        }
        return month
    }

    // MARK: - Attribution

    private var attribution: some View {
        Text("\(itemCount) items")
            .font(QuickInkText.bodyItalic)
            .foregroundStyle(QuickInkColors.inkSoft)
            .padding(.bottom, QuickInkSpacing.s3)
    }

    private var itemCount: Int {
        // Active items only (deleted_at filtered by the VM).
        vm.items.count
    }

    // MARK: - Body sections

    private var bodySections: some View {
        let markers = StoryDayMarkers.derive(from: vm.items)
        // Build an id → marker dictionary for O(1) lookup while
        // rendering items in order.
        let markerByItemId = Dictionary(uniqueKeysWithValues: markers.map { ($0.precedingItemId, $0) })
        return VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            ForEach(vm.items, id: \.id) { item in
                if let marker = markerByItemId[item.id] {
                    dayMarkerView(marker.label)
                }
                readerRow(item)
            }
        }
    }

    private func dayMarkerView(_ label: String) -> some View {
        Text(label)
            .font(QuickInkFont.serif(12, weight: .regular, italic: true))
            .tracking(1)
            .foregroundStyle(QuickInkColors.accentDeep)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, QuickInkSpacing.s2)
            .padding(.bottom, QuickInkSpacing.s2 - 2)
            .background(QuickInkColors.bg)
    }

    @ViewBuilder
    private func readerRow(_ item: StoryItem) -> some View {
        switch item.kind {
        case StoryItem.Kind.textBlock.rawValue:
            Text(item.text ?? "")
                .font(QuickInkFont.serif(14, weight: .regular))
                .lineSpacing(4)
                .foregroundStyle(QuickInkColors.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 4)
                .padding(.vertical, QuickInkSpacing.s1 + 2)

        case StoryItem.Kind.handwrittenNote.rawValue:
            HStack {
                Rectangle()
                    .fill(QuickInkColors.accent)
                    .frame(width: 2)
                Text(item.text ?? "")
                    .font(QuickInkFont.handwritten(16))
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, QuickInkSpacing.s2)
            }
            .padding(.vertical, QuickInkSpacing.s1 + 2)

        case StoryItem.Kind.dateDivider.rawValue:
            HStack {
                Rectangle().fill(QuickInkColors.border).frame(height: 0.5)
                Text(item.text ?? "")
                    .font(QuickInkFont.serif(12, weight: .medium, italic: true))
                    .tracking(1)
                    .foregroundStyle(QuickInkColors.accentDeep)
                    .padding(.horizontal, QuickInkSpacing.s3)
                Rectangle().fill(QuickInkColors.border).frame(height: 0.5)
            }
            .padding(.vertical, QuickInkSpacing.s2)

        case StoryItem.Kind.placePin.rawValue:
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "mappin.and.ellipse")
                    .foregroundStyle(QuickInkColors.accent)
                Text(item.text ?? "")
                    .font(QuickInkFont.serif(13, weight: .medium, italic: true))
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            .padding(.vertical, QuickInkSpacing.s1)

        case StoryItem.Kind.voiceClip.rawValue:
            readerCard {
                HStack(spacing: QuickInkSpacing.s2) {
                    ZStack {
                        Circle().fill(QuickInkColors.accentSoft).frame(width: 36, height: 36)
                        Image(systemName: "waveform")
                            .foregroundStyle(QuickInkColors.accent)
                    }
                    Text(item.caption ?? "Voice clip")
                        .font(QuickInkFont.serif(12, weight: .regular, italic: true))
                        .foregroundStyle(QuickInkColors.inkSoft)
                    Spacer()
                }
                .padding(QuickInkSpacing.s2 + 2)
            }

        default:
            readerCard {
                VStack(alignment: .leading, spacing: 6) {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(QuickInkColors.paper1)
                        .frame(height: photoHeight(for: item.layout))
                    if let caption = item.caption, !caption.isEmpty {
                        Text(caption)
                            .font(QuickInkFont.serif(12, weight: .regular, italic: true))
                            .foregroundStyle(QuickInkColors.inkSoft)
                            .lineSpacing(2)
                    }
                }
                .padding(QuickInkSpacing.s2)
            }
        }
    }

    private func photoHeight(for layoutRaw: String) -> CGFloat {
        switch layoutRaw {
        case StoryItem.Layout.half.rawValue: return 80
        case StoryItem.Layout.grid.rawValue: return 64
        default:                             return 140
        }
    }

    private func readerCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(QuickInkColors.surface)
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .padding(.bottom, QuickInkSpacing.s1 + 2)
    }

    // MARK: - End card

    private var endCard: some View {
        VStack(spacing: QuickInkSpacing.s2 + 2) {
            Text("— THE END —")
                .font(QuickInkFont.serif(13, weight: .regular, italic: true))
                .tracking(1.5)
                .foregroundStyle(QuickInkColors.inkSoft)

            HStack(spacing: 6) {
                Button(action: { flashToast("Reply target lands in v1.1 — stay tuned.") }) {
                    Text("Reply with a note")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(QuickInkColors.bg)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10).strokeBorder(QuickInkColors.border, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)

                Button(action: { flashToast("Make your own — coming in Phase 4 share sheet.") }) {
                    Text("Make your own")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(QuickInkColors.textOnAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(QuickInkColors.accent, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(QuickInkSpacing.s3 + 2)
        .frame(maxWidth: .infinity)
        .background(QuickInkColors.surface)
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.top, QuickInkSpacing.s3)
    }

    private var footer: some View {
        Text("— Made with QuickInk · scan, jot, find again. —")
            .font(QuickInkText.bodyItalic)
            .foregroundStyle(QuickInkColors.muted)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.top, QuickInkSpacing.s3)
    }

    private func flashToast(_ message: String) {
        toastMessage = message
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            toastMessage = nil
        }
    }
}

// MARK: - Cover gradient

/// Static cover-gradient palette keyed by `Story.CoverStyle`. Phase 3
/// ships this fallback; the Vision-based dominant-colour extraction
/// from the hero photo (per the handoff doc's task 3.4) lands as a
/// follow-up — it needs to load the cover_item_id's preview image off
/// disk, run `CIAreaAverage`, and cache the result on the story row.
enum StoryCoverColor {

    /// Static-palette gradient keyed by `Story.CoverStyle`. When
    /// `dominant` is provided (Vision-extracted colour from the
    /// cover hero photo), it takes precedence — paired with a
    /// lightened variant for the two-stop gradient.
    static func gradient(for coverStyleRaw: String?, dominant: UIColor? = nil) -> LinearGradient {
        if let dominant = dominant {
            let start = Color(dominant.lightened(by: 0.10))
            let end   = Color(dominant.darkened(by:  0.10))
            return LinearGradient(
                colors:     [start, end],
                startPoint: .topLeading,
                endPoint:   .bottomTrailing
            )
        }
        switch coverStyleRaw {
        case Story.CoverStyle.gradient.rawValue:
            return LinearGradient(
                colors: [QuickInkColors.accent, QuickInkColors.accentDeep],
                startPoint: .topLeading,
                endPoint:   .bottomTrailing
            )
        case Story.CoverStyle.typographic.rawValue:
            return LinearGradient(
                colors: [QuickInkColors.paper3, QuickInkColors.paper1],
                startPoint: .topLeading,
                endPoint:   .bottomTrailing
            )
        default:
            return LinearGradient(
                colors: [QuickInkColors.paper1, QuickInkColors.paper2],
                startPoint: .topLeading,
                endPoint:   .bottomTrailing
            )
        }
    }

    /// Resolve a capture id to its preview JPEG on disk and run
    /// `CIAreaAverage` to extract a single representative colour.
    /// Returns nil when the capture is missing, the preview file
    /// isn't on disk, or the filter fails. Cheap enough to call on
    /// the main thread for small previews; callers in this codebase
    /// wrap in a `Task.detached`.
    static func dominantColor(captureId: String) async -> UIColor? {
        let previewUri = await readPreviewUri(captureId: captureId)
        guard let uri = previewUri else { return nil }
        return dominantColor(previewUri: uri)
    }

    static func dominantColor(previewUri: String) -> UIColor? {
        let path: String? = {
            if let url = URL(string: previewUri), url.isFileURL { return url.path }
            return previewUri
        }()
        guard let p = path, FileManager.default.fileExists(atPath: p),
              let img = UIImage(contentsOfFile: p),
              let cg  = img.cgImage else { return nil }
        let ci = CIImage(cgImage: cg)
        let filter = CIFilter(name: "CIAreaAverage")
        filter?.setValue(ci, forKey: kCIInputImageKey)
        filter?.setValue(CIVector(cgRect: ci.extent), forKey: kCIInputExtentKey)
        guard let out = filter?.outputImage else { return nil }
        let context = CIContext(options: [.workingColorSpace: NSNull()])
        var rgba = [UInt8](repeating: 0, count: 4)
        context.render(
            out,
            toBitmap: &rgba,
            rowBytes: 4,
            bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
            format: .RGBA8,
            colorSpace: CGColorSpaceCreateDeviceRGB()
        )
        return UIColor(
            red:   CGFloat(rgba[0]) / 255.0,
            green: CGFloat(rgba[1]) / 255.0,
            blue:  CGFloat(rgba[2]) / 255.0,
            alpha: 1
        )
    }

    private static func readPreviewUri(captureId: String) async -> String? {
        await Task.detached(priority: .userInitiated) {
            (try? await QuickInkDatabase.shared.dbQueue.read { db -> String? in
                try Row.fetchOne(
                    db,
                    sql: "SELECT preview_uri FROM captures WHERE id = ? AND deleted_at IS NULL LIMIT 1",
                    arguments: [captureId]
                ).flatMap { $0["preview_uri"] as String? }
            }) ?? nil
        }.value
    }

    /// "MAY 2026" stamp — uppercase, letter-spaced, used by the cover.
    static func monthYearStamp(from iso: String) -> String? {
        let formatters: [ISO8601DateFormatter] = [
            { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f }(),
            { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime]; return f }(),
        ]
        for fmt in formatters {
            if let date = fmt.date(from: iso) {
                let df = DateFormatter()
                df.dateFormat = "MMM yyyy"
                return df.string(from: date).uppercased()
            }
        }
        return nil
    }
}

private extension UIColor {
    /// Mix this colour with white by `fraction` (0…1). Used to
    /// generate the top stop of the cover gradient from the
    /// dominant-extracted mid-stop.
    func lightened(by fraction: CGFloat) -> UIColor {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return UIColor(
            red:   min(1.0, r + (1.0 - r) * fraction),
            green: min(1.0, g + (1.0 - g) * fraction),
            blue:  min(1.0, b + (1.0 - b) * fraction),
            alpha: a
        )
    }
    func darkened(by fraction: CGFloat) -> UIColor {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return UIColor(
            red:   max(0, r * (1.0 - fraction)),
            green: max(0, g * (1.0 - fraction)),
            blue:  max(0, b * (1.0 - fraction)),
            alpha: a
        )
    }
}
