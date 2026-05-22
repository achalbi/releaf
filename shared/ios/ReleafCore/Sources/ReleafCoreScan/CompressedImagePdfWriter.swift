/*
 * CompressedImagePdfWriter.swift
 *
 * Small image-only PDF writer used by the scanner save path. It
 * embeds color pages as opaque, downscaled JPEG streams and text-like
 * pages as 1-bit Flate-compressed image streams. That gives the save
 * pipeline an explicit size budget without changing OCR inputs.
 *
 * Mirror of `CompressedImagePdfWriter.kt` in :shared:scan.
 */

#if os(iOS)

import Foundation
import UIKit
import ReleafCoreData

public enum CompressedImagePdfWriter {

    public static let pdfMarker = "QuickInk-Compressed-PDF"
    public static let defaultMaxLongEdge: CGFloat = 1800
    public static let defaultJpegQuality: CGFloat = 0.82
    public static let defaultTargetPageBytes: Int = 250 * 1024

    private static let pdfPageOverheadBudgetBytes = 8 * 1024
    private static let minImageTargetBytes = 24 * 1024

    public static func writeToAttachment(
        images: [UIImage],
        maxLongEdge: CGFloat = defaultMaxLongEdge,
        jpegQuality: CGFloat = defaultJpegQuality,
        targetPageBytes: Int = defaultTargetPageBytes
    ) -> URL? {
        guard !images.isEmpty else { return nil }
        guard let data = makePDFData(
            images: images,
            maxLongEdge: maxLongEdge,
            jpegQuality: jpegQuality,
            targetPageBytes: targetPageBytes
        ) else { return nil }
        return AttachmentStorage.write(data, ext: "pdf")
    }

    private struct Page {
        let width: Int
        let height: Int
        let image: PDFImage

        var imageData: Data { image.data }
    }

    private enum PDFImage {
        case jpeg(Data)
        case bitonalFlate(Data)

        var data: Data {
            switch self {
            case .jpeg(let data), .bitonalFlate(let data):
                return data
            }
        }

        func dictionary(width: Int, height: Int) -> String {
            switch self {
            case .jpeg(let data):
                return "<< /Type /XObject /Subtype /Image /Width \(width) /Height \(height) " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                    "/Length \(data.count) >>"
            case .bitonalFlate(let data):
                return "<< /Type /XObject /Subtype /Image /Width \(width) /Height \(height) " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 1 /Decode [0 1] " +
                    "/Filter /FlateDecode /Length \(data.count) >>"
            }
        }
    }

    private static func makePDFData(
        images: [UIImage],
        maxLongEdge: CGFloat,
        jpegQuality: CGFloat,
        targetPageBytes: Int
    ) -> Data? {
        let pageBudget = max(1, targetPageBytes)
        let pages = images.compactMap {
            encodePage(
                image: $0,
                maxLongEdge: max(1, maxLongEdge),
                jpegQuality: min(1, max(0.01, jpegQuality)),
                targetPageBytes: pageBudget
            )
        }
        guard pages.count == images.count, !pages.isEmpty else { return nil }
        let data = buildPDFData(pages: pages)
        guard data.count < pageBudget * pages.count else { return nil }
        return data
    }

    private static func encodePage(
        image: UIImage,
        maxLongEdge: CGFloat,
        jpegQuality: CGFloat,
        targetPageBytes: Int
    ) -> Page? {
        let imageBudget = max(
            minImageTargetBytes,
            targetPageBytes - pdfPageOverheadBudgetBytes
        )
        var smallestJpeg: Page?
        var selectedJpeg: Page?

        for preset in encodingPresets(maxLongEdge: maxLongEdge, jpegQuality: jpegQuality) {
            guard let page = encodeJpegPageAttempt(
                image: image,
                maxLongEdge: preset.maxLongEdge,
                jpegQuality: preset.jpegQuality
            ) else { continue }
            if smallestJpeg == nil || page.imageData.count < smallestJpeg!.imageData.count {
                smallestJpeg = page
            }
            if selectedJpeg == nil && page.imageData.count <= imageBudget {
                selectedJpeg = page
                break
            }
        }

        guard var selected = selectedJpeg ?? smallestJpeg else { return nil }

        let analysis = analyzeDocumentScan(image: image)
        if analysis.supportsBitonal,
           let bitonal = encodeBestBitonalPage(
            image: image,
            threshold: analysis.threshold,
            maxLongEdge: maxLongEdge,
            imageBudget: imageBudget,
            jpegBaseline: selected
           ) {
            selected = bitonal
        }

        return selected
    }

    private struct EncodingPreset {
        let maxLongEdge: CGFloat
        let jpegQuality: CGFloat
    }

