/*
 * PdfExporter.swift
 *
 * Renders a `Page` to a single-page (or multi-page if it overflows)
 * letter-size PDF and writes it into `.documentDirectory`. Returns
 * the URL the page was written to so the caller can hand it to a
 * share sheet.
 *
 * The output is plain editorial: green eyebrow + serif title +
 * dateline, then the page's notes set as serif body, then a small
 * "captures summary" block at the bottom (counts per kind). It's
 * intentionally not a pixel-faithful render of the in-app screen —
 * users sharing PDFs of their pages want a readable doc, not a
 * screenshot. The visual language stays Releaf — same warm cream,
 * coral leaf, dot-grid suggestion — at print densities.
 *
 * Pure UIKit/CoreGraphics, no SwiftUI. iOS 16 floor; the renderer
 * APIs predate that comfortably.
 */

import Foundation
import UIKit

public enum PdfExporter {

    public static let pageWidth: CGFloat  = 612   // 8.5"
    public static let pageHeight: CGFloat = 792   // 11"
    public static let margin: CGFloat     = 56

    /// Render `page` to a fresh file under `.documentDirectory` and
    /// return the URL. Filename pattern: `page-{id}-{timestamp}.pdf`.
    public static func export(page: Page) throws -> URL {
        let renderer = UIGraphicsPDFRenderer(
            bounds: CGRect(x: 0, y: 0, width: pageWidth, height: pageHeight)
        )
        let data = renderer.pdfData { ctx in
            ctx.beginPage()
            var y = margin
            y = drawHeader(in: ctx.cgContext, page: page, top: y)
            y += 24
            y = drawNotes(in: ctx.cgContext, page: page, top: y, ctx: ctx)
            y += 24
            _ = drawCapturesSummary(in: ctx.cgContext, page: page, top: y)
        }

        let url = try documentsURL(for: page)
        try data.write(to: url, options: .atomic)
        return url
    }

    // MARK: - Sections

    private static func drawHeader(
        in cg: CGContext,
        page: Page,
        top: CGFloat
    ) -> CGFloat {
        var y = top
        let eyebrow = "RELEAF · PAGE"
        let eyebrowFont = UIFont.systemFont(ofSize: 9, weight: .medium)
        let eyebrowAttrs: [NSAttributedString.Key: Any] = [
            .font: eyebrowFont,
            .foregroundColor: themeGreenDeep,
            .kern: 1.6,
        ]
        let eyebrowSize = (eyebrow as NSString).size(withAttributes: eyebrowAttrs)
        (eyebrow as NSString).draw(at: CGPoint(x: margin, y: y), withAttributes: eyebrowAttrs)

        // Coral leaf glyph next to eyebrow
        let glyph = leafPath(at: CGPoint(x: margin + eyebrowSize.width + 6, y: y + 1),
                             height: eyebrowSize.height - 2)
        cg.setFillColor(coral.cgColor)
        cg.addPath(glyph)
        cg.fillPath()

        y += eyebrowSize.height + 8

        // Title
        let titleFont = UIFont.systemFont(ofSize: 28, weight: .regular)
        let titleAttrs: [NSAttributedString.Key: Any] = [
            .font: serifVariant(of: titleFont),
            .foregroundColor: ink,
        ]
        let titleRect = CGRect(x: margin, y: y, width: pageWidth - 2 * margin, height: 80)
        (page.title as NSString).draw(in: titleRect, withAttributes: titleAttrs)
        y += titleFont.lineHeight + 4

        // Dateline + notebook context
        if let captured = page.capturedOn {
            let metaFont = serifVariant(of: UIFont.italicSystemFont(ofSize: 13))
            let metaAttrs: [NSAttributedString.Key: Any] = [
                .font: metaFont,
                .foregroundColor: inkSoft,
            ]
            (captured as NSString).draw(at: CGPoint(x: margin, y: y), withAttributes: metaAttrs)
            y += metaFont.lineHeight + 6
        }

        // Hairline rule
        cg.setStrokeColor(borderRule.cgColor)
        cg.setLineWidth(0.5)
        cg.move(to: CGPoint(x: margin, y: y + 8))
        cg.addLine(to: CGPoint(x: pageWidth - margin, y: y + 8))
        cg.strokePath()

        return y + 16
    }

