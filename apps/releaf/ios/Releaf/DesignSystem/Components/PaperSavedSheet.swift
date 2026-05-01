/*
 * PaperSavedSheet.swift
 *
 * Bottom sheet that opens when the RE-LEAF eyebrow on the strip is
 * tapped. Shows the math behind the two numbers in the strip:
 *
 *   - per-kind table  (count × sheets/each = sheets)
 *   - total           (sum)
 *   - tree readout    (sheets ÷ sheetsPerTree)
 *   - honest closing  copy on what the number means
 *
 * Counts come from the page (or aggregate) the strip was built from.
 * The multipliers come from `AppMetrics.PaperPerCapture` — same source
 * that the strip itself uses, so the sub-totals always reconcile.
 *
 * The sheet uses `.presentationDetents([.medium, .large])` so the
 * caller doesn't have to pick a size; the system grows it on tall
 * notebooks where the table runs longer.
 */

import SwiftUI

public struct PaperSavedSheet: View {
    public let photos: Int
    public let voiceNotes: Int
    public let todoItems: Int
    public let scans: Int
    public let contacts: Int
    public let places: Int
    public let notes: Int
    /// Optional accent override for the eyebrow + total readout
    /// tones. Nil → defaults to the green theme. PageDetail
    /// passes the parent-notebook color so the explainer matches
    /// the surface that opened it. The breakdown-row glyphs are
    /// intentionally not overridden — they're per-capture-mode
    /// color codes (scan green, contact blue, etc.) and shouldn't
    /// morph with notebook color.
    public let accentOverride: Color?
    public let onClose: () -> Void

    public init(
        photos: Int = 0,
        voiceNotes: Int = 0,
        todoItems: Int = 0,
        scans: Int = 0,
        contacts: Int = 0,
        places: Int = 0,
        notes: Int = 0,
        accentOverride: Color? = nil,
        onClose: @escaping () -> Void
    ) {
        self.photos = photos
        self.voiceNotes = voiceNotes
        self.todoItems = todoItems
        self.scans = scans
        self.contacts = contacts
        self.places = places
        self.notes = notes
        self.accentOverride = accentOverride
        self.onClose = onClose
    }