    private static func encodingPresets(
        maxLongEdge: CGFloat,
        jpegQuality: CGFloat
    ) -> [EncodingPreset] {
        let requestedEdge = max(1, maxLongEdge)
        let requestedQuality = min(1, max(0.01, jpegQuality))
        let raw = [
            EncodingPreset(maxLongEdge: requestedEdge, jpegQuality: requestedQuality),
            EncodingPreset(maxLongEdge: requestedEdge, jpegQuality: min(requestedQuality, 0.76)),
            EncodingPreset(maxLongEdge: min(requestedEdge, 1600), jpegQuality: min(requestedQuality, 0.72)),
            EncodingPreset(maxLongEdge: min(requestedEdge, 1400), jpegQuality: min(requestedQuality, 0.68)),
            EncodingPreset(maxLongEdge: min(requestedEdge, 1200), jpegQuality: min(requestedQuality, 0.62)),
            EncodingPreset(maxLongEdge: min(requestedEdge, 1000), jpegQuality: min(requestedQuality, 0.56)),
            EncodingPreset(maxLongEdge: min(requestedEdge, 850), jpegQuality: min(requestedQuality, 0.50)),
            EncodingPreset(maxLongEdge: min(requestedEdge, 720), jpegQuality: min(requestedQuality, 0.44)),
            EncodingPreset(maxLongEdge: min(requestedEdge, 640), jpegQuality: min(requestedQuality, 0.38)),
            EncodingPreset(maxLongEdge: min(requestedEdge, 560), jpegQuality: min(requestedQuality, 0.32)),
            EncodingPreset(maxLongEdge: min(requestedEdge, 480), jpegQuality: min(requestedQuality, 0.28)),
            EncodingPreset(maxLongEdge: min(requestedEdge, 360), jpegQuality: min(requestedQuality, 0.24)),
        ]

        var seen: Set<String> = []
        return raw.filter { preset in
            let key = "\(Int(preset.maxLongEdge.rounded())):\(Int((preset.jpegQuality * 100).rounded()))"
            return seen.insert(key).inserted
        }
    }

    private static func bitonalPresets(maxLongEdge: CGFloat) -> [CGFloat] {
        let requestedEdge = max(1, maxLongEdge)
        let raw = [
            requestedEdge,
            min(requestedEdge, 1600),
            min(requestedEdge, 1400),
            min(requestedEdge, 1200),
            min(requestedEdge, 1000),
            min(requestedEdge, 850),
            min(requestedEdge, 720),
            min(requestedEdge, 640),
            min(requestedEdge, 560),
            min(requestedEdge, 480),
            min(requestedEdge, 360),
        ]

        var seen: Set<Int> = []
        return raw.filter { edge in
            seen.insert(Int(edge.rounded())).inserted
        }
    }

    private static func encodeBestBitonalPage(
        image: UIImage,
        threshold: Int,
        maxLongEdge: CGFloat,
        imageBudget: Int,
        jpegBaseline: Page
    ) -> Page? {
        var smallest: Page?
        var selected: Page?

        for edge in bitonalPresets(maxLongEdge: maxLongEdge) {
            guard let rendered = renderRGBA(image: image, maxLongEdge: edge),
                  let data = encodeBitonalFlate(rendered: rendered, threshold: threshold)
            else { continue }

            let page = Page(
                width: rendered.width,
                height: rendered.height,
                image: .bitonalFlate(data)
            )
            if smallest == nil || page.imageData.count < smallest!.imageData.count {
                smallest = page
            }
            if selected == nil && page.imageData.count <= imageBudget {
                selected = page
                break
            }
        }

        guard let candidate = selected ?? smallest else { return nil }
        let jpegSize = jpegBaseline.imageData.count
        let bitonalSize = candidate.imageData.count
        return bitonalSize < jpegSize ? candidate : nil
    }

    private static func encodeJpegPageAttempt(
        image: UIImage,
        maxLongEdge: CGFloat,
        jpegQuality: CGFloat
    ) -> Page? {
        let sourceSize = image.size
        guard sourceSize.width > 0, sourceSize.height > 0 else { return nil }

        let longEdge = max(sourceSize.width, sourceSize.height)
        let scale = longEdge > maxLongEdge ? (maxLongEdge / longEdge) : 1
        let targetSize = CGSize(
            width:  max(1, (sourceSize.width * scale).rounded()),
            height: max(1, (sourceSize.height * scale).rounded())
        )

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        let normalized = renderer.image { context in
            UIColor.white.setFill()
            context.cgContext.fill(CGRect(origin: .zero, size: targetSize))
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }

        guard let jpegData = normalized.jpegData(compressionQuality: jpegQuality) else {
            return nil
        }

        return Page(
            width: Int(targetSize.width),
            height: Int(targetSize.height),
            image: .jpeg(jpegData)
        )
    }

