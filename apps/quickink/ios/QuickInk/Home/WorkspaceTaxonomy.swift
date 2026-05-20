/*
 * WorkspaceTaxonomy.swift
 *
 * Phase 1 primitives for the Workspace tab refresh
 * (`design/WORKSPACE_TAB_HANDOFF.md`):
 *
 *   - `TagBucket`        — value type describing a tag-vocabulary
 *                          bucket (id, name, question, hue, prefix,
 *                          controlled / exclusive / auto-applied
 *                          flags). Seeded in Phase 2 via
 *                          `workspace_seed.json`; this file ships
 *                          the in-code defaults so the primitives
 *                          render in previews before the data
 *                          layer lands.
 *   - `FolderTier`       — presentation-only grouping (1 Workflow,
 *                          2 Life Domains, 3 Creative & Output)
 *                          surfaced by the folders section.
 *   - `TierHeader`       — section header for a tier block.
 *   - `FolderRow`        — single row in a tier block.
 *   - `TagBucketBlock`   — bar + name + question + count pill +
 *                          pill row, one per bucket.
 *   - `TagPill`          — pill rendering inside a bucket block.
 *
 * Phase 1 ships the components and the static bucket seed only —
 * no screen is wired up yet. Phase 3 plumbs `FolderRow` into the
 * Workspace tab; Phase 4 plumbs `TagBucketBlock`.
 *
 * Mirror of Android `WorkspaceTaxonomy.kt`.
 */

import SwiftUI

// MARK: - Bucket model

/// A tag-vocabulary bucket. Phase 1 keeps this in code; Phase 2
/// reads the same shape from `workspace_seed.json` and persists
/// the bucket id on each tag row.
public struct TagBucket: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let question: String
    public let hue: Color
    /// Zero, one, or many prefix strings (e.g. `["org/", "place/"]`).
    /// `nil` → bucket is unprefixed (Status, Energy, …). User-added
    /// tags in a prefixed bucket auto-complete the prefix.
    public let prefixes: [String]?
    public let controlled: Bool
    public let exclusive: Bool
    public let autoApplied: Bool

    public init(
        id: String,
        name: String,
        question: String,
        hue: Color,
        prefixes: [String]? = nil,
        controlled: Bool = false,
        exclusive: Bool = false,
        autoApplied: Bool = false
    ) {
        self.id          = id
        self.name        = name
        self.question    = question
        self.hue         = hue
        self.prefixes    = prefixes
        self.controlled  = controlled
        self.exclusive   = exclusive
        self.autoApplied = autoApplied
    }
}

/// The seven canonical buckets in the order spec'd by §4.2. Phase 4
/// reads this list to render `TagBucketBlock`s top-to-bottom.
public let workspaceTagBuckets: [TagBucket] = [
    TagBucket(id: "status",   name: "Status",           question: "what state is it in?",            hue: QuickInkColors.bucketStatus,   controlled: true),
    TagBucket(id: "people",   name: "People",           question: "who is this about?",              hue: QuickInkColors.bucketPeople,   prefixes: ["p/"]),
    TagBucket(id: "orgplace", name: "Org & Place",      question: "what organization or location?", hue: QuickInkColors.bucketOrgPlace, prefixes: ["org/", "place/"]),
    TagBucket(id: "energy",   name: "Energy",           question: "what state of mind does it need?", hue: QuickInkColors.bucketEnergy, controlled: true),
    TagBucket(id: "time",     name: "Time-sensitivity", question: "which horizon?",                  hue: QuickInkColors.bucketTime,     controlled: true, exclusive: true),
    TagBucket(id: "kind",     name: "Kind",             question: "what kind of content?",           hue: QuickInkColors.bucketKind),
    TagBucket(id: "source",   name: "Source",           question: "where did it come from?",         hue: QuickInkColors.bucketSource,   controlled: true, autoApplied: true),
]

// MARK: - Folder tier

