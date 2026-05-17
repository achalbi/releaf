/*
 * StoryPdfExporter.swift
 *
 * Stories Phase 4 — renders a Story + its items into a US-Letter PDF
 * via `UIGraphicsPDFRenderer`. Page 1 is the cover; subsequent pages
 * carry items. Per-page item count is driven by `StoryItem.Layout`:
 *
 *   - full → 1 item per page (default)
 *   - half → 2 items per page
 *   - grid → 4 items per page
 *
 * Day markers (from `StoryDayMarkers.derive`) render inline above
 * the first item of each new bucket. End-card lands on the final
 * page. The output is byte-deterministic for the same input + clock
 * so callers can dedupe runs.
 *
 * Mirror of Android `StoryPdfExporter.kt`.
 */

import Foundation
import UIKit

enum StoryPdfExporter {

    /// US-Letter portrait at 72 DPI. SwiftUI's PDF context uses
    /// `CGFloat` points — same coordinate space as on-screen views.
    private static let pageRect = CGRect(x: 0, y: 0, width: 612, height: 792)
    private static let margin: CGFloat = 48

    /// Render the story to a temporary file on disk; returns the
    /// file URL or throws if write fails. Caller hands the URL off
    /// to `UIActivityViewController`.
    ///
    /// `previewUris` maps each capture-backed `StoryItem.refId` to
    /// its on-disk `preview_uri`. The exporter loads + embeds the
    /// bitmap when present; missing entries fall back to a cream-box
    /// placeholder. The share sheet builds the map before calling.
    static func export(story: Story, items: [StoryItem], previewUris: [String: String] = [:]) throws -> URL {
        let renderer = UIGraphicsPDFRenderer(bounds: pageRect)
        let dir = FileManager.default.temporaryDirectory
        let safeTitle = sanitizeFileName(story.title)
        let url = dir.appendingPathComponent("\(safeTitle).pdf")

        let markers = StoryDayMarkers.derive(from: items)
        let markerByItemId = Dictionary(uniqueKeysWithValues: markers.map { ($0.precedingItemId, $0) })

        try renderer.writePDF(to: url) { ctx in
            // ---- Cover page ----
            ctx.beginPage()
            drawCover(story: story, items: items)

            // ---- Item pages ----
            let chunks = paginate(items: items)
            for chunk in chunks {
                ctx.beginPage()
                drawChunk(chunk, markerByItemId: markerByItemId, previewUris: previewUris)
            }

            // ---- End-card page ----
            ctx.beginPage()
            drawEndCard(story: story)
        }
        return url
    }

    // MARK: - Cover

    private static func drawCover(story: Story, items: [StoryItem]) {
        // Paper-warm gradient background — uses the same palette as
        // the reader's `StoryCoverColor.gradient`. Drawn manually via
        // CGContext linear gradient.
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let colors = coverColors(for: story.coverStyle)
        let gradient = CGGradient(
            colorsSpace: CGColorSpaceCreateDeviceRGB(),
            colors: [colors.0.cgColor, colors.1.cgColor] as CFArray,
            locations: [0, 1]
        )!
        let coverRect = CGRect(x: margin, y: 96, width: pageRect.width - margin * 2, height: 320)
        ctx.saveGState()
        ctx.addPath(UIBezierPath(roundedRect: coverRect, cornerRadius: 16).cgPath)
        ctx.clip()
        ctx.drawLinearGradient(
            gradient,
            start: CGPoint(x: coverRect.minX, y: coverRect.minY),
            end:   CGPoint(x: coverRect.maxX, y: coverRect.maxY),
            options: []
        )
        ctx.restoreGState()

        // Stamp (top-right of cover)
        let stampSource = story.timeRangeStart
            ?? items.first.map { $0.occurredAt ?? $0.createdAt }
            ?? story.createdAt
        let stamp = monthYearStamp(stampSource) ?? ""
        let stampAttrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.italicSystemFont(ofSize: 10),
            .foregroundColor: UIColor.darkGray,
            .kern: 2,
        ]
        (stamp as NSString).draw(
            at: CGPoint(x: coverRect.minX + 16, y: coverRect.minY + 16),
            withAttributes: stampAttrs
        )

