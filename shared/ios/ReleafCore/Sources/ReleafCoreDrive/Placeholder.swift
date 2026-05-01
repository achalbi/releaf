/*
 * Placeholder.swift
 *
 * SwiftPM rejects targets with no source files. This file exists only
 * so `ReleafCoreDrive` resolves while the target is empty.
 *
 * Real contents arrive in PR #3b (sync extract) and PR #4. The target
 * will hold:
 *   - DriveClient (protocol + URLSessionDriveClient + InMemoryDriveClient)
 *   - DriveClientPath
 *   - PdfExporter (refactored to take a generic page-shape, not Releaf's Page)
 *
 * Delete this file in the same PR that lands the real sources.
 */

import Foundation

@available(*, unavailable, message: "ReleafCoreDrive target is in skeleton state — see PR #3b/#4")
internal enum _ReleafCoreDrivePlaceholder {}
