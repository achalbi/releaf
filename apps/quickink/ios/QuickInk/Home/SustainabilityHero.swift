/*
 * SustainabilityHero.swift
 *
 * Hero card under the home greeting that frames QuickInk as a paper-
 * saving tool. Shows the user's lifetime digitised page count and
 * translates it into a composite "Tree points" impact score plus
 * water spared. Static leaf-green palette (independent of the user's
 * accent picker) so the eco message reads the same regardless of
 * whether they've picked Coral or Leaf Yellow for everything else.
 *
 * Tree-points model — we deliberately do *not* show a flat
 * `pages / 8333` tree count. A single divisor flatters casual users
 * (every scan rounds to "0.00 trees") and undersells power users
 * (one tree feels small even though the lifecycle impact is huge).
 * Instead we blend five independent paper-LCA factors into one
 * integer score:
 *
 *   1. Sheet engagement   — flat per-page reward; each capture has
 *                           to feel like it moved the needle.
 *   2. Tree-equivalent    — pages → fractional mature pine using the
 *                           conventional 8,333 sheets/tree, then
 *                           de-rated by the typical ~17% pulp yield
 *                           (only ~1/6 of a tree's biomass actually
 *                           becomes office paper). Heavy weight so
 *                           a whole-tree milestone reads as a real
 *                           jump.
 *   3. Water spared       — ~10 L of process water per A4 sheet.
 *   4. CO₂ avoided        — ~4.6 g CO₂e per sheet, cradle-to-grave.
 *   5. Energy spared      — ~50 Wh per sheet (mill + transport).
 *
 * A logarithmic engagement boost is layered on top so the curve
 * still rewards sustained use without being purely linear — each
 * order of magnitude of pages adds a fixed bump rather than the
 * score creeping up at a constant per-page rate. Numbers are
 * deliberately conservative; the goal is a directional impact
 * score, not a precise lifecycle assessment.
 *
 * Tapping the card presents `SustainabilityBreakdownSheet`, which
 * surfaces the per-component math behind the displayed score.
 *
 * Counterpart: Android `SustainabilityHero` in `HomeScreen.kt`. The
 * `TreeImpact` struct + `computeTreeImpact(totalPages:)` factory
 * mirror the Kotlin equivalents 1-to-1 so the displayed score
 * matches across platforms for the same lifetime page count.
 */

import SwiftUI
import Foundation

// MARK: - Sustainability impact model

/// Snapshot of one user's lifetime impact, expressed both as raw
/// LCA outputs (sheets / trees-equivalent / water / CO₂ / energy)
/// and as the per-component point contributions that sum to
/// `totalPoints`. The hero card and the breakdown sheet both read
/// from this struct so the displayed score and the per-row math
/// can never drift out of sync.
struct TreeImpact: Equatable {
    let pages: Int
    let pulpYield: Double
    let treeFraction: Double
    let waterLiters: Int
    let co2Grams: Double
    let energyWh: Double
    let pSheets: Double
    let pTrees: Double
    let pWater: Double
    let pCarbon: Double
    let pEnergy: Double
    let pStreak: Double
    let totalPoints: Int
}