        // Title + subtitle (bottom-left of cover)
        let titleFont = UIFont(name: "Lora-Medium", size: 28) ?? UIFont.systemFont(ofSize: 28, weight: .medium)
        let titleAttrs: [NSAttributedString.Key: Any] = [
            .font: titleFont,
            .foregroundColor: UIColor.black,
        ]
        let titleSize = (story.title as NSString).boundingRect(
            with: CGSize(width: coverRect.width - 32, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin],
            attributes: titleAttrs,
            context: nil
        ).size
        (story.title as NSString).draw(
            with: CGRect(
                x: coverRect.minX + 16,
                y: coverRect.maxY - 16 - titleSize.height - (story.subtitle?.isEmpty == false ? 26 : 0),
                width: coverRect.width - 32,
                height: titleSize.height
            ),
            options: [.usesLineFragmentOrigin],
            attributes: titleAttrs,
            context: nil
        )

        if let subtitle = story.subtitle, !subtitle.isEmpty {
            let subFont = UIFont(name: "Caveat-Medium", size: 18) ?? UIFont.italicSystemFont(ofSize: 16)
            let subAttrs: [NSAttributedString.Key: Any] = [
                .font: subFont,
                .foregroundColor: UIColor.darkGray,
            ]
            (subtitle as NSString).draw(
                at: CGPoint(x: coverRect.minX + 16, y: coverRect.maxY - 32),
                withAttributes: subAttrs
            )
        }

