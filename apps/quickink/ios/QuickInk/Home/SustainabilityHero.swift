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
 *   1. Sheet engagement   — per-page reward, weighted by page size
 *                           (card +4, A4 +2, smaller +1) so a
 *                           bulk-print-saving card scan beats a
 *                           one-off small page.
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
 * Numbers are deliberately conservative; the goal is a directional
 * impact score, not a precise lifecycle assessment.
 *
 * Tapping the card presents `SustainabilityBreakdownSheet`, which
 * surfaces the per-component math behind the displayed score.
 *
 * Counterpart: Android `SustainabilityHero` in `HomeScreen.kt`. The
 * `TreeImpact` struct + `computeTreeImpact(pagesBySize:)` factory
 * mirror the Kotlin equivalents 1-to-1 so the displayed score
 * matches across platforms for the same lifetime page split.
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
///
/// `cardPages` / `a4Pages` / `smallPages` carry the size-bucket
/// split that drives the variable per-page weight in `pSheets`. The
/// breakdown sheet's "What we measured" section also renders from
/// these so the user sees where their points come from.
struct TreeImpact: Equatable {
    let pages: Int
    let cardPages: Int
    let a4Pages: Int
    let smallPages: Int
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
    let totalPoints: Int
}

/// Translate the raw-string-keyed dict that GRDB delivers (column
/// values are `TEXT`) into the typed `[PaperSize: Int]` shape the
/// impact calculator expects. Unknown keys (forward-compatibility
/// hedge: a future schema bump could introduce values we don't have
/// in the enum yet) are silently dropped — better than fatal-erroring
/// the home screen on rollout.
func typedPagesBySize(_ raw: [String: Int]) -> [PaperSize: Int] {
    var out: [PaperSize: Int] = [:]
    for (key, pages) in raw {
        if let size = PaperSize(rawValue: key) {
            out[size] = pages
        }
    }
    return out
}

/// Build a `TreeImpact` from a per-size page count breakdown. The
/// `Sheet engagement` factor weights each bucket independently:
///   - card  → +4 pts/page (bonus for digitising what's normally
///                          printed in bulk)
///   - a4    → +2 pts/page (default for camera scans)
///   - small → +1 pt/page  (reserved for sub-A4 PDF imports)
///
/// The other four factors and the streak boost still scale with
/// total lifetime pages — they capture pulp / water / CO₂ / energy
/// per sheet, which is roughly size-agnostic at the precision the
/// score is meant to convey.
func computeTreeImpact(pagesBySize: [PaperSize: Int]) -> TreeImpact {
    let cardPages  = pagesBySize[.card]  ?? 0
    let a4Pages    = pagesBySize[.a4]    ?? 0
    let smallPages = pagesBySize[.small] ?? 0
    let totalPages = cardPages + a4Pages + smallPages

    let sheets       = Double(totalPages)
    let pulpYield    = 0.17                          // tree biomass → paper
    let treeFraction = (sheets / 8333.0) * pulpYield
    let waterLiters  = totalPages * 10               // kept as Int for the L label
    let co2Grams     = sheets * 4.6
    let energyWh     = sheets * 50.0

    // Size-weighted sheet engagement. Cards score 0.4, A4 0.2,
    // smaller 0.1 per page. All five component weights are tuned
    // 10× smaller than the earlier model so the lifetime total
    // stays in a comfortably-readable range rather than ballooning
    // into the high tens-of-thousands for everyday users.
    let pSheets = Double(cardPages)  * 0.4
                + Double(a4Pages)    * 0.2
                + Double(smallPages) * 0.1
    let pTrees  = treeFraction * 1_200.0
    let pWater  = Double(waterLiters) * 0.06
    let pCarbon = co2Grams     * 0.12
    let pEnergy = energyWh     * 0.04

    let total = max(0, Int((pSheets + pTrees + pWater + pCarbon + pEnergy).rounded()))

    return TreeImpact(
        pages:        totalPages,
        cardPages:    cardPages,
        a4Pages:      a4Pages,
        smallPages:   smallPages,
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
        totalPoints:  total
    )
}

// MARK: - Sustainability hero