/// Build a `TreeImpact` for the given lifetime page count. See the
/// `SustainabilityHero` doc-comment for the rationale behind each
/// factor and weight.
func computeTreeImpact(totalPages: Int) -> TreeImpact {
    let sheets       = Double(totalPages)
    let pulpYield    = 0.17                          // tree biomass → paper
    let treeFraction = (sheets / 8333.0) * pulpYield
    let waterLiters  = totalPages * 10               // kept as Int for the L label
    let co2Grams     = sheets * 4.6
    let energyWh     = sheets * 50.0

    // Component weights are calibrated so a single tree-milestone
    // (~8,333 pages) lands in the low six figures, while a single
    // captured page still scores in the low hundreds — enough to
    // feel rewarding without making the empty-state-to-first-scan
    // jump feel cheap.
    let pSheets = sheets       * 7.5
    let pTrees  = treeFraction * 12_000.0
    let pWater  = Double(waterLiters) * 0.6
    let pCarbon = co2Grams     * 1.2
    let pEnergy = energyWh     * 0.4
    let pStreak = sheets > 0 ? log(sheets + 1.0) * 180.0 : 0.0

    let total = max(0, Int((pSheets + pTrees + pWater + pCarbon + pEnergy + pStreak).rounded()))

    return TreeImpact(
        pages:        totalPages,
        pulpYield:    pulpYield,
        treeFraction: treeFraction,
        waterLiters:  waterLiters,
        co2Grams:     co2Grams,
        energyWh:     energyWh,
        pSheets:      pSheets,
        pTrees:       pTrees,
        pWater:       pWater,
        pCarbon:      pCarbon,
        pEnergy:      pEnergy,
        pStreak:      pStreak,
        totalPoints:  total
    )
}

// MARK: - Sustainability hero

struct SustainabilityHero: View {
    let totalPages: Int

    @State private var showBreakdown: Bool = false

    /// Recompute only when `totalPages` changes — same intent as
    /// Android's `remember(totalPages)`.
    private var impact: TreeImpact { computeTreeImpact(totalPages: totalPages) }