/// Presentation-only tier grouping for the folders section. The
/// underlying `folders` table stores `tier` as an int (1, 2, 3)
/// on each row; Phase 2 adds that column. `FolderTier.custom`
/// (= 0) is the visual bucket for user-created folders that
/// coexist alongside the 12 seeded ones.
public enum FolderTier: Int, CaseIterable, Identifiable, Equatable, Sendable {
    case workflow  = 1
    case life      = 2
    case creative  = 3
    case custom    = 0

    public var id: Int { rawValue }

    public var label: String {
        switch self {
        case .workflow: return "Workflow"
        case .life:     return "Life domains"
        case .creative: return "Creative & output"
        case .custom:   return "Custom"
        }
    }

    /// Optional italic sub-label rendered next to the tier name.
    /// `nil` means no sub-label (tier 1 reads as a clean masthead).
    public var sub: String? {
        switch self {
        case .workflow: return nil
        case .life:     return "where it belongs"
        case .creative: return "output, study, sparks"
        case .custom:   return "your own folders"
        }
    }

    /// Coral numeral shown to the left of the tier label.
    public var numeral: String {
        switch self {
        case .workflow: return "1"
        case .life:     return "2"
        case .creative: return "3"
        case .custom:   return "+"
        }
    }

    public var stripeColor: Color {
        switch self {
        case .workflow: return QuickInkColors.tier1
        case .life:     return QuickInkColors.ink
        case .creative: return QuickInkColors.tier3
        case .custom:   return QuickInkColors.muted
        }
    }
}

// MARK: - TierHeader

/// Section header above a tier's stack of `FolderRow`s. Numeral
/// + label + optional italic sub + 1 px divider beneath.
public struct TierHeader: View {
    let tier: FolderTier

    public init(tier: FolderTier) {
        self.tier = tier
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(tier.numeral)
                    .font(QuickInkFont.serif(14, weight: .medium))
                    .tracking(0.84)
                    .foregroundStyle(QuickInkColors.accent)
                Text(tier.label.uppercased())
                    .font(QuickInkText.eyebrow)
                    .tracking(QuickInkLetterSpacing.eyebrow)
                    .foregroundStyle(QuickInkColors.ink)
                if let sub = tier.sub {
                    Text(sub)
                        .font(QuickInkFont.ui(11, weight: .regular).italic())
                        .foregroundStyle(QuickInkColors.muted)
                }
                Spacer(minLength: 0)
            }
            .padding(.bottom, 4)

            Rectangle()
                .fill(QuickInkColors.border)
                .frame(height: 1)
                .padding(.top, 8)
        }
    }
}

// MARK: - FolderRow

/// One folder in a tier block. `description` is optional — when
/// nil the row collapses to one text line. `isSystemManaged`
/// renders the Inbox lock glyph next to the name (per §9 q3 —
/// answered yes, default-on for system-managed folders).
public struct FolderRow: View {
    let name: String
    let description: String?
    let count: Int
    let tier: FolderTier
    let isSystemManaged: Bool
    let showBottomBorder: Bool
    let action: () -> Void

    public init(
        name: String,
        description: String? = nil,
        count: Int,
        tier: FolderTier,
        isSystemManaged: Bool = false,
        showBottomBorder: Bool = true,
        action: @escaping () -> Void
    ) {
        self.name             = name
        self.description      = description
        self.count            = count
        self.tier             = tier
        self.isSystemManaged  = isSystemManaged
        self.showBottomBorder = showBottomBorder
        self.action           = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                    .fill(tier.stripeColor)
                    .frame(width: 3, height: 26)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(name)
                            .font(QuickInkFont.ui(15, weight: .medium))
                            .foregroundStyle(QuickInkColors.ink)
                            .lineLimit(1)
                        if isSystemManaged {
                            Image(systemName: "lock.fill")
                                .font(.system(size: 10))
                                .foregroundStyle(QuickInkColors.muted)
                                .accessibilityLabel("system-managed")
                        }
                    }
                    if let description {
                        Text(description)
                            .font(QuickInkFont.ui(12, weight: .regular))
                            .foregroundStyle(QuickInkColors.muted)
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                }

