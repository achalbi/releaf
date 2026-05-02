/*
 * ExportSheet.swift
 *
 * Bottom-sheet picker offering export destinations for the active
 * note. Triggered from `NoteEditorScreen`'s Export floating-action
 * button. Presented as a `.sheet(...)` with `.presentationDetents`
 * so the user can flick it away.
 *
 * Format options (per the mockup brief):
 *   - PDF              — searchable when the user's experimental
 *                        toggle is on; a flat bitmap export
 *                        otherwise.
 *   - Markdown         — `.md` with the OCR text as the body and
 *                        the title as the H1.
 *   - Image            — single-page PNG/JPEG export.
 *   - Plain text       — `.txt` for clipboard / quick paste.
 *
 * Each tap fires the matching closure on the parent and the sheet
 * dismisses. The actual export pipeline (rendering PDF, encoding
 * Markdown, etc.) lives in a follow-up — this surface owns the
 * picker UI only. Closures hand the parent `(note, format)`
 * payload to consume.
 */

import SwiftUI

struct ExportSheet: View {

    enum Format: String, CaseIterable {
        case pdf, markdown, image, plain

        var label: String {
            switch self {
            case .pdf:      return "PDF"
            case .markdown: return "Markdown"
            case .image:    return "Image"
            case .plain:    return "Plain text"
            }
        }

        var subtitle: String {
            switch self {
            case .pdf:      return "Searchable layout · ideal for sharing"
            case .markdown: return ".md with the OCR transcript as the body"
            case .image:    return "Single-page PNG of the captured page"
            case .plain:    return "Just the OCR text — quick paste"
            }
        }

        var icon: String {
            switch self {
            case .pdf:      return "doc.richtext"
            case .markdown: return "chevron.left.forwardslash.chevron.right"
            case .image:    return "photo"
            case .plain:    return "doc.text"
            }
        }
    }

    /// The user's current Searchable PDF setting (`SettingsState.
    /// searchablePdfExportEnabled`). Surfaced here as a footer
    /// hint on the PDF row so the user knows what to expect.
    let searchablePdfEnabled: Bool

    let onSelect: (Format) -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            handle
            header
            Divider().background(QuickInkColors.border)

            VStack(spacing: QuickInkSpacing.s2) {
                ForEach(Format.allCases, id: \.self) { format in
                    Button(action: { onSelect(format) }) {
                        row(for: format)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.top, QuickInkSpacing.s3)
            .padding(.bottom, QuickInkSpacing.s5)
        }
        .frame(maxWidth: .infinity)
        .background(QuickInkColors.bg)
    }

    // MARK: - Pieces

    @ViewBuilder
    private var handle: some View {
        Capsule()
            .fill(QuickInkColors.border)
            .frame(width: 40, height: 4)
            .padding(.top, QuickInkSpacing.s2)
            .padding(.bottom, QuickInkSpacing.s3)
            .frame(maxWidth: .infinity, alignment: .center)
    }

    @ViewBuilder
    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Export this note")
                    .font(QuickInkText.heading)
                    .foregroundStyle(QuickInkColors.ink)
                Text("Pick a format — we'll generate it on this device.")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            Spacer()
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .padding(QuickInkSpacing.s2)
                    .background(QuickInkColors.borderSoft)
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close")
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.bottom, QuickInkSpacing.s3)
    }

    @ViewBuilder
    private func row(for format: Format) -> some View {
        HStack(spacing: QuickInkSpacing.s3) {
            ZStack {
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .fill(QuickInkColors.accentSoft)
                Image(systemName: format.icon)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.accent)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 2) {
                Text(format.label)
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                Text(subtitle(for: format))
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(QuickInkColors.muted)
        }
        .padding(QuickInkSpacing.s4)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    /// PDF row gets a contextual addition based on whether the
    /// user has the searchable-PDF experimental flag on. Other
    /// formats use their static subtitle.
    private func subtitle(for format: Format) -> String {
        switch format {
        case .pdf:
            return searchablePdfEnabled
                ? "Searchable layout · OCR text layer included"
                : "Flat layout · enable Searchable PDF in Settings for OCR"
        default:
            return format.subtitle
        }
    }
}
