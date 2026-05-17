/*
 * StoryLibraryPickerSheet.swift
 *
 * Stories Phase 2 follow-up — single-select capture picker over the
 * user's library. Opens from the "Choose a photo" / "Choose a
 * document" rows in `StoryAddSheet`; filters by `captures.source`
 * (`import` for photo, `scan` for document) and returns the picked
 * capture id to the caller.
 *
 * Single-select keeps the UX one-row-at-a-time per the §7.3 editor
 * pattern: tap +Add → pick → insert → repeat. Multi-select is the
 * v1.1 polish per `STORIES_HANDOFF.md` §10.
 *
 * Mirror of Android `StoryLibraryPickerSheet.kt`.
 */

import GRDB
import SwiftUI

struct StoryLibraryPickerSheet: View {

    enum Filter: String {
        case photo
        case document
        case any
    }

    let userId: String
    let filter: Filter
    var onPick: (_ captureId: String) -> Void
    var onCancel: () -> Void

    @State private var rows: [PickerRow] = []
    @State private var loading: Bool = true

    var body: some View {
        VStack(spacing: 0) {
            handle
            header
            content
        }
        .background(QuickInkColors.surface)
        .task { await load() }
    }

    private var handle: some View {
        Capsule()
            .fill(QuickInkColors.border)
            .frame(width: 38, height: 4)
            .padding(.top, QuickInkSpacing.s2)
            .padding(.bottom, QuickInkSpacing.s3)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(headerTitle)
                .font(QuickInkText.editorial)
                .foregroundStyle(QuickInkColors.ink)
            Text(headerSubtitle)
                .font(QuickInkText.bodyItalic)
                .foregroundStyle(QuickInkColors.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s3)
    }

    @ViewBuilder
    private var content: some View {
        if loading {
            HStack {
                Spacer()
                ProgressView().padding(.vertical, QuickInkSpacing.s5)
                Spacer()
            }
        } else if rows.isEmpty {
            Text("Nothing in your library yet. Scan or import first.")
                .font(QuickInkText.bodyItalic)
                .foregroundStyle(QuickInkColors.inkSoft)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s4)
        } else {
            ScrollView(showsIndicators: false) {
                VStack(spacing: QuickInkSpacing.s2) {
                    ForEach(rows, id: \.id) { row in
                        Button(action: { onPick(row.id) }) {
                            pickerRow(row)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s6)
            }
        }
    }

    private func pickerRow(_ row: PickerRow) -> some View {
        HStack(spacing: QuickInkSpacing.s3) {
            thumbnail(uri: row.previewUri)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title ?? "Untitled capture")
                    .font(QuickInkText.editorial)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
                Text(metaLine(row))
                    .font(.system(size: 11))
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            Spacer(minLength: 0)
        }
        .padding(QuickInkSpacing.s2 + 2)
        .background(QuickInkColors.bg)
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private func thumbnail(uri: String?) -> some View {
        let placeholder = RoundedRectangle(cornerRadius: 8)
            .fill(QuickInkColors.paper1)
            .frame(width: 48, height: 56)
        if let uri = uri,
           let url = URL(string: uri),
           url.isFileURL,
           FileManager.default.fileExists(atPath: url.path),
           let image = UIImage(contentsOfFile: url.path) {
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: 48, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        } else {
            placeholder
        }
    }

    private func metaLine(_ row: PickerRow) -> String {
        let date = monthDay(from: row.createdAt) ?? "—"
        let kind = row.source == "import" ? "photo" : "scan"
        return "\(kind) · \(date)"
    }

    private var headerTitle: String {
        switch filter {
        case .photo:    return "Choose a photo"
        case .document: return "Choose a document"
        case .any:      return "Choose from your library"
        }
    }

    private var headerSubtitle: String {
        switch filter {
        case .photo:    return "Photos you imported via the system picker."
        case .document: return "Pages you scanned with the document scanner."
        case .any:      return "Anything in your library so far."
        }
    }

    // MARK: - Data

    private struct PickerRow: Identifiable {
        let id: String
        let title: String?
        let previewUri: String?
        let createdAt: String
        let source: String
    }

    @MainActor
    private func load() async {
        loading = true
        let userId = self.userId
        let filter = self.filter
        let queue = QuickInkDatabase.shared.dbQueue
        let fetched: [PickerRow] = (try? await queue.read { db -> [PickerRow] in
            let sourceClause: String = {
                switch filter {
                case .photo:    return "AND source = 'import'"
                case .document: return "AND source = 'scan'"
                case .any:      return ""
                }
            }()
            let rows = try Row.fetchAll(db, sql: """
                SELECT id, title, preview_uri, created_at, source
                FROM captures
                WHERE user_id = ? AND deleted_at IS NULL \(sourceClause)
                ORDER BY created_at DESC
                LIMIT 100
                """, arguments: [userId])
            return rows.map { row in
                PickerRow(
                    id:         row["id"],
                    title:      row["title"] as String?,
                    previewUri: row["preview_uri"] as String?,
                    createdAt:  row["created_at"],
                    source:     (row["source"] as String?) ?? "scan"
                )
            }
        }) ?? []
        self.rows = fetched
        self.loading = false
    }

    private static let monthDayFmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d"
        return f
    }()

    private func monthDay(from iso: String) -> String? {
        let parsers: [ISO8601DateFormatter] = [
            { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f }(),
            { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime]; return f }(),
        ]
        for p in parsers {
            if let d = p.date(from: iso) {
                return Self.monthDayFmt.string(from: d)
            }
        }
        return nil
    }
}