                Spacer(minLength: 8)

                CountPill(count: count)

                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .regular))
                    .foregroundStyle(QuickInkColors.muted)
            }
            .padding(.vertical, 13)
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) {
                if showBottomBorder {
                    Rectangle()
                        .fill(QuickInkColors.border)
                        .frame(height: 1)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("\(name), \(count) \(count == 1 ? "item" : "items")"))
        .accessibilityHint(Text("Open folder"))
    }
}

// MARK: - Count pill

/// The 999-radius soft-bg badge used in folder rows and bucket
/// blocks. Exposed publicly because Phase 3 / 4 share it.
public struct CountPill: View {
    let count: Int
    public init(count: Int) { self.count = count }

    public var body: some View {
        Text(formatted)
            .font(QuickInkFont.ui(12, weight: .medium))
            .foregroundStyle(QuickInkColors.inkSoft)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .frame(minWidth: 26)
            .background(
                Capsule(style: .continuous).fill(QuickInkColors.accentSoft)
            )
    }

    private var formatted: String {
        count > 999 ? "999+" : "\(count)"
    }
}

// MARK: - TagPill

/// 26-pt-tall capsule used inside a `TagBucketBlock` pill row.
/// Background is the bucket hue at 12 % opacity over canvas;
/// border + text are the bucket hue at 100 %. The `.add` variant
/// renders a dashed-border placeholder for prefixed / uncontrolled
/// buckets ("+ add").
public struct TagPill: View {
    public enum Variant: Equatable {
        case filled(hue: Color)
        case add
    }

    let label: String
    let variant: Variant
    let action: () -> Void

    public init(label: String, hue: Color, action: @escaping () -> Void = {}) {
        self.label   = label
        self.variant = .filled(hue: hue)
        self.action  = action
    }

    public init(addLabel: String = "+ add", action: @escaping () -> Void = {}) {
        self.label   = addLabel
        self.variant = .add
        self.action  = action
    }

    public var body: some View {
        Button(action: action) {
            Text(label)
                .font(QuickInkFont.ui(12, weight: .medium))
                .foregroundStyle(textColor)
                .padding(.horizontal, 11)
                .frame(height: 26)
                .background(backgroundFill, in: Capsule(style: .continuous))
                .overlay(border)
        }
        .buttonStyle(.plain)
    }

    private var textColor: Color {
        switch variant {
        case .filled(let hue): return hue
        case .add:             return QuickInkColors.muted
        }
    }

    /// Capsule fill color — 12 % bucket-hue over canvas (matches the
    /// HTML mock's `--*-bg` tokens, pre-mixed pastels close to the
    /// hue at ~12 % opacity over the cream canvas). The `.add`
    /// variant sits on canvas for the dashed-border affordance.
    private var backgroundFill: Color {
        switch variant {
        case .filled(let hue): return hue.opacity(0.12)
        case .add:             return QuickInkColors.bg
        }
    }

    @ViewBuilder
    private var border: some View {
        switch variant {
        case .filled(let hue):
            Capsule(style: .continuous).stroke(hue, lineWidth: 1)
        case .add:
            Capsule(style: .continuous)
                .stroke(
                    QuickInkColors.border,
                    style: StrokeStyle(lineWidth: 1, dash: [3, 3])
                )
        }
    }
}

// MARK: - TagBucketBlock

/// One block of the tag vocabulary section — bar + name + question
/// + count pill + pill row. Tap on a pill routes via `onTapTag`;
/// tap on the `+ add` pill (rendered only for non-`controlled`
/// buckets) routes via `onAddTag`.
public struct TagBucketBlock: View {
    public struct PillSpec: Identifiable, Equatable {
        public let id: String
        public let label: String
        public init(id: String, label: String) {
            self.id    = id
            self.label = label
        }
    }