struct SustainabilityHero: View {
    /// Per-size page counts keyed by `PaperSize` raw value. The
    /// home screen feeds this from `CaptureListViewModel.pagesBySize`
    /// (which mirrors the SQL `GROUP BY paper_size` on captures).
    /// Missing keys are treated as 0 — a freshly-installed library
    /// flows through as an empty dict and the hero renders its
    /// empty-state branch.
    let pagesBySize: [String: Int]

    /// Bound by the parent so the underlying view controller can
    /// drive `.statusBarHidden(showBreakdown)` from the home screen
    /// — at `.large` detent the sheet doesn't cover the status-bar
    /// strip, so hiding it has to happen on the presenting VC, not
    /// inside the sheet's content. Defaults to a local
    /// `@State` for preview / standalone-use cases.
    @Binding var showBreakdown: Bool

    /// Lifetime page count derived from the size breakdown — drives
    /// the headline + empty-state branch. Computed (not stored) so a
    /// new `pagesBySize` value re-derives it on the same render.
    private var totalPages: Int { pagesBySize.values.reduce(0, +) }

    /// Recompute only when `pagesBySize` changes — same intent as
    /// Android's `remember(pagesBySize)`.
    private var impact: TreeImpact {
        computeTreeImpact(pagesBySize: typedPagesBySize(pagesBySize))
    }

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
                        Text("Five LCA factors. Per-page weight scales with page size.")
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
                // Per-size breakdown of pages captured. Each row only
                // renders when its bucket is non-empty so a brand-new
                // library doesn't read as three "0 pages" lines.
                // Falls back to a single "Sheets engaged" row when no
                // bucket has any pages yet — preserves the previous
                // empty-state copy.
                if impact.cardPages == 0 && impact.a4Pages == 0 && impact.smallPages == 0 {
                    breakdownRow(
                        label:   "Sheets engaged",
                        value:   "0 pages",
                        caption: "Lifetime captures across all notebooks"
                    )
                } else {
                    if impact.cardPages > 0 {
                        breakdownRow(
                            label:   "Business cards",
                            value:   String(format: "%@ cards",
                                            integerFormatter.string(from: NSNumber(value: impact.cardPages)) ?? "0"),
                            caption: "Each card saves a bulk print run"
                        )
                    }
                    if impact.a4Pages > 0 {
                        breakdownRow(
                            label:   "A4 documents",
                            value:   String(format: "%@ pages",
                                            integerFormatter.string(from: NSNumber(value: impact.a4Pages)) ?? "0"),
                            caption: "Standard letter / A4 captures"
                        )
                    }
                    if impact.smallPages > 0 {
                        breakdownRow(
                            label:   "Smaller pages",
                            value:   String(format: "%@ pages",
                                            integerFormatter.string(from: NSNumber(value: impact.smallPages)) ?? "0"),
                            caption: "Imports smaller than A4"
                        )
                    }
                }
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
                             caption: "+0.4 / +0.2 / +0.1 pts per page (card / A4 / smaller)")
                breakdownRow(label: "Tree milestone",
                             value: pointsLabel(impact.pTrees),
                             caption: "+1,200 pts per tree-equivalent saved")
                breakdownRow(label: "Water",
                             value: pointsLabel(impact.pWater),
                             caption: "+6 pts per 100 L")
                breakdownRow(label: "CO₂",
                             value: pointsLabel(impact.pCarbon),
                             caption: "+12 pts per 100 g avoided")
                breakdownRow(label: "Energy",
                             value: pointsLabel(impact.pEnergy),
                             caption: "+4 pts per 100 Wh")

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
            SustainabilityHero(pagesBySize: [:],                              showBreakdown: .constant(false))
            SustainabilityHero(pagesBySize: ["a4": 1],                        showBreakdown: .constant(false))
            SustainabilityHero(pagesBySize: ["a4": 100],                      showBreakdown: .constant(false))
            SustainabilityHero(pagesBySize: ["a4": 980, "card": 20],          showBreakdown: .constant(false))
            SustainabilityHero(pagesBySize: ["a4": 8_300, "card": 33],        showBreakdown: .constant(false))
        }
        .padding()
        .background(QuickInkColors.bg)
        .previewDisplayName("Sustainability hero — varying totals")
    }
}
#endif