        // Attribution below the cover
        let attrFont = UIFont.italicSystemFont(ofSize: 11)
        let attribution = "\(items.count) items"
        let attrAttrs: [NSAttributedString.Key: Any] = [
            .font: attrFont, .foregroundColor: UIColor.darkGray,
        ]
        let attribSize = (attribution as NSString).size(withAttributes: attrAttrs)
        (attribution as NSString).draw(
            at: CGPoint(
                x: (pageRect.width - attribSize.width) / 2,
                y: coverRect.maxY + 16
            ),
            withAttributes: attrAttrs
        )
    }

    // MARK: - Items per page

    private struct Chunk {
        let items: [StoryItem]
        let perPage: Int
    }

    private static func paginate(items: [StoryItem]) -> [Chunk] {
        var out: [Chunk] = []
        var i = 0
        while i < items.count {
            let perPage = itemsPerPage(for: items[i].layout)
            let end = min(i + perPage, items.count)
            out.append(Chunk(items: Array(items[i..<end]), perPage: perPage))
            i = end
        }
        return out
    }

    private static func itemsPerPage(for layoutRaw: String) -> Int {
        switch layoutRaw {
        case StoryItem.Layout.half.rawValue: return 2
        case StoryItem.Layout.grid.rawValue: return 4
        default:                             return 1
        }
    }

    // MARK: - Chunk rendering

    private static func drawChunk(_ chunk: Chunk, markerByItemId: [String: StoryDayMarker], previewUris: [String: String]) {
        let contentRect = pageRect.insetBy(dx: margin, dy: margin)
        var yCursor = contentRect.minY

        for (idx, item) in chunk.items.enumerated() {
            if let marker = markerByItemId[item.id] {
                yCursor = drawDayMarker(marker.label, at: yCursor, width: contentRect.width, originX: contentRect.minX)
            }

            let rect: CGRect = {
                switch chunk.perPage {
                case 2:
                    let h = (contentRect.height - 32) / 2
                    return CGRect(x: contentRect.minX, y: yCursor, width: contentRect.width, height: h)
                case 4:
                    let h = (contentRect.height - 32 * 2) / 4
                    return CGRect(x: contentRect.minX, y: yCursor, width: contentRect.width, height: h)
                default:
                    return CGRect(x: contentRect.minX, y: yCursor, width: contentRect.width, height: contentRect.height - (yCursor - contentRect.minY))
                }
            }()

            drawItem(item, in: rect, previewUris: previewUris)
            yCursor = rect.maxY + 24

            if idx == chunk.items.count - 1 { break }
        }
    }

    private static func drawDayMarker(_ label: String, at y: CGFloat, width: CGFloat, originX: CGFloat) -> CGFloat {
        let font = UIFont(name: "Lora-Italic", size: 11) ?? UIFont.italicSystemFont(ofSize: 11)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor.systemOrange.withAlphaComponent(0.85),
            .kern: 1,
        ]
        let size = (label as NSString).size(withAttributes: attrs)
        (label as NSString).draw(
            at: CGPoint(x: originX, y: y),
            withAttributes: attrs
        )
        return y + size.height + 10
    }

    private static func drawItem(_ item: StoryItem, in rect: CGRect, previewUris: [String: String] = [:]) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }

        switch item.kind {
        case StoryItem.Kind.textBlock.rawValue:
            let font = UIFont(name: "Lora-Regular", size: 14) ?? UIFont.systemFont(ofSize: 14)
            let para = NSMutableParagraphStyle()
            para.lineSpacing = 4
            let attrs: [NSAttributedString.Key: Any] = [
                .font: font, .foregroundColor: UIColor.black, .paragraphStyle: para,
            ]
            (item.text ?? "").draw(with: rect, options: [.usesLineFragmentOrigin], attributes: attrs, context: nil)

        case StoryItem.Kind.handwrittenNote.rawValue:
            // Left coral rule + Caveat text
            ctx.setFillColor(UIColor.systemOrange.cgColor)
            ctx.fill(CGRect(x: rect.minX, y: rect.minY, width: 2, height: min(rect.height, 60)))
            let font = UIFont(name: "Caveat-Medium", size: 18) ?? UIFont.italicSystemFont(ofSize: 16)
            (item.text ?? "").draw(
                with: rect.insetBy(dx: 12, dy: 0).offsetBy(dx: 6, dy: 0),
                options: [.usesLineFragmentOrigin],
                attributes: [.font: font, .foregroundColor: UIColor.darkGray],
                context: nil
            )

        case StoryItem.Kind.dateDivider.rawValue:
            let font = UIFont(name: "Lora-Italic", size: 12) ?? UIFont.italicSystemFont(ofSize: 12)
            let label = item.text ?? "Date divider"
            (label as NSString).draw(
                at: CGPoint(x: rect.midX - 100, y: rect.minY),
                withAttributes: [.font: font, .foregroundColor: UIColor.systemOrange.withAlphaComponent(0.85), .kern: 1.0]
            )

        case StoryItem.Kind.placePin.rawValue:
            let font = UIFont(name: "Lora-MediumItalic", size: 13) ?? UIFont.italicSystemFont(ofSize: 13)
            ("📍 " + (item.text ?? "")).draw(
                at: CGPoint(x: rect.minX, y: rect.minY),
                withAttributes: [.font: font, .foregroundColor: UIColor.darkGray]
            )

        case StoryItem.Kind.voiceClip.rawValue:
            // Audio doesn't render to PDF; show a transcription-style
            // placeholder card. Phase 5 transcripts (if added) replace
            // the placeholder text.
            ctx.setStrokeColor(UIColor.lightGray.cgColor)
            ctx.setLineWidth(1)
            ctx.addPath(UIBezierPath(roundedRect: rect.insetBy(dx: 0, dy: 8), cornerRadius: 12).cgPath)
            ctx.strokePath()
            let font = UIFont(name: "Lora-Italic", size: 12) ?? UIFont.italicSystemFont(ofSize: 12)
            let label = "🎙  \(item.caption ?? "Voice clip — open the app to listen.")"
            (label as NSString).draw(
                at: CGPoint(x: rect.minX + 12, y: rect.minY + 20),
                withAttributes: [.font: font, .foregroundColor: UIColor.darkGray]
            )

        default:
            // Photo / document — load the capture's preview JPEG and
            // embed it inside `photoRect`. Falls back to a cream box
            // when the preview is missing or unreadable.
            let photoHeight: CGFloat = min(rect.height - 30, 360)
            let photoRect = CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: photoHeight)

            let img: UIImage? = {
                guard let refId = item.refId,
                      let uri   = previewUris[refId] else { return nil }
                return loadImage(at: uri)
            }()

            if let img = img {
                ctx.saveGState()
                ctx.addPath(UIBezierPath(roundedRect: photoRect, cornerRadius: 8).cgPath)
                ctx.clip()
                img.draw(in: aspectFill(image: img.size, into: photoRect))
                ctx.restoreGState()
            } else {
                ctx.setFillColor(UIColor(white: 0.92, alpha: 1).cgColor)
                ctx.addPath(UIBezierPath(roundedRect: photoRect, cornerRadius: 8).cgPath)
                ctx.fillPath()
            }

            if let caption = item.caption, !caption.isEmpty {
                let font = UIFont(name: "Lora-Italic", size: 12) ?? UIFont.italicSystemFont(ofSize: 12)
                (caption as NSString).draw(
                    at: CGPoint(x: rect.minX, y: photoRect.maxY + 6),
                    withAttributes: [.font: font, .foregroundColor: UIColor.darkGray]
                )
            }
        }
    }

    /// Compute the aspect-fill rect for an image of `imageSize`
    /// drawn into `target`. Used by the photo embed so portraits
    /// fill the slot without letterboxing.
    private static func aspectFill(image imageSize: CGSize, into target: CGRect) -> CGRect {
        guard imageSize.width > 0, imageSize.height > 0 else { return target }
        let scale = max(target.width / imageSize.width, target.height / imageSize.height)
        let w = imageSize.width  * scale
        let h = imageSize.height * scale
        return CGRect(
            x: target.midX - w / 2,
            y: target.midY - h / 2,
            width:  w,
            height: h
        )
    }

    /// Load a `UIImage` from a `file://` URI (or a bare path) when
    /// the file exists locally. Nil on miss.
    private static func loadImage(at uri: String) -> UIImage? {
        let path: String?
        if let url = URL(string: uri), url.isFileURL {
            path = url.path
        } else {
            path = uri
        }
        guard let p = path, FileManager.default.fileExists(atPath: p) else { return nil }
        return UIImage(contentsOfFile: p)
    }

    // MARK: - End card

    private static func drawEndCard(story: Story) {
        let endRect = CGRect(x: margin, y: 280, width: pageRect.width - margin * 2, height: 180)
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.setStrokeColor(UIColor.lightGray.cgColor)
        ctx.setLineWidth(1)
        ctx.addPath(UIBezierPath(roundedRect: endRect, cornerRadius: 14).cgPath)
        ctx.strokePath()

        let font = UIFont(name: "Lora-Italic", size: 13) ?? UIFont.italicSystemFont(ofSize: 13)
        let endLabel = "— THE END —"
        let endSize = (endLabel as NSString).size(withAttributes: [.font: font, .kern: 1.5])
        (endLabel as NSString).draw(
            at: CGPoint(x: endRect.midX - endSize.width / 2, y: endRect.midY - 12),
            withAttributes: [.font: font, .foregroundColor: UIColor.darkGray, .kern: 1.5]
        )

        let footer = "— Made with QuickInk · scan, jot, find again. —"
        let footerFont = UIFont(name: "Lora-Italic", size: 11) ?? UIFont.italicSystemFont(ofSize: 11)
        let footerSize = (footer as NSString).size(withAttributes: [.font: footerFont])
        (footer as NSString).draw(
            at: CGPoint(x: (pageRect.width - footerSize.width) / 2, y: pageRect.height - margin - 24),
            withAttributes: [.font: footerFont, .foregroundColor: UIColor.gray]
        )
    }

    // MARK: - Helpers

    private static func coverColors(for coverStyleRaw: String) -> (UIColor, UIColor) {
        switch coverStyleRaw {
        case Story.CoverStyle.gradient.rawValue:
            return (UIColor(red: 0xE0/255, green: 0x78/255, blue: 0x56/255, alpha: 1),
                    UIColor(red: 0xC6/255, green: 0x5A/255, blue: 0x3E/255, alpha: 1))
        case Story.CoverStyle.typographic.rawValue:
            return (UIColor(red: 0xEA/255, green: 0xDF/255, blue: 0xCF/255, alpha: 1),
                    UIColor(red: 0xE8/255, green: 0xDC/255, blue: 0xC4/255, alpha: 1))
        default:
            return (UIColor(red: 0xE8/255, green: 0xDC/255, blue: 0xC4/255, alpha: 1),
                    UIColor(red: 0xF0/255, green: 0xE4/255, blue: 0xD7/255, alpha: 1))
        }
    }

    private static func monthYearStamp(_ iso: String) -> String? {
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

    private static func sanitizeFileName(_ name: String) -> String {
        let invalid = CharacterSet(charactersIn: "/\\:*?\"<>|")
        let cleaned = name.unicodeScalars
            .map { invalid.contains($0) ? "-" : String($0) }
            .joined()
            .trimmingCharacters(in: .whitespaces)
        return cleaned.isEmpty ? "Story" : cleaned
    }
}
