/*
 * CaptureListViewModel.swift
 *
 * Observable for the home-screen "Recent" rail. Streams captures
 * (newest first) so the rail surfaces the actual scanned document
 * thumbnail right after a scan completes — replacing the earlier
 * mock lined-paper rendering tied to notepad entries.
 *
 * Lightweight — only the fields needed to render a thumb + open
 * the detail viewer (id, preview URI, category, created_at). The
 * full row + OCR text load lazily inside `ScanDetailScreen`.
 */

import Foundation
import Combine
import GRDB

public struct CaptureSummary: Codable, FetchableRecord, Equatable, Sendable, Identifiable {
    public let id: String
    public let previewUri: String?
    public let pdfUri: String
    public let category: String?
    public let pageCount: Int
    public let createdAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case previewUri = "preview_uri"
        case pdfUri     = "pdf_uri"
        case category
        case pageCount  = "page_count"
        case createdAt  = "created_at"
    }
}

@MainActor
public final class CaptureListViewModel: ObservableObject {

    @Published public private(set) var captures: [CaptureSummary] = []

    private let dbQueue: DatabaseQueue
    private let userId: String
    private var cancellable: AnyCancellable?

    public init(database: QuickInkDatabase = .shared, userId: String) {
        self.dbQueue = database.dbQueue
        self.userId  = userId
    }

    public func start() {
        cancellable = ValueObservation.tracking { [userId] db in
            try CaptureSummary.fetchAll(db, sql: """
                SELECT id, preview_uri, pdf_uri, category, page_count, created_at
                FROM captures
                WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY created_at DESC
                LIMIT 30
                """, arguments: [userId])
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(
            receiveCompletion: { _ in },
            receiveValue: { [weak self] in self?.captures = $0 }
        )
    }
}
