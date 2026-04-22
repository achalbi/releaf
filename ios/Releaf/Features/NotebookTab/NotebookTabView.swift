/*
 * NotebookTabView.swift
 *
 * Top-level "Notebook" tab — lists the user's notebooks from the
 * Room/GRDB-backed store (via `NotebookRepository`). Parity with
 * Android's `NotebookTabScreen.kt`, though much slimmer for now: we
 * show a list, an add-notebook inline input, and a swipe-to-delete.
 * Drilling into a notebook (chapters + pages) is deferred to a later
 * turn — the existing drive-fake `NotebookDetailView` still handles
 * that path from the Home tab.
 */

import SwiftUI
import ReleafData
import ReleafDesignSystem

public struct NotebookTabView: View {
    @StateObject private var vm = NotebookTabViewModel()
    @State private var isAdding: Bool = false
    @State private var newTitle: String = ""
    @FocusState private var addFocused: Bool

    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            if vm.isLoading {
                ZStack {
                    ProgressView().tint(AppColors.coral)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if vm.notebooks.isEmpty && !isAdding {
                emptyState
            } else {
                List {
                    if isAdding {
                        Section {
                            addRow
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                        }
                    }
                    Section {
                        ForEach(vm.notebooks) { notebook in
                            NotebookListRow(notebook: notebook)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button(role: .destructive) {
                                        vm.delete(notebookId: notebook.id)
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                    .tint(AppColors.danger)
                                }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }

            if !isAdding {
                addAffordance
            }
        }
        .background(AppColors.canvas.ignoresSafeArea())
        .task {
            vm.start()
        }
        .onDisappear { vm.stop() }
    }

    // MARK: - Pieces

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            Text("NOTEBOOK")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coral)

            Text("Your notebooks")
                .font(AppText.editorialTitle)
                .foregroundStyle(AppColors.textPrimary)
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.top, AppSpacing.s4)
        .padding(.bottom, AppSpacing.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var emptyState: some View {
        VStack(spacing: AppSpacing.s2) {
            Text("No notebooks yet")
                .font(AppText.sectionTitle)
                .foregroundStyle(AppColors.textSecondary)
            Text("Tap \u{201c}+ Add notebook\u{201d} below to start one.")
                .font(AppText.body)
                .foregroundStyle(AppColors.textTertiary)
                .multilineTextAlignment(.center)
        }
        .padding(AppSpacing.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var addRow: some View {
        HStack(spacing: AppSpacing.s3) {
            Image(systemName: "book.closed")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(AppColors.coral)
            TextField(
                "",
                text: $newTitle,
                prompt: Text("Notebook name").foregroundColor(AppColors.textTertiary)
            )
            .font(AppText.body)
            .foregroundStyle(AppColors.textPrimary)
            .tint(AppColors.coral)
            .submitLabel(.done)
            .focused($addFocused)
            .onSubmit(commitAdd)
            Button(action: cancelAdd) {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(AppColors.textTertiary)
                    .frame(width: 22, height: 22)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.vertical, AppSpacing.s2)
        .onAppear { addFocused = true }
    }

    private var addAffordance: some View {
        Button {
            isAdding = true
        } label: {
            HStack(spacing: AppSpacing.s2) {
                Image(systemName: "plus")
                    .font(.system(size: 16, weight: .semibold))
                Text("Add notebook")
                    .font(AppText.button)
            }
            .foregroundStyle(AppColors.coral)
            .padding(.horizontal, AppSpacing.s4)
            .padding(.vertical, AppSpacing.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .background(
            Rectangle()
                .fill(AppColors.cardSolid)
                .overlay(alignment: .top) {
                    Rectangle().fill(AppColors.borderDefault).frame(height: 1)
                }
        )
    }

    private func commitAdd() {
        let trimmed = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            vm.addNotebook(title: trimmed)
        }
        newTitle = ""
        isAdding = false
    }

    private func cancelAdd() {
        newTitle = ""
        isAdding = false
    }
}

// MARK: - Row

private struct NotebookListRow: View {
    let notebook: NotebookEntity

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            colorDot
            VStack(alignment: .leading, spacing: 2) {
                Text(notebook.title)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                    .lineLimit(1)
                Text(relativeUpdated)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(AppColors.textTertiary)
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.vertical, AppSpacing.s3)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
    }

    private var colorDot: some View {
        Circle()
            .fill(color)
            .frame(width: 12, height: 12)
    }

    private var color: Color {
        // `color_hex` comes from the schema; fall back to coral when absent.
        guard let hex = notebook.colorHex, let parsed = Color(hex: hex) else {
            return AppColors.coral
        }
        return parsed
    }

    private var relativeUpdated: String {
        // The `updated_at` column stores ISO-8601 UTC with ms. Parse
        // it with a formatter so the "5 min ago" label renders
        // correctly. Best-effort — a parse failure falls back to the
        // raw string.
        guard let date = Self.isoFormatter.date(from: notebook.updatedAt) else {
            return notebook.updatedAt
        }
        return Self.relative.localizedString(for: date, relativeTo: Date())
    }

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let relative: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .short
        return f
    }()
}

// MARK: - Hex color helper

private extension Color {
    /// Parse `#RRGGBB` or `RRGGBB` strings. Returns nil on any
    /// malformation so callers can fall back safely.
    init?(hex: String) {
        let cleaned = hex.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "#", with: "")
        guard cleaned.count == 6,
              let rgb = UInt64(cleaned, radix: 16) else {
            return nil
        }
        let r = Double((rgb >> 16) & 0xFF) / 255.0
        let g = Double((rgb >> 8) & 0xFF) / 255.0
        let b = Double(rgb & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b)
    }
}
