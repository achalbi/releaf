/*
 * Placeholder.swift
 *
 * SwiftPM rejects targets with no source files. This file exists only
 * so `ReleafCoreScan` resolves while the target is empty.
 *
 * Real contents arrive in PR #4 (extract DocumentScannerView from
 * Releaf) and Phase 3 of the QuickInk spinoff plan (add the new
 * iOS-side OCR recognizer + pipeline). The target will hold:
 *   - DocumentScannerView (VisionKit wrapper, extracted from Releaf)
 *   - OcrEngine protocol + VisionTextRecognizer (Apple Vision impl)
 *   - OcrPipeline (multi-page parallel OCR with bounded concurrency)
 *   - OcrResult / OcrBlock / OcrBbox value types
 *   - SearchablePdfExporter (feature-flagged behind
 *     `searchablePdfExportEnabled`; ships off by default in v1)
 *
 * Delete this file in the same PR that lands the real sources.
 */

import Foundation

@available(*, unavailable, message: "ReleafCoreScan target is in skeleton state — see PR #4 + Phase 3")
internal enum _ReleafCoreScanPlaceholder {}
