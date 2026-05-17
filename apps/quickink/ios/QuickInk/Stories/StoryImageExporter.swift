/*
 * StoryImageExporter.swift
 *
 * Stories Phase 4 — composites a Story + items into a single tall
 * PNG ("a tall PNG for chats or stories", §7.5 mockup). Capped at 12
 * items per image; if a story has more, the export truncates and the
 * last visible row gets a "+ N more in the app" footer line.
 *
 * Rendering goes through `UIGraphicsImageRenderer` at @2x for retina
 * sharpness. The output is byte-stable for the same input.
 *
 * Mirror of Android `StoryImageExporter.kt`.
 */

import Foundation
import UIKit

enum StoryImageExporter {

    /// Item cap per the handoff doc.
    private static let maxItems = 12
    /// Canvas width (1080 px is the standard "story" aspect).
    private static let width: CGFloat  = 1080
    /// Padding around the page content.
    private static let pad: CGFloat    = 56

    /// Render to a temp PNG on disk; returns the file URL.
    /// See `StoryPdfExporter.export` for the `previewUris` contract.
    static func export(story: Story, items: [StoryItem], previewUris: [String: String] = [:]) throws -> URL {
        let limited = Array(items.prefix(maxItems))
        let overflow = max(0, items.count - limited.count)
        let height = computeHeight(items: limited, hasOverflow: overflow > 0)

        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: width, height: height),
            format: { let f = UIGraphicsImageRendererFormat(); f.scale = 2; return f }()
        )
        let image = renderer.image { rendererCtx in
            draw(story: story, items: limited, overflow: overflow, previewUris: previewUris, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        guard let data = image.pngData() else {
            throw NSError(domain: "StoryImageExporter", code: 1)
        }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(sanitizeFileName(story.title)).png")
        try data.write(to: url, options: .atomic)
        return url
    }

    // MARK: - Layout

    private static func computeHeight(items: [StoryItem], hasOverflow: Bool) -> CGFloat {
        // Rough vertical budget: cover (560) + per-item heights + end-card.
        var total: CGFloat = 560 + 60 // cover + attribution
        for item in items {
            total += rowHeight(for: item)
        }
        if hasOverflow { total += 56 }
        total += 200 // end-card
        return total
    }

    private static func rowHeight(for item: StoryItem) -> CGFloat {
        switch item.kind {
        case StoryItem.Kind.textBlock.rawValue:        return 120
        case StoryItem.Kind.handwrittenNote.rawValue:  return 96
        case StoryItem.Kind.dateDivider.rawValue:      return 56
        case StoryItem.Kind.placePin.rawValue:         return 48
        case StoryItem.Kind.voiceClip.rawValue:        return 96
        default:
            switch item.layout {
            case StoryItem.Layout.half.rawValue: return 320
            case StoryItem.Layout.grid.rawValue: return 240
            default:                             return 520
            }
        }
    }

    // MARK: - Drawing

    private static func draw(story: Story, items: [StoryItem], overflow: Int, previewUris: [String: String], in rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }

        // ---- Canvas background (warm cream) ----
        ctx.setFillColor(UIColor(red: 0xFB/255, green: 0xF6/255, blue: 0xEE/255, alpha: 1).cgColor)
        ctx.fill(rect)

        // ---- Cover (560 tall) ----
        let coverRect = CGRect(x: pad, y: pad, width: rect.width - pad * 2, height: 480)
        let colors = coverColors(for: story.coverStyle)
        let gradient = CGGradient(
            colorsSpace: CGColorSpaceCreateDeviceRGB(),
            colors: [colors.0.cgColor, colors.1.cgColor] as CFArray,
            locations: [0, 1]
        )!
        ctx.saveGState()
        ctx.addPath(UIBezierPath(roundedRect: coverRect, cornerRadius: 24).cgPath)
        ctx.clip()
        ctx.drawLinearGradient(gradient,
            start: CGPoint(x: coverRect.minX, y: coverRect.minY),
            end:   CGPoint(x: coverRect.maxX, y: coverRect.maxY),
            options: [])
        ctx.restoreGState()

        // Title + subtitle bottom-left
        let titleFont = UIFont(name: "Lora-Medium", size: 60) ?? UIFont.systemFont(ofSize: 60, weight: .medium)
        let titleAttrs: [NSAttributedString.Key: Any] = [
            .font: titleFont, .foregroundColor: UIColor.black,
        ]
        let titleRect = CGRect(
            x: coverRect.minX + 32,
            y: coverRect.maxY - 160,
            width: coverRect.width - 64,
            height: 120
        )
        (story.title as NSString).draw(
            with: titleRect,
            options: [.usesLineFragmentOrigin],
            attributes: titleAttrs,
            context: nil
        )
        if let subtitle = story.subtitle, !subtitle.isEmpty {
            let subFont = UIFont(name: "Caveat-Medium", size: 36) ?? UIFont.italicSystemFont(ofSize: 32)
            (subtitle as NSString).draw(
                at: CGPoint(x: coverRect.minX + 32, y: coverRect.maxY - 64),
                withAttributes: [.font: subFont, .foregroundColor: UIColor(white: 0.2, alpha: 1)]
            )
        }

        // ---- Attribution ----
        var y: CGFloat = coverRect.maxY + 40
        let attrFont = UIFont(name: "Lora-Italic", size: 22) ?? UIFont.italicSystemFont(ofSize: 22)
        let attribution = "\(items.count) items"
        let attrAttrs: [NSAttributedString.Key: Any] = [.font: attrFont, .foregroundColor: UIColor.darkGray]
        let attribSize = (attribution as NSString).size(withAttributes: attrAttrs)
        (attribution as NSString).draw(
            at: CGPoint(x: (rect.width - attribSize.width) / 2, y: y),
            withAttributes: attrAttrs
        )
        y += attribSize.height + 32

        // ---- Items + sticky day markers ----
        let markers = StoryDayMarkers.derive(from: items)
        let markerByItemId = Dictionary(uniqueKeysWithValues: markers.map { ($0.precedingItemId, $0) })
        let contentLeft  = pad
        let contentRight = rect.width - pad
        let contentWidth = contentRight - contentLeft

        for item in items {
            if let marker = markerByItemId[item.id] {
                let mfont = UIFont(name: "Lora-Italic", size: 20) ?? UIFont.italicSystemFont(ofSize: 20)
                (marker.label as NSString).draw(
                    at: CGPoint(x: contentLeft, y: y),
                    withAttributes: [
                        .font: mfont,
                        .foregroundColor: UIColor.systemOrange.withAlphaComponent(0.85),
                        .kern: 1,
                    ]
                )
                y += 36
            }
            let itemRect = CGRect(x: contentLeft, y: y, width: contentWidth, height: rowHeight(for: item))
            drawRow(item, in: itemRect, previewUris: previewUris)
            y += itemRect.height + 16
        }

        if overflow > 0 {
            let font = UIFont(name: "Lora-Italic", size: 20) ?? UIFont.italicSystemFont(ofSize: 20)
            let msg = "+ \(overflow) more in the app"
            let size = (msg as NSString).size(withAttributes: [.font: font])
            (msg as NSString).draw(
                at: CGPoint(x: (rect.width - size.width) / 2, y: y),
                withAttributes: [.font: font, .foregroundColor: UIColor.gray]
            )
            y += 48
        }

        // ---- End-card + footer ----
        y += 32
        let endRect = CGRect(x: pad, y: y, width: contentWidth, height: 96)
        ctx.setStrokeColor(UIColor.lightGray.cgColor)
        ctx.setLineWidth(1)
        ctx.addPath(UIBezierPath(roundedRect: endRect, cornerRadius: 24).cgPath)
        ctx.strokePath()
        let endFont = UIFont(name: "Lora-Italic", size: 22) ?? UIFont.italicSystemFont(ofSize: 22)
        let endLabel = "— THE END —"
        let endSize = (endLabel as NSString).size(withAttributes: [.font: endFont, .kern: 2])
        (endLabel as NSString).draw(
            at: CGPoint(x: endRect.midX - endSize.width / 2, y: endRect.midY - 14),
            withAttributes: [.font: endFont, .foregroundColor: UIColor.darkGray, .kern: 2]
        )
        y = endRect.maxY + 24
        let footer = "— Made with QuickInk —"
        let footerFont = UIFont(name: "Lora-Italic", size: 18) ?? UIFont.italicSystemFont(ofSize: 18)
        let footerSize = (footer as NSString).size(withAttributes: [.font: footerFont])
        (footer as NSString).draw(
            at: CGPoint(x: (rect.width - footerSize.width) / 2, y: y),
            withAttributes: [.font: footerFont, .foregroundColor: UIColor.gray]
        )
    }

    private static func drawRow(_ item: StoryItem, in rect: CGRect, previewUris: [String: String] = [:]) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        switch item.kind {
        case StoryItem.Kind.textBlock.rawValue:
            let font = UIFont(name: "Lora-Regular", size: 22) ?? UIFont.systemFont(ofSize: 22)
            let para = NSMutableParagraphStyle(); para.lineSpacing = 6
            (item.text ?? "").draw(
                with: rect, options: [.usesLineFragmentOrigin],
                attributes: [.font: font, .foregroundColor: UIColor.black, .paragraphStyle: para],
                context: nil
            )
        case StoryItem.Kind.handwrittenNote.rawValue:
            ctx.setFillColor(UIColor.systemOrange.cgColor)
            ctx.fill(CGRect(x: rect.minX, y: rect.minY, width: 4, height: rect.height))
            let font = UIFont(name: "Caveat-Medium", size: 28) ?? UIFont.italicSystemFont(ofSize: 28)
            (item.text ?? "").draw(
                with: rect.insetBy(dx: 20, dy: 0).offsetBy(dx: 12, dy: 8),
                options: [.usesLineFragmentOrigin],
                attributes: [.font: font, .foregroundColor: UIColor.darkGray],
                context: nil
            )
        case StoryItem.Kind.dateDivider.rawValue:
            let font = UIFont(name: "Lora-Italic", size: 22) ?? UIFont.italicSystemFont(ofSize: 22)
            let label = item.text ?? "Date divider"
            (label as NSString).draw(
                at: CGPoint(x: rect.minX, y: rect.minY),
                withAttributes: [.font: font, .foregroundColor: UIColor.systemOrange.withAlphaComponent(0.85), .kern: 1.5]
            )
        case StoryItem.Kind.placePin.rawValue:
            let font = UIFont(name: "Lora-MediumItalic", size: 22) ?? UIFont.italicSystemFont(ofSize: 22)
            ("📍 " + (item.text ?? "")).draw(
                at: CGPoint(x: rect.minX, y: rect.minY),
                withAttributes: [.font: font, .foregroundColor: UIColor.darkGray]
            )
        case StoryItem.Kind.voiceClip.rawValue:
            ctx.setStrokeColor(UIColor.lightGray.cgColor); ctx.setLineWidth(1)
            ctx.addPath(UIBezierPath(roundedRect: rect, cornerRadius: 16).cgPath); ctx.strokePath()
            let font = UIFont(name: "Lora-Italic", size: 20) ?? UIFont.italicSystemFont(ofSize: 20)
            ("🎙  " + (item.caption ?? "Voice clip")).draw(
                at: CGPoint(x: rect.minX + 24, y: rect.midY - 12),
                withAttributes: [.font: font, .foregroundColor: UIColor.darkGray]
            )
        default:
            let photoHeight: CGFloat = max(120, rect.height - 60)
            let photoRect = CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: photoHeight)

            let img: UIImage? = {
                guard let refId = item.refId,
                      let uri   = previewUris[refId] else { return nil }
                return loadImage(at: uri)
            }()

            if let img = img {
                ctx.saveGState()
                ctx.addPath(UIBezierPath(roundedRect: photoRect, cornerRadius: 16).cgPath)
                ctx.clip()
                img.draw(in: aspectFill(image: img.size, into: photoRect))
                ctx.restoreGState()
            } else {
                ctx.setFillColor(UIColor(white: 0.92, alpha: 1).cgColor)
                ctx.addPath(UIBezierPath(roundedRect: photoRect, cornerRadius: 16).cgPath)
                ctx.fillPath()
            }

            if let caption = item.caption, !caption.isEmpty {
                let font = UIFont(name: "Lora-Italic", size: 20) ?? UIFont.italicSystemFont(ofSize: 20)
                (caption as NSString).draw(
                    at: CGPoint(x: rect.minX, y: photoRect.maxY + 12),
                    withAttributes: [.font: font, .foregroundColor: UIColor.darkGray]
                )
            }
        }
    }

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

    // MARK: - Helpers (mirror StoryPdfExporter)

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

    private static func sanitizeFileName(_ name: String) -> String {
        let invalid = CharacterSet(charactersIn: "/\\:*?\"<>|")
        let cleaned = name.unicodeScalars
            .map { invalid.contains($0) ? "-" : String($0) }
            .joined()
            .trimmingCharacters(in: .whitespaces)
        return cleaned.isEmpty ? "Story" : cleaned
    }
}