    private struct RenderedRGBA {
        let width: Int
        let height: Int
        let bytes: [UInt8]
    }

    private static func renderRGBA(image: UIImage, maxLongEdge: CGFloat) -> RenderedRGBA? {
        let sourceSize = image.size
        guard sourceSize.width > 0, sourceSize.height > 0 else { return nil }

        let longEdge = max(sourceSize.width, sourceSize.height)
        let scale = longEdge > maxLongEdge ? (maxLongEdge / longEdge) : 1
        let width = max(1, Int((sourceSize.width * scale).rounded()))
        let height = max(1, Int((sourceSize.height * scale).rounded()))
        let bytesPerRow = width * 4
        var bytes = [UInt8](repeating: 255, count: bytesPerRow * height)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        var didRender = false

        bytes.withUnsafeMutableBytes { rawBuffer in
            guard let baseAddress = rawBuffer.baseAddress,
                  let context = CGContext(
                    data: baseAddress,
                    width: width,
                    height: height,
                    bitsPerComponent: 8,
                    bytesPerRow: bytesPerRow,
                    space: colorSpace,
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue |
                        CGBitmapInfo.byteOrder32Big.rawValue
                  )
            else { return }

            let rect = CGRect(x: 0, y: 0, width: width, height: height)
            context.setFillColor(UIColor.white.cgColor)
            context.fill(rect)
            UIGraphicsPushContext(context)
            image.draw(in: rect)
            UIGraphicsPopContext()
            didRender = true
        }

        guard didRender else { return nil }
        return RenderedRGBA(width: width, height: height, bytes: bytes)
    }

    private static func encodeBitonalFlate(rendered: RenderedRGBA, threshold: Int) -> Data? {
        let bytesPerPackedRow = (rendered.width + 7) / 8
        var packed = [UInt8](repeating: 0, count: bytesPerPackedRow * rendered.height)

        for y in 0..<rendered.height {
            let rowOffset = y * bytesPerPackedRow
            for x in 0..<rendered.width {
                let pixelOffset = (y * rendered.width + x) * 4
                let luminance = luminance(
                    red: Int(rendered.bytes[pixelOffset]),
                    green: Int(rendered.bytes[pixelOffset + 1]),
                    blue: Int(rendered.bytes[pixelOffset + 2])
                )
                if luminance > threshold {
                    let byteIndex = rowOffset + x / 8
                    let bit = 7 - (x % 8)
                    packed[byteIndex] |= UInt8(1 << bit)
                }
            }
        }

        return try? (Data(packed) as NSData).compressed(using: .zlib) as Data
    }

    private struct DocumentScanAnalysis {
        let supportsBitonal: Bool
        let threshold: Int
    }

    private static func analyzeDocumentScan(image: UIImage) -> DocumentScanAnalysis {
        guard let rendered = renderRGBA(image: image, maxLongEdge: 900) else {
            return DocumentScanAnalysis(supportsBitonal: false, threshold: 180)
        }

        var histogram = Array(repeating: 0, count: 256)
        var sampleStep = 1
        while (rendered.width / sampleStep) * (rendered.height / sampleStep) > 12_000 {
            sampleStep *= 2
        }

        var samples = 0
        var chromaticSamples = 0
        var y = 0
        while y < rendered.height {
            var x = 0
            while x < rendered.width {
                let pixelOffset = (y * rendered.width + x) * 4
                let red = Int(rendered.bytes[pixelOffset])
                let green = Int(rendered.bytes[pixelOffset + 1])
                let blue = Int(rendered.bytes[pixelOffset + 2])
                let value = luminance(red: red, green: green, blue: blue)
                histogram[value] += 1
                samples += 1

                let maxChannel = max(red, green, blue)
                let minChannel = min(red, green, blue)
                if maxChannel - minChannel > 36 && maxChannel > 72 {
                    chromaticSamples += 1
                }

                x += sampleStep
            }
            y += sampleStep
        }

        guard samples > 0 else {
            return DocumentScanAnalysis(supportsBitonal: false, threshold: 180)
        }

        let threshold = otsuThreshold(histogram: histogram, total: samples)
        let stats = thresholdStats(histogram: histogram, threshold: threshold)
        let chromaticRatio = Double(chromaticSamples) / Double(samples)
        let foregroundRatio = Double(stats.foregroundCount) / Double(samples)
        let backgroundRatio = Double(stats.backgroundCount) / Double(samples)
        let contrast = stats.backgroundMean - stats.foregroundMean

        let supportsBitonal =
            chromaticRatio <= 0.10 &&
            (0.003...0.55).contains(foregroundRatio) &&
            backgroundRatio >= 0.40 &&
            contrast >= 55 &&
            (70...230).contains(threshold)

        return DocumentScanAnalysis(
            supportsBitonal: supportsBitonal,
            threshold: threshold
        )
    }

