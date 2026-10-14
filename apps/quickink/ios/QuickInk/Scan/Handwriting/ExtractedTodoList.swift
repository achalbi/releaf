/*
 * ExtractedTodoList.swift
 *
 * Shared UI for to-dos read off checkbox lines. Two surfaces use it:
 *
 *   - `ScanReviewScreen` (editable) — the confirm step before the
 *     items are saved: edit text, tick / untick, remove.
 *   - `NoteEditorScreen` (toggle only) — the day's saved to-dos in
 *     place of the old "Coming soon" smart-suggestions card.
 *
 * Mirror of Android `ExtractedTodoList.kt`.
 */

import SwiftUI

/// Review-screen section: "TO-DOS FROM THIS SCAN" with one editable
/// row per item. Renders nothing for an empty list.
struct ExtractedTodosReviewSection: View {
    @ObservedObject var controller: ScanFlowController

    var body: some View {
        if !controller.reviewTodos.isEmpty {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
                Text("TO-DOS FROM THIS SCAN")
                    .font(QuickInkText.eyebrow)
                    .tracking(QuickInkLetterSpacing.eyebrow)
                    .foregroundStyle(QuickInkColors.muted)
                Text("Saved to the day's note when you tap Done. Edit, untick or remove anything that was misread.")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.inkSoft)
                ForEach(controller.reviewTodos) { todo in
                    ExtractedTodoRow(
                        text: todo.text,
                        done: todo.done,
                        onToggle: { controller.setTodoDone(id: todo.id, done: !todo.done) },
                        editText: Binding(
                            get: { controller.reviewTodos.first(where: { $0.id == todo.id })?.text ?? "" },
                            set: { controller.editTodoText(id: todo.id, text: $0) }
                        ),
                        onRemove: { controller.removeTodo(id: todo.id) }
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// One to-do row. `editText` / `onRemove` nil → read-only text (the
/// note editor); the checkbox always toggles.
struct ExtractedTodoRow: View {
    let text: String
    let done: Bool
    let onToggle: () -> Void
    var editText: Binding<String>? = nil
    var onRemove: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Button(action: onToggle) {
                Image(systemName: done ? "checkmark.square.fill" : "square")
                    .font(.system(size: 20))
                    .foregroundStyle(done ? QuickInkColors.accent : QuickInkColors.inkSoft)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(done ? "Mark not done" : "Mark done")

            Group {
                if let editText {
                    TextField("To-do", text: editText)
                } else {
                    Text(text)
                }
            }
            .font(QuickInkText.body)
            .foregroundStyle(done ? QuickInkColors.muted : QuickInkColors.ink)
            .strikethrough(done, color: QuickInkColors.muted)
            .frame(maxWidth: .infinity, alignment: .leading)

            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(QuickInkColors.muted)
                        .padding(4)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Remove to-do")
            }
        }
    }
}
