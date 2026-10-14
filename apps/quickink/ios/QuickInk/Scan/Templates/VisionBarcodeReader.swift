/*
 * VisionBarcodeReader.swift
 *
 * Decodes QR codes on a page image with Vision's on-device barcode
 * detector and returns the raw payload strings for
 * `MarkTemplateDetector`. Runs on the CGImage `PageImageLoader`
 * already decoded for the checkbox pass, so a page is only decoded
 * once. Synchronous — call it off the main actor.
 *
 * Mirror of Android `MlKitBarcodeReader.kt`.
 */

import CoreGraphics
import Vision

public enum VisionBarcodeReader {

    /// Raw payloads of every QR code on `image`. Never throws: a
    /// Vision failure reads as "no marks", and template routing
    /// falls back to the header-words detector.
    public static func readPayloads(_ image: CGImage) -> [String] {
        let request = VNDetectBarcodesRequest()
        // QR only: the assumed mark format, and fewer false reads.
        request.symbologies = [.qr]
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return []
        }
        return (request.results ?? []).compactMap { $0.payloadStringValue }
    }
}