    public var body: some View {
        let impact = ReleafImpact(
            photos: photos, voiceNotes: voiceNotes, todoItems: todoItems,
            scans: scans, contacts: contacts, places: places, notes: notes
        )
        let totalCaptures = photos + voiceNotes + todoItems + scans + contacts + places + notes

        let eyebrowTint = accentOverride ?? AppColors.themeGreenDeep
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                Text("RE-LEAF")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(eyebrowTint)

                Text("how paper saved is counted")
                    .font(AppText.editorialTitle)
                    .foregroundStyle(AppColors.textPrimary)
                    .padding(.bottom, AppSpacing.s2)

                summaryTiles(impact: impact, accentTint: eyebrowTint)
                    .padding(.bottom, AppSpacing.s3)

                Text("PER CAPTURE · ON THIS PAGE")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                    .padding(.bottom, AppSpacing.s1)

                breakdownRows()

                totalRow(impact: impact, totalCaptures: totalCaptures)
                    .padding(.top, AppSpacing.s2)

                Text(rationale)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
                    .lineSpacing(3)
                    .padding(.top, AppSpacing.s4)

                AppButton("Close", variant: .primary, action: onClose)
                    .padding(.top, AppSpacing.s3)
            }
            .padding(.horizontal, AppSpacing.s5)
            .padding(.top, AppSpacing.s4)
            .padding(.bottom, AppSpacing.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    // MARK: - Pieces

    private func summaryTiles(impact: ReleafImpact, accentTint: Color) -> some View {
        let readout = impact.treeReadout
        return HStack(spacing: AppSpacing.s2) {
            SummaryTile(
                eyebrow: "SHEETS",
                value: impact.formattedSheets,
                caption: "across this page",
                eyebrowTint: accentTint
            )
            SummaryTile(
                eyebrow: "TREES",
                value: readout.number,
                caption: readout.unit,
                eyebrowTint: AppColors.textSecondary
            )
        }
    }

    @ViewBuilder
    private func breakdownRows() -> some View {
        let m = AppMetrics.PaperPerCapture.self
        VStack(spacing: 0) {
            BreakdownRow(label: "scans",       count: scans,      multiplier: m.scan,
                         glyph: AppColors.green)
            BreakdownRow(label: "notes",       count: notes,      multiplier: m.note,
                         glyph: AppColors.themeGreenPrimary)
            BreakdownRow(label: "voice notes", count: voiceNotes, multiplier: m.voice,
                         glyph: AppColors.warning)
            BreakdownRow(label: "contacts",    count: contacts,   multiplier: m.contact,
                         glyph: AppColors.info)
            BreakdownRow(label: "places",      count: places,     multiplier: m.place,
                         glyph: AppColors.coralDeep)
            BreakdownRow(label: "photos",      count: photos,     multiplier: m.photo,
                         glyph: AppColors.themeGreenPrimary.opacity(0.7),
                         isLast: false)
            BreakdownRow(label: "to-do",       count: todoItems,  multiplier: m.todo,
                         glyph: AppColors.themeGreenPrimary.opacity(0.55),
                         isLast: true)
        }
    }

    private func totalRow(impact: ReleafImpact, totalCaptures: Int) -> some View {
        HStack {
            Text("total · \(totalCaptures) capture\(totalCaptures == 1 ? "" : "s")")
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
            Spacer()
            Text(impact.formattedSheets)
                .font(.system(size: 22, design: .serif))
                .foregroundStyle(AppColors.green)
        }
        .padding(.vertical, AppSpacing.s3)
        .overlay(
            Rectangle()
                .fill(AppColors.borderStrong)
                .frame(height: 1),
            alignment: .top
        )
    }

    private var rationale: String {
        "a mature pine yields about \(Int(AppMetrics.sheetsPerTree).formatted(.number)) letter-size sheets. each capture is rated against what it would have replaced on paper — a scan against one printed page, a note against a quarter, a voice note against a tenth. small on its own. across years of notebooks, less so."
    }
}

// MARK: - Summary tile

private struct SummaryTile: View {
    let eyebrow: String
    let value: String
    let caption: String
    let eyebrowTint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s1) {
            Text(eyebrow)
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(eyebrowTint)
            Text(value)
                .font(.system(size: 26, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(caption)
                .font(AppText.tag)
                .foregroundStyle(AppColors.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(AppSpacing.s3)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.canvas)
        )
    }
}

// MARK: - Breakdown row

private struct BreakdownRow: View {
    let label: String
    let count: Int
    let multiplier: Double
    let glyph: Color
    var isLast: Bool = false

    var body: some View {
        let subtotal = Double(count) * multiplier
        VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                LeafDropletGlyph(tint: glyph, size: 11)
                    .padding(.trailing, AppSpacing.s1)
                Text(label)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textPrimary)
                Spacer()
                Text("\(count) × \(formatMultiplier(multiplier))")
                    .font(AppText.tag)
                    .foregroundStyle(AppColors.textTertiary)
                    .padding(.trailing, AppSpacing.s3)
                Text(String(format: "%.2f", subtotal))
                    .font(.system(size: 15, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                    .frame(minWidth: 44, alignment: .trailing)
            }
            .padding(.vertical, AppSpacing.s2)
            if !isLast {
                Rectangle()
                    .fill(AppColors.borderDefault)
                    .frame(height: 0.5)
            }
        }
    }

    private func formatMultiplier(_ value: Double) -> String {
        // Trim trailing zeros: 1.00 → 1.0, 0.30 → 0.3.
        let formatted = String(format: "%.2f", value)
        if formatted.hasSuffix("0") {
            return String(formatted.dropLast())
        }
        return formatted
    }
}

#if DEBUG
struct PaperSavedSheet_Previews: PreviewProvider {
    static var previews: some View {
        Color.gray.opacity(0.2)
            .sheet(isPresented: .constant(true)) {
                PaperSavedSheet(
                    photos: 2, voiceNotes: 1, todoItems: 1,
                    scans: 2, contacts: 1, places: 1, notes: 1,
                    onClose: {}
                )
            }
    }
}
#endif
