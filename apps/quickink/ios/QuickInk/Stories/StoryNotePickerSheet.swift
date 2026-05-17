/*
 * StoryNotePickerSheet.swift
 *
 * Stories Phase 2 follow-up — single-select picker over the user's
 * notepad entries (the daily-journal table from ReleafCoreNotes).
 * Opens from the "Choose a note" row in `StoryAddSheet` and returns
 * the picked entry id to the caller. The editor then inserts a
 * `story_item` of `kind = .note` with `refId = entryId`.
 *
 * Visual treatment differs from `StoryLibraryPickerSheet`: no
 * thumbnail (notes are text), so the row shows title (or "Daily
 * note") plus a 60-char excerpt of the body plus the entry date.
 *
 * Mirror of Android `StoryNotePickerSheet.kt`.
 */

import GRDB
import SwiftUI

struct StoryNotePickerSheet: View {

    let userId: String
    var onPick: (_ entryId: String) -> Void
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
            Text("Choose a note")
                .font(QuickInkText.editorial)
                .foregroundStyle(QuickInkColors.ink)
            Text("Daily notes from your journal.")
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
            Text("No notes yet. Write one in the notepad first.")
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
        HStack(alignment: .top, spacing: QuickInkSpacing.s3) {
            VStack(alignment: .leading, spacing: 4) {
                Text(row.title)
                    .font(QuickInkText.editorial)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
                if !row.excerpt.isEmpty {
                    Text(row.excerpt)
                        .font(QuickInkFont.serif(12, weight: .regular, italic: true))
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .lineLimit(2)
                }
                Text(row.entryDate)
                    .font(.system(size: 11))
                    .foregroundStyle(QuickInkColors.muted)
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
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    // MARK: - Data

    private struct PickerRow: Identifiable {
        let id: String
        let title: String
        let excerpt: String
        let entryDate: String
    }

    @MainActor
    private func load() async {
        loading = true
        let userId = self.userId
        let queue = QuickInkDatabase.shared.dbQueue
        let fetched: [PickerRow] = (try? await queue.read { db -> [PickerRow] in
            let rows = try Row.fetchAll(db, sql: """
                SELECT id, title, notes, entry_date
                FROM notepad_entries
                WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY entry_date DESC
                LIMIT 100
                """, arguments: [userId])
            return rows.map { row -> PickerRow in
                let body = (row["notes"] as String?) ?? ""
                let title = ((row["title"] as String?)?.trimmingCharacters(in: .whitespacesAndNewlines))
                    .flatMap { $0.isEmpty ? nil : $0 } ?? "Daily note"
                let excerpt: String = {
                    let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
                    if trimmed.isEmpty { return "" }
                    let cut = String(trimmed.prefix(60))
                    return trimmed.count > 60 ? cut + "…" : cut
                }()
                return PickerRow(
                    id:        row["id"],
                    title:     title,
                    excerpt:   excerpt,
                    entryDate: row["entry_date"]
                )
            }
        }) ?? []
        self.rows = fetched
        self.loading = false
    }
}