    let bucket: TagBucket
    let pills: [PillSpec]
    let showBottomBorder: Bool
    let onTapTag: (PillSpec) -> Void
    let onAddTag: () -> Void

    public init(
        bucket: TagBucket,
        pills: [PillSpec],
        showBottomBorder: Bool = true,
        onTapTag: @escaping (PillSpec) -> Void = { _ in },
        onAddTag: @escaping () -> Void = {}
    ) {
        self.bucket           = bucket
        self.pills            = pills
        self.showBottomBorder = showBottomBorder
        self.onTapTag         = onTapTag
        self.onAddTag         = onAddTag
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center, spacing: 8) {
                RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                    .fill(bucket.hue)
                    .frame(width: 3, height: 26)

                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 6) {
                        Text(bucket.name.uppercased())
                            .font(QuickInkFont.ui(12.5, weight: .semibold))
                            .tracking(0.5)
                            .foregroundStyle(bucket.hue)
                        if let prefixGlyph {
                            Text(prefixGlyph)
                                .font(QuickInkFont.ui(12.5, weight: .regular))
                                .foregroundStyle(QuickInkColors.muted)
                        }
                        if bucket.autoApplied {
                            Text("· auto-applied")
                                .font(QuickInkFont.ui(12.5, weight: .regular))
                                .foregroundStyle(QuickInkColors.muted)
                        }
                    }
                    Text(bucket.question)
                        .font(QuickInkFont.ui(11, weight: .regular).italic())
                        .foregroundStyle(QuickInkColors.muted)
                }

                Spacer(minLength: 0)

                CountPill(count: pills.count)
            }

            // Pill row — wrap onto multiple lines. The HTML mock
            // uses a 15px left indent under the bar; on iOS we fold
            // that into the leading padding for a clean
            // alignment with the bucket-name baseline.
            FlowLayout(spacing: 6, runSpacing: 6) {
                ForEach(pills) { pill in
                    TagPill(label: pill.label, hue: bucket.hue) {
                        onTapTag(pill)
                    }
                }
                if !bucket.controlled {
                    TagPill(action: onAddTag)
                }
            }
            .padding(.leading, 15)
            .padding(.bottom, 4)
        }
        .padding(.top, 12)
        .overlay(alignment: .bottom) {
            if showBottomBorder {
                Rectangle()
                    .fill(QuickInkColors.border)
                    .frame(height: 1)
            }
        }
    }

    private var prefixGlyph: String? {
        guard let prefixes = bucket.prefixes, !prefixes.isEmpty else { return nil }
        return "(#" + prefixes.joined(separator: ", #") + ")"
    }
}

// MARK: - FlowLayout

/// Minimal flow layout used by `TagBucketBlock` to wrap pills
/// onto multiple lines. SwiftUI doesn't ship a built-in flow
/// container; this is a small `Layout` impl (iOS 16+).
struct FlowLayout: Layout {
    var spacing: CGFloat
    var runSpacing: CGFloat

    init(spacing: CGFloat = 6, runSpacing: CGFloat = 6) {
        self.spacing    = spacing
        self.runSpacing = runSpacing
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalWidth: CGFloat = 0

        for sv in subviews {
            let size = sv.sizeThatFits(.unspecified)
            if x + size.width > maxWidth, x > 0 {
                y += rowHeight + runSpacing
                x = 0
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            totalWidth = max(totalWidth, x)
        }
        return CGSize(width: totalWidth, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let maxWidth = bounds.width
        var x: CGFloat = bounds.minX
        var y: CGFloat = bounds.minY
        var rowHeight: CGFloat = 0

        for sv in subviews {
            let size = sv.sizeThatFits(.unspecified)
            if x + size.width > bounds.minX + maxWidth, x > bounds.minX {
                y += rowHeight + runSpacing
                x = bounds.minX
                rowHeight = 0
            }
            sv.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