    private struct ThresholdStats {
        let foregroundCount: Int
        let backgroundCount: Int
        let foregroundMean: Double
        let backgroundMean: Double
    }

    private static func thresholdStats(
        histogram: [Int],
        threshold: Int
    ) -> ThresholdStats {
        var foregroundCount = 0
        var backgroundCount = 0
        var foregroundSum = 0
        var backgroundSum = 0

        for (luminance, count) in histogram.enumerated() {
            if luminance <= threshold {
                foregroundCount += count
                foregroundSum += luminance * count
            } else {
                backgroundCount += count
                backgroundSum += luminance * count
            }
        }

        return ThresholdStats(
            foregroundCount: foregroundCount,
            backgroundCount: backgroundCount,
            foregroundMean: foregroundCount == 0 ? 0 : Double(foregroundSum) / Double(foregroundCount),
            backgroundMean: backgroundCount == 0 ? 255 : Double(backgroundSum) / Double(backgroundCount)
        )
    }

    private static func otsuThreshold(histogram: [Int], total: Int) -> Int {
        var sum = 0
        for (luminance, count) in histogram.enumerated() {
            sum += luminance * count
        }

        var backgroundWeight = 0
        var backgroundSum = 0
        var bestVariance = -1.0
        var bestThreshold = 180

        for (threshold, count) in histogram.enumerated() {
            backgroundWeight += count
            backgroundSum += threshold * count
            if backgroundWeight == 0 { continue }

            let foregroundWeight = total - backgroundWeight
            if foregroundWeight == 0 { continue }

            let foregroundSum = sum - backgroundSum
            let backgroundMean = Double(backgroundSum) / Double(backgroundWeight)
            let foregroundMean = Double(foregroundSum) / Double(foregroundWeight)
            let diff = backgroundMean - foregroundMean
            let variance = Double(backgroundWeight) * Double(foregroundWeight) * diff * diff
            if variance > bestVariance {
                bestVariance = variance
                bestThreshold = threshold
            }
        }

        return bestThreshold
    }

    private static func luminance(red: Int, green: Int, blue: Int) -> Int {
        ((red * 299) + (green * 587) + (blue * 114)) / 1000
    }

    private static func buildPDFData(pages: [Page]) -> Data {
        var data = Data()
        var offsets: [Int] = []

        func append(_ string: String) {
            data.append(contentsOf: string.utf8)
        }

        func beginObject(_ id: Int) {
            offsets.append(data.count)
            append("\(id) 0 obj\n")
        }

        let pageObjectIds = pages.indices.map { 3 + $0 * 3 }
        let objectCount = 2 + pages.count * 3

        append("%PDF-1.4\n%\(pdfMarker)\n")

        beginObject(1)
        append("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        beginObject(2)
        append("<< /Type /Pages /Count \(pages.count) /Kids [")
        for pageObjectId in pageObjectIds {
            append(" \(pageObjectId) 0 R")
        }
        append(" ] >>\nendobj\n")

        for (index, page) in pages.enumerated() {
            let pageObjectId = 3 + index * 3
            let contentObjectId = pageObjectId + 1
            let imageObjectId = pageObjectId + 2
            let imageName = "Im\(index + 1)"
            let content = "q\n\(page.width) 0 0 \(page.height) 0 0 cm\n/\(imageName) Do\nQ\n"

            beginObject(pageObjectId)
            append(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 \(page.width) \(page.height)] " +
                "/Resources << /XObject << /\(imageName) \(imageObjectId) 0 R >> >> " +
                "/Contents \(contentObjectId) 0 R >>\nendobj\n"
            )

            beginObject(contentObjectId)
            append("<< /Length \(content.utf8.count) >>\nstream\n")
            append(content)
            append("endstream\nendobj\n")

            beginObject(imageObjectId)
            append("\(page.image.dictionary(width: page.width, height: page.height))\nstream\n")
            data.append(page.imageData)
            append("\nendstream\nendobj\n")
        }

        let xrefStart = data.count
        append("xref\n0 \(objectCount + 1)\n")
        append("0000000000 65535 f \n")
        for offset in offsets {
            append(String(format: "%010d 00000 n \n", offset))
        }
        append(
            "trailer\n<< /Size \(objectCount + 1) /Root 1 0 R >>\n" +
            "startxref\n\(xrefStart)\n%%EOF\n"
        )
        return data
    }
}

#endif
