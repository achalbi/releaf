/*
 * PageImageLoader.swift
 *
 * Decodes one scanned page into a downsampled, upright CGImage plus
 * the 8-bit grayscale buffer `CheckboxTodoDetector` consumes. The
 * CGImage is kept for the on-device barcode pass (template marks)
 * so each page is decoded once.
 *
 * `UIImage(contentsOfFile:)` carries the EXIF orientation; drawing it
 * into a fresh context bakes the orientation in, which matches the
 * frame Vision's normalised boxes are reported in for an upright
 * page.
 *
 * Mirror of Android `PageImageLoader.kt`.
 */

import CoreGraphics
import Foundation
import UIKit

public struct LoadedPage {
    public let cgImage: CGImage
    public let gray: GrayImage
}

public enum PageImageLoader {

    /// Long-side cap — see the Android twin for the sizing rationale.
    public static let maxSide: CGFloat = 2000

    /// Returns nil when the page can't be read; callers fall back to
    /// text-only detection.
    public static func load(url: URL, maxSide: CGFloat = maxSide) -> LoadedPage? {
        guard let image = UIImage(contentsOfFile: url.path) else { return nil }
        let size = image.size
        guard size.width > 0, size.height > 0 else { return nil }
        let scale = min(1, maxSide / max(size.width, size.height))
        let w = Int((size.width * scale).rounded())
        let h = Int((size.height * scale).rounded())
        guard w > 0, h > 0 else { return nil }

        // Upright RGB render (bakes in EXIF orientation).
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let upright = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        guard let cg = upright.cgImage else { return nil }

        // Gray buffer via a DeviceGray bitmap context.
        var pixels = [UInt8](repeating: 255, count: w * h)
        let drew: Bool = pixels.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(
                data: buf.baseAddress,
                width: w,
                height: h,
                bitsPerComponent: 8,
                bytesPerRow: w,
                space: CGColorSpaceCreateDeviceGray(),
                bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drew else { return nil }
        return LoadedPage(cgImage: cg, gray: GrayImage(pixels: pixels, width: w, height: h))
    }
}