    private static func drawNotes(
        in cg: CGContext,
        page: Page,
        top: CGFloat,
        ctx: UIGraphicsPDFRendererContext
    ) -> CGFloat {
        var y = top
        guard !page.notes.isEmpty else {
            let muted = UIFont.italicSystemFont(ofSize: 13)
            ("(no notes)" as NSString).draw(
                at: CGPoint(x: margin, y: y),
                withAttributes: [.font: muted, .foregroundColor: inkSoft]
            )
            return y + muted.lineHeight
        }

        let bodyFont = serifVariant(of: UIFont.systemFont(ofSize: 13))
        let attrs: [NSAttributedString.Key: Any] = [
            .font: bodyFont,
            .foregroundColor: ink,
        ]
        for (idx, note) in page.notes.enumerated() {
            let bullet = "—"
            (bullet as NSString).draw(
                at: CGPoint(x: margin, y: y),
                withAttributes: [.font: bodyFont, .foregroundColor: themeGreenDeep]
            )
            let textRect = CGRect(
                x: margin + 16,
                y: y,
                width: pageWidth - 2 * margin - 16,
                height: pageHeight - margin - y
            )
            let body = NSString(string: note.body)
            let bounding = body.boundingRect(
                with: CGSize(width: textRect.width, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: attrs,
                context: nil
            )
            // Page-break safety — if this paragraph won't fit, end the page.
            if y + bounding.height > pageHeight - margin {
                ctx.beginPage()
                y = margin
            }
            body.draw(in: textRect, withAttributes: attrs)
            y += bounding.height + (idx == page.notes.count - 1 ? 0 : 12)
        }
        return y
    }

    private static func drawCapturesSummary(
        in cg: CGContext,
        page: Page,
        top: CGFloat
    ) -> CGFloat {
        let counts = page.counts
        let pieces: [String] = [
            counts.photos > 0           ? "\(counts.photos) photo\(counts.photos == 1 ? "" : "s")" : nil,
            counts.scannedDocuments > 0 ? "\(counts.scannedDocuments) scan\(counts.scannedDocuments == 1 ? "" : "s")" : nil,
            counts.voiceNotes > 0       ? "\(counts.voiceNotes) voice note\(counts.voiceNotes == 1 ? "" : "s")" : nil,
            counts.todoItems > 0        ? "\(counts.todoItems) to-do\(counts.todoItems == 1 ? "" : "s")" : nil,
            counts.contacts > 0         ? "\(counts.contacts) contact\(counts.contacts == 1 ? "" : "s")" : nil,
            counts.locations > 0        ? "\(counts.locations) place\(counts.locations == 1 ? "" : "s")" : nil,
        ].compactMap { $0 }

        guard !pieces.isEmpty else { return top }

        let eyebrow = "CAPTURES"
        let eyebrowFont = UIFont.systemFont(ofSize: 9, weight: .medium)
        let eyebrowAttrs: [NSAttributedString.Key: Any] = [
            .font: eyebrowFont,
            .foregroundColor: inkSoft,
            .kern: 1.6,
        ]
        var y = top
        (eyebrow as NSString).draw(at: CGPoint(x: margin, y: y), withAttributes: eyebrowAttrs)
        y += eyebrowFont.lineHeight + 4

        let summary = pieces.joined(separator: " · ")
        let summaryFont = serifVariant(of: UIFont.systemFont(ofSize: 13))
        (summary as NSString).draw(
            at: CGPoint(x: margin, y: y),
            withAttributes: [.font: summaryFont, .foregroundColor: ink]
        )
        return y + summaryFont.lineHeight
    }

    // MARK: - Helpers

    private static func documentsURL(for page: Page) throws -> URL {
        let docs = try FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let safeId = page.id.replacingOccurrences(of: "/", with: "-")
        let stamp = Int(Date().timeIntervalSince1970)
        return docs.appendingPathComponent("page-\(safeId)-\(stamp).pdf")
    }

    /// Returns a serif version of `font` at the same point size +
    /// weight. Falls back to the original font if the system can't
    /// find a serif variant (rare; iOS ships New York / Times).
    private static func serifVariant(of font: UIFont) -> UIFont {
        let descriptor = font.fontDescriptor.withDesign(.serif) ?? font.fontDescriptor
        return UIFont(descriptor: descriptor, size: font.pointSize)
    }

    /// A small leaf-shaped path centered on `at` with the given
    /// height. Used as the inline glyph next to the eyebrow.
    private static func leafPath(at origin: CGPoint, height: CGFloat) -> CGPath {
        let path = CGMutablePath()
        let w = height * 0.7
        path.move(to: CGPoint(x: origin.x + w / 2, y: origin.y))
        path.addQuadCurve(
            to: CGPoint(x: origin.x + w / 2, y: origin.y + height),
            control: CGPoint(x: origin.x + w * 1.15, y: origin.y + height * 0.45)
        )
        path.addQuadCurve(
            to: CGPoint(x: origin.x + w / 2, y: origin.y),
            control: CGPoint(x: origin.x - w * 0.15, y: origin.y + height * 0.45)
        )
        return path
    }

    // MARK: - Tokens (light-mode hex literals)
    //
    // The PDF doesn't run through `dynamicColor` — it's a static
    // file shared off-device. We pin to the light-mode Releaf
    // palette so what the user sees in their PDF reader matches the
    // brand on a printed page.

    private static let ink           = UIColor(red: 0x46/255, green: 0x3C/255, blue: 0x31/255, alpha: 1)
    private static let inkSoft       = UIColor(red: 0x5F/255, green: 0x52/255, blue: 0x45/255, alpha: 1)
    private static let coral         = UIColor(red: 0xE0/255, green: 0x78/255, blue: 0x56/255, alpha: 1)
    private static let themeGreenDeep = UIColor(red: 0x5B/255, green: 0x8C/255, blue: 0x52/255, alpha: 1)
    private static let borderRule    = UIColor(red: 0x50/255, green: 0x3E/255, blue: 0x2D/255, alpha: 0.24)
}