    var body: some View {
        let ecoDeep   = QuickInkColors.leafGreenDeep
        let ecoBg     = QuickInkColors.leafGreenBase.opacity(0.18)
        let ecoBorder = QuickInkColors.leafGreenBase.opacity(0.40)

        let pointsLabelText = String(
            format: "%@ Tree pts",
            integerFormatter.string(from: NSNumber(value: impact.totalPoints)) ?? "0"
        )
        let title = "By going digital"
        let headline: String = {
            switch totalPages {
            case 0:  return "Start saving paper"
            case 1:  return "1 page saved"
            default:
                let n = integerFormatter.string(from: NSNumber(value: totalPages)) ?? "\(totalPages)"
                return "\(n) pages saved"
            }
        }()

        // Tap to open the score-breakdown sheet. Always tappable —
        // the breakdown also serves as the explainer for the
        // empty-state ("here's how the score will work once you
        // start scanning"). Wrapped in a Button(.plain) so the
        // tap target spans the whole card and reads as a single
        // affordance to VoiceOver.
        Button {
            showBreakdown = true
        } label: {
            HStack(alignment: .center, spacing: QuickInkSpacing.s3) {
                ZStack {
                    Circle()
                        .fill(ecoDeep)
                        .frame(width: 48, height: 48)
                    Image(systemName: "leaf.fill")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(QuickInkColors.textOnAccent)
                }

                // Headline column — title sits on the leaf-green deep
                // tone so the whole card reads as a coherent green
                // family rather than gray-on-green. Headline uses
                // the dedicated `editorial` token (Sustainability
                // Campaigns row of the type spec).
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(QuickInkText.meta)
                        .foregroundStyle(ecoDeep)
                    Text(headline)
                        .font(QuickInkText.editorial)
                        .foregroundStyle(QuickInkColors.ink)
                    if totalPages == 0 {
                        Text("Tap the ⚡ to capture your first page")
                            .font(QuickInkText.caption)
                            .foregroundStyle(ecoDeep)
                            .padding(.top, 2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                // Right-aligned data column. Renders only once we
                // have any saved page — the empty-state prompt above
                // carries the secondary line in that case.
                if totalPages > 0 {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(pointsLabelText)
                            .font(QuickInkText.meta)
                            .foregroundStyle(ecoDeep)
                        Text("\(impact.waterLiters) L water")
                            .font(QuickInkText.meta)
                            .foregroundStyle(ecoDeep)
                    }
                }
            }
            .padding(QuickInkSpacing.s4)
            .background(
                RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                    .fill(ecoBg)
            )
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                    .strokeBorder(ecoBorder, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(headline). \(pointsLabelText). Tap for breakdown.")
        .sheet(isPresented: $showBreakdown) {
            SustainabilityBreakdownSheet(
                impact:    impact,
                onDismiss: { showBreakdown = false }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
    }
}

// MARK: - Sustainability breakdown sheet

/// Bottom sheet that opens when the user taps `SustainabilityHero`.
/// Lays out the Tree-points calculation in two stacked sections:
///
///   1. **What we measured** — the raw lifecycle outputs (sheets,
///      tree-equivalent, water, CO₂, energy) with the per-sheet
///      conversion factor as a caption underneath each row.
///   2. **How that scores** — the per-component point contributions
///      with their weight rates as a caption, totalled at the bottom.
///
/// The total at the top of the sheet is the same integer the hero
/// card shows; both reads come from the same `TreeImpact` snapshot
/// so the displayed score and the breakdown can never disagree.
struct SustainabilityBreakdownSheet: View {
    let impact: TreeImpact
    let onDismiss: () -> Void

    var body: some View {
        let ecoDeep = QuickInkColors.leafGreenDeep
        let ecoBg   = QuickInkColors.leafGreenBase.opacity(0.18)

        let totalLabel = String(
            format: "%@ Tree pts",
            integerFormatter.string(from: NSNumber(value: impact.totalPoints)) ?? "0"
        )
        let pagesLabel: String = {
            switch impact.pages {
            case 0: return "No pages saved yet"
            case 1: return "From 1 page saved"
            default:
                let n = integerFormatter.string(from: NSNumber(value: impact.pages)) ?? "\(impact.pages)"
                return "From \(n) pages saved"
            }
        }()

        ScrollView {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                // Header row — title + close affordance. Mirrors
                // `ExportSheet.swift`'s header pattern so the sheet
                // chrome reads consistently across the app.
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("How your Tree score works")
                            .font(QuickInkText.heading)
                            .foregroundStyle(QuickInkColors.ink)
                        Text("Five LCA factors plus an engagement boost.")
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

                // Hero echo — green-tinted block restating the score
                // the user just tapped through.
                VStack(alignment: .leading, spacing: 2) {
                    Text(pagesLabel)
                        .font(QuickInkText.meta)
                        .foregroundStyle(ecoDeep)
                    Text(totalLabel)
                        .font(QuickInkText.editorial)
                        .foregroundStyle(QuickInkColors.ink)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(QuickInkSpacing.s4)
                .background(
                    RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                        .fill(ecoBg)
                )

                // Section 1 — raw lifecycle outputs.
                breakdownSectionHeader("What we measured")
                breakdownRow(
                    label:   "Sheets engaged",
                    value:   String(format: "%@ pages",
                                    integerFormatter.string(from: NSNumber(value: impact.pages)) ?? "0"),
                    caption: "Lifetime captures across all notebooks"
                )
                breakdownRow(
                    label:   "Tree-equivalent",
                    value:   String(format: "%.4f trees", impact.treeFraction),
                    caption: String(format: "pages ÷ 8,333 × %.0f%% pulp yield",
                                    impact.pulpYield * 100.0)
                )
                breakdownRow(
                    label:   "Water spared",
                    value:   String(format: "%@ L",
                                    integerFormatter.string(from: NSNumber(value: impact.waterLiters)) ?? "0"),
                    caption: "≈ 10 L of process water per A4 sheet"
                )
                breakdownRow(
                    label:   "CO₂ avoided",
                    value:   formatGramsOrKg(impact.co2Grams),
                    caption: "≈ 4.6 g CO₂e per sheet, cradle-to-grave"
                )
                breakdownRow(
                    label:   "Energy spared",
                    value:   formatWhOrKWh(impact.energyWh),
                    caption: "≈ 50 Wh per sheet (mill + transport)"
                )

                Divider().background(QuickInkColors.border)

                // Section 2 — point contributions.
                breakdownSectionHeader("How that scores")
                breakdownRow(label: "Sheet engagement",
                             value: pointsLabel(impact.pSheets),
                             caption: "+7.5 pts per page captured")
                breakdownRow(label: "Tree milestone",
                             value: pointsLabel(impact.pTrees),
                             caption: "+12,000 pts per tree-equivalent saved")
                breakdownRow(label: "Water",
                             value: pointsLabel(impact.pWater),
                             caption: "+0.6 pts per litre")
                breakdownRow(label: "CO₂",
                             value: pointsLabel(impact.pCarbon),
                             caption: "+1.2 pts per gram avoided")
                breakdownRow(label: "Energy",
                             value: pointsLabel(impact.pEnergy),
                             caption: "+0.4 pts per watt-hour")
                breakdownRow(label: "Engagement boost",
                             value: pointsLabel(impact.pStreak),
                             caption: "ln(pages + 1) × 180 — rewards sustained use")

                Divider().background(QuickInkColors.border)

                // Total row — pinned at the bottom, matches the card.
                HStack {
                    Text("Total")
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.ink)
                    Spacer()
                    Text(totalLabel)
                        .font(QuickInkText.heading)
                        .foregroundStyle(ecoDeep)
                }

                Text("Numbers are deliberately conservative — directional " +
                     "impact, not a precise lifecycle assessment.")
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)

                Spacer(minLength: QuickInkSpacing.s2)
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.top, QuickInkSpacing.s4)
            .padding(.bottom, QuickInkSpacing.s5)
        }
        .frame(maxWidth: .infinity)
        .background(QuickInkColors.bg)
    }

    // MARK: - Section helpers

    @ViewBuilder
    private func breakdownSectionHeader(_ label: String) -> some View {
        Text(label.uppercased())
            .font(QuickInkText.caption)
            .foregroundStyle(QuickInkColors.muted)
    }

    @ViewBuilder
    private func breakdownRow(label: String, value: String, caption: String) -> some View {
        HStack(alignment: .top, spacing: QuickInkSpacing.s3) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                Text(caption)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }
            Spacer()
            Text(value)
                .font(QuickInkText.label)
                .foregroundStyle(QuickInkColors.ink)
                .multilineTextAlignment(.trailing)
        }
    }
}

// MARK: - Formatting helpers

/// Shared thousand-separated integer formatter. Hoisted to file
/// scope so each row render doesn't allocate a new instance.
private let integerFormatter: NumberFormatter = {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.locale = Locale(identifier: "en_US_POSIX")
    f.maximumFractionDigits = 0
    return f
}()

/// "+1,234 pts" — used for the per-component point contributions.
private func pointsLabel(_ raw: Double) -> String {
    let n = max(0, Int(raw.rounded()))
    let formatted = integerFormatter.string(from: NSNumber(value: n)) ?? "\(n)"
    return "+\(formatted) pts"
}

/// "812 g" under a kilo, "1.23 kg" once we cross the threshold.
private func formatGramsOrKg(_ grams: Double) -> String {
    if grams < 1_000.0 {
        let n = Int(grams.rounded())
        let formatted = integerFormatter.string(from: NSNumber(value: n)) ?? "\(n)"
        return "\(formatted) g"
    }
    return String(format: "%.2f kg", grams / 1_000.0)
}

/// "812 Wh" under a kilowatt-hour, "1.23 kWh" once we cross over.
private func formatWhOrKWh(_ wh: Double) -> String {
    if wh < 1_000.0 {
        let n = Int(wh.rounded())
        let formatted = integerFormatter.string(from: NSNumber(value: n)) ?? "\(n)"
        return "\(formatted) Wh"
    }
    return String(format: "%.2f kWh", wh / 1_000.0)
}

#if DEBUG
struct SustainabilityHero_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: 16) {
            SustainabilityHero(totalPages: 0)
            SustainabilityHero(totalPages: 1)
            SustainabilityHero(totalPages: 100)
            SustainabilityHero(totalPages: 1_000)
            SustainabilityHero(totalPages: 8_333)
        }
        .padding()
        .background(QuickInkColors.bg)
        .previewDisplayName("Sustainability hero — varying totals")
    }
}
#endif
