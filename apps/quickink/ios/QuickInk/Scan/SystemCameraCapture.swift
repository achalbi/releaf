/*
 * SystemCameraCapture.swift
 *
 * UIKit-backed system camera picker used by the footer Sundial Photo /
 * Video rays. iOS does not allow third-party apps to launch the
 * standalone Camera app and receive the captured file back; the
 * supported native flow is `UIImagePickerController` with
 * `sourceType = .camera`.
 *
 * Returned media is promoted into the same capture pipeline as the
 * in-app surfaces: photos become `source="photo"` captures, videos
 * become `source="video"` captures and
 * use their first frame as the capture preview and store the raw
 * movie URL on `captures.video_uri`.
 */

import AVFoundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import ReleafCoreData

enum SystemCameraCaptureMode: String, Identifiable {
    case photo
    case video

    var id: String { rawValue }
}

struct SystemCameraCapturePicker: UIViewControllerRepresentable {
    let mode: SystemCameraCaptureMode
    let onPhotoCaptured: (UIImage) -> Void
    let onVideoCaptured: (URL) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera)
            ? .camera
            : .photoLibrary
        picker.allowsEditing = false

        switch mode {
        case .photo:
            picker.mediaTypes = [UTType.image.identifier]
            if picker.sourceType == .camera {
                picker.cameraCaptureMode = .photo
            }
        case .video:
            picker.mediaTypes = [UTType.movie.identifier]
            if picker.sourceType == .camera {
                picker.cameraCaptureMode = .video
            }
            picker.videoMaximumDuration = 120
            picker.videoQuality = .typeHigh
        }

        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(
            mode:            mode,
            onPhotoCaptured: onPhotoCaptured,
            onVideoCaptured: onVideoCaptured,
            onCancel:        onCancel
        )
    }

    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let mode: SystemCameraCaptureMode
        let onPhotoCaptured: (UIImage) -> Void
        let onVideoCaptured: (URL) -> Void
        let onCancel: () -> Void

        init(
            mode: SystemCameraCaptureMode,
            onPhotoCaptured: @escaping (UIImage) -> Void,
            onVideoCaptured: @escaping (URL) -> Void,
            onCancel: @escaping () -> Void
        ) {
            self.mode = mode
            self.onPhotoCaptured = onPhotoCaptured
            self.onVideoCaptured = onVideoCaptured
            self.onCancel = onCancel
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            switch mode {
            case .photo:
                guard let image = info[.originalImage] as? UIImage else {
                    onCancel()
                    return
                }
                onPhotoCaptured(image)

            case .video:
                guard
                    let sourceURL = info[.mediaURL] as? URL,
                    let copiedURL = Self.copyVideoToTemporaryFile(sourceURL)
                else {
                    onCancel()
                    return
                }
                onVideoCaptured(copiedURL)
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onCancel()
        }

        private static func copyVideoToTemporaryFile(_ sourceURL: URL) -> URL? {
            let dir = FileManager.default.temporaryDirectory
                .appendingPathComponent("system_camera", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let ext = sourceURL.pathExtension.isEmpty ? "mov" : sourceURL.pathExtension
            let dest = dir.appendingPathComponent("capture-\(UUID().uuidString).\(ext)")
            do {
                if FileManager.default.fileExists(atPath: dest.path) {
                    try FileManager.default.removeItem(at: dest)
                }
                try FileManager.default.copyItem(at: sourceURL, to: dest)
                return dest
            } catch {
                NSLog("[SystemCameraCapture] video temp copy failed: %@", "\(error)")
                return nil
            }
        }
    }
}

enum SystemCameraCaptureCommit {
    @MainActor
    static func commitPhoto(
        image: UIImage,
        controller: ScanFlowController
    ) async -> Bool {
        guard let result = ImportArtifacts.build(from: [image]) else {
            return false
        }

        controller.onScanComplete(
            pdfURL:     result.pdfURL,
            previewURL: result.previewURL,
            pageURLs:   result.pageURLs,
            source:     "photo",
            paperSize:  .custom
        )
        return true
    }

    @MainActor
    static func commitVideo(
        videoURL: URL,
        controller: ScanFlowController
    ) async -> Bool {
        defer { try? FileManager.default.removeItem(at: videoURL) }

        let artifact = await Task.detached(priority: .userInitiated) {
            buildVideoArtifact(videoURL: videoURL)
        }.value
        guard let artifact else {
            return false
        }

        controller.onScanComplete(
            pdfURL:     artifact.result.pdfURL,
            previewURL: artifact.result.previewURL,
            pageURLs:   artifact.result.pageURLs,
            source:     "video",
            paperSize:  .custom
        )

        let captureId: String? = {
            if case .recognizing(let id, _, _) = controller.state { return id }
            return nil
        }()

        guard let captureId else { return true }
        let landed = await waitForCaptureRow(id: captureId, timeoutMs: 8_000)
        if landed {
            do {
                try await CaptureRepository().setVideoUri(
                    captureId: captureId,
                    videoUri:  artifact.videoURL.absoluteString
                )
            } catch {
                NSLog("[SystemCameraCapture] setVideoUri failed: %@", "\(error)")
            }
        }
        return true
    }

    private struct VideoArtifact {
        let result: ImportArtifacts.Result
        let videoURL: URL
    }

    private static func buildVideoArtifact(videoURL: URL) -> VideoArtifact? {
        let asset = AVURLAsset(url: videoURL)
        guard let frame = extractFirstFrame(from: asset) else { return nil }
        guard let result = ImportArtifacts.build(from: [frame]) else { return nil }
        guard
            let data = try? Data(contentsOf: videoURL),
            let storedVideo = AttachmentStorage.write(data, ext: "mov")
        else {
            return nil
        }
        return VideoArtifact(result: result, videoURL: storedVideo)
    }

    private static func extractFirstFrame(from asset: AVURLAsset) -> UIImage? {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        let time = CMTime(seconds: 0, preferredTimescale: 600)
        do {
            let cgImage = try generator.copyCGImage(at: time, actualTime: nil)
            return UIImage(cgImage: cgImage)
        } catch {
            NSLog("[SystemCameraCapture] first-frame extract failed: %@", "\(error)")
            return nil
        }
    }

    private static func waitForCaptureRow(id: String, timeoutMs: Int) async -> Bool {
        let pollMs = 100
        var elapsed = 0
        let repo = CaptureRepository()
        while elapsed < timeoutMs {
            let exists = (try? await repo.exists(captureId: id)) ?? false
            if exists { return true }
            try? await Task.sleep(nanoseconds: UInt64(pollMs * 1_000_000))
            elapsed += pollMs
        }
        return false
    }
}
