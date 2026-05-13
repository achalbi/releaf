/*
 * WorkspaceDocThumbnail.swift
 *
 * Shared 44×56 thumbnail tile for Workspace doc rows
 * (FolderDetailScreen, SmartCollectionScreen, future folder-tag
 * lists). Loads the preview JPEG via UIImage when the URI resolves
 * to a file://; falls back to a soft-border placeholder otherwise.
 *
 * Mirror of Android's `DocRowThumbnail` composable in
 * FolderDetailScreen.kt.
 */

import SwiftUI
import ReleafCoreDesignSystem

/// Larger 56×70 thumbnail variant used by the Workspace home
/// Continue card. Same loader as the doc-row tile.
struct ContinueCardThumbnail: View {
    let previewUri: String?
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 6)
        return shape
            .fill(QuickInkColors.bg)
            .frame(width: 56, height: 70)
            .overlay(
                Group {
                    if let uri = previewUri, !uri.isEmpty,
                       let image = workspaceLoadPreview(from: uri) {
                        Image(uiImage: image)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    }
                }
            )
            .clipShape(shape)
    }
}

private func workspaceLoadPreview(from uri: String) -> UIImage? {
    let url: URL?
    if uri.hasPrefix("file://") {
        url = URL(string: uri)
    } else if uri.hasPrefix("/") {
        url = URL(fileURLWithPath: uri)
    } else {
        url = URL(string: uri) ?? URL(fileURLWithPath: uri)
    }
    guard let resolved = url else { return nil }
    return UIImage(contentsOfFile: resolved.path)
}

struct WorkspaceDocThumbnail: View {
    let previewUri: String?

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 7)
        return shape
            .fill(QuickInkColors.borderSoft)
            .frame(width: 44, height: 56)
            .overlay(
                Group {
                    if let uri = previewUri, !uri.isEmpty,
                       let image = workspaceLoadPreview(from: uri) {
                        Image(uiImage: image)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    }
                }
            )
            .clipShape(shape)
    }
}
