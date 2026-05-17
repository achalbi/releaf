/*
 * StoryCapturePreviewImage.swift
 *
 * Shared SwiftUI view for rendering a `captures.preview_uri` JPEG
 * inside a story's reader card or the suggestion preview's first-
 * page card. Loads the file synchronously off `UIImage(contentsOfFile:)`
 * — fast enough for preview-sized JPEGs (~100 KB) and avoids the
 * complexity of an async image cache for the first iteration.
 *
 * Falls back to a cream `paper1` rectangle when the URI is nil or
 * the file isn't on disk yet (e.g., a freshly-synced capture whose
 * binary restore hasn't completed). Same visual placeholder the
 * exporters use on miss — keeps the on-screen and exported PDF/PNG
 * frames lined up.
 *
 * Android renders the same JPEG inline via `coil.compose.AsyncImage`
 * (no helper view needed — Coil's loader handles the cache + decode).
 */

import SwiftUI
import UIKit

struct StoryCapturePreviewImage: View {
    let uri: String?
    let height: CGFloat
    var cornerRadius: CGFloat = 8

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(QuickInkColors.paper1)
            if let image = loadImage() {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            }
        }
        .frame(height: height)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }

    private func loadImage() -> UIImage? {
        guard let uri = uri, !uri.isEmpty else { return nil }
        let path: String? = {
            if let url = URL(string: uri), url.isFileURL { return url.path }
            return uri
        }()
        guard let p = path, FileManager.default.fileExists(atPath: p) else { return nil }
        return UIImage(contentsOfFile: p)
    }
}
