/*
 * NotesEditorSheet.swift
 *
 * Full-height sheet that owns the rich-text editor + format bar,
 * presented from the Overview tab on the notepad editor. Tap the
 * notes preview to open; dismiss with the "Done" button, a pull-down,
 * or the standard sheet handle.
 *
 * The sheet binds to the same `$notes` the caller's VM exposes and
 * re-mounts a `RichTextEditor` against the shared
 * `RichTextEditorController`, so changes here propagate back into
 * the Edit-mode editor when the user flips modes.
 */

import SwiftUI
import ReleafCoreDesignSystem  // PR #4g — was ReleafDesignSystem before file moved here

public struct NotesEditorSheet: View {
    @Binding var notes: String
    @ObservedObject var controller: RichTextEditorController
    let onDismiss: () -> Void

    public init(
        notes: Binding<String>,
        controller: RichTextEditorController,
        onDismiss: @escaping () -> Void
    ) {
        self._notes = notes
        self.controller = controller
        self.onDismiss = onDismiss
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // No outer ScrollView: the UITextView handles its own
                // scrolling via `isScrollEnabled: true`. Nesting the
                // text view inside a SwiftUI ScrollView was letting
                // the outer gesture recognizer intercept double-tap
                // before UITextView's own selection gestures saw it.
                ZStack(alignment: .topLeading) {
                    if notes.isEmpty {
                        Text("Start typing…")
                            .font(AppText.body)
                            .foregroundStyle(AppColors.textTertiary)
                            .allowsHitTesting(false)
                            .padding(.horizontal, AppSpacing.s4)
                            .padding(.top, AppSpacing.s3)
                    }
                    RichTextEditor(
                        markdown:        $notes,
                        controller:      controller,
                        tintColor:       AppColors.coral,
                        isScrollEnabled: true
                    )
                    .padding(.horizontal, AppSpacing.s4)
                    .padding(.top, AppSpacing.s3)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

                RichTextFormatBar(controller: controller)
            }
            .background(AppColors.canvas.ignoresSafeArea())
            .navigationTitle("Notes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        // Force a final serialize. `textViewDidChange`
                        // fires on text edits but attribute-only mutations
                        // (bold toggles with no typing) can race the
                        // teardown of the RichTextEditor before the
                        // binding catches up. Writing to `notes`
                        // explicitly here guarantees the Overview
                        // preview shows the latest body on return.
                        // `serializedMarkdown()` is the iOS-only round-
                        // trip; on the macOS stub it returns nil and we
                        // fall through with `notes` already reflecting
                        // the plain TextEditor content.
                        if let latest = controller.serializedMarkdown() {
                            notes = latest
                        }
                        onDismiss()
                    }
                    .foregroundStyle(AppColors.coral)
                }
            }
        }
    }
}
