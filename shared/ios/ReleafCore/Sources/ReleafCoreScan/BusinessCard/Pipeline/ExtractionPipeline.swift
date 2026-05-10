/*
 * ExtractionPipeline.swift  — mirror of ExtractionPipeline.kt.
 */

import Foundation

public struct ExtractionPipeline: Sendable {

    private let weights: ScoringWeights
    private let classifiers: [Classifier]

    public init(
        weights: ScoringWeights = ScoringWeights(),
        classifiers: [Classifier] = [
            EmailClassifier(),
            PhoneClassifier(),
            UrlClassifier(),
            PostcodeClassifier(),
            NameClassifier(),
            DesignationClassifier(),
            CompanyClassifier(),
        ]
    ) {
        self.weights     = weights
        self.classifiers = classifiers
    }

    public func extract(_ blocks: [OcrBlock], keepTrace: Bool = false) -> ExtractedContact {
        guard !blocks.isEmpty else { return .empty }
        var timings: [String: UInt64] = [:]

        // ML Kit emits both Paragraph- and Line-grained blocks; the
        // Paragraph block concatenates every line on the card and
        // poisons the classifier scores. Filter to Line when present.
        // Vision only emits Line, so this is a no-op there. Mirror
        // of the Android pipeline.
        let preferred: [OcrBlock] = {
            if blocks.contains(where: { $0.kind == .line }) {
                return blocks.filter { $0.kind == .line }
            }
            return blocks
        }()
        guard !preferred.isEmpty else { return .empty }

        let t0 = DispatchTime.now().uptimeNanoseconds
        let layout = layoutOf(preferred)
        timings["layout"] = DispatchTime.now().uptimeNanoseconds - t0

        let t1 = DispatchTime.now().uptimeNanoseconds
        var baseCandidates: [FieldCandidate] = []
        for c in classifiers {
            baseCandidates.append(contentsOf: c.classify(layout: layout, weights: weights))
        }
        timings["classifiers"] = DispatchTime.now().uptimeNanoseconds - t1

        // Address — needs postcode results.
        let postcodeBlocks = Set(
            baseCandidates.filter { $0.kind == .postcode }.map(\.sourceBlockIndex)
        )
        let t2 = DispatchTime.now().uptimeNanoseconds
        let addressCandidates = AddressClassifier(postcodeBlockIndices: postcodeBlocks)
            .classify(layout: layout, weights: weights)
        timings["address"] = DispatchTime.now().uptimeNanoseconds - t2

        let allCandidates = baseCandidates + addressCandidates

        // Drop candidates whose own text reads as the category
        // name (or one of its OCR-error aliases). Mirror of the
        // Android pipeline filter — same stop words, same
        // alphanumeric-only normalisation.
        let filteredCandidates = allCandidates.filter { candidate in
            !Self.categoryStopWords.contains(Self.normaliseForStopCheck(candidate.text))
        }

        // Cross-kind layout bonus — name-above-designation pattern.
        // Mirror of the Android pipeline.
        let boostedCandidates = applyNameDesignationAdjacencyBonus(
            filteredCandidates,
            layout: layout
        )

        let t3 = DispatchTime.now().uptimeNanoseconds
        let resolved = resolve(boostedCandidates)
        timings["resolve"] = DispatchTime.now().uptimeNanoseconds - t3

        let trace = keepTrace
            ? ExtractionTrace(candidates: allCandidates, timings: timings)
            : nil

        return ExtractedContact(
            name:        resolved.name,
            company:     resolved.company,
            designation: resolved.designation,
            phones:      resolved.phones,
            emails:      resolved.emails,
            websites:    resolved.websites,
            address:     resolved.address,
            confidence:  resolved.confidence,
            trace:       trace
        )
    }

    private func resolve(_ candidates: [FieldCandidate]) -> ExtractedContact {
        let byKind: [FieldKind: [FieldCandidate]] = Dictionary(grouping: candidates) { $0.kind }

        let emails  = pickMulti(byKind[.email]   ?? [], min: weights.minEmailScore)
        let phones  = pickMulti(byKind[.phone]   ?? [], min: weights.minPhoneScore)
        let websites = pickMulti(byKind[.website] ?? [], min: weights.minWebsiteScore)

        // Greedy cross-kind resolution. Sort the union of single-value
        // candidates by score desc; claim each iff neither the source
        // block nor the kind has been claimed yet. Mirror of Android's
        // resolver logic.
        let singleValueKinds: Set<FieldKind> = [.name, .company, .designation]
        let mins: [FieldKind: Double] = [
            .name:        weights.minNameScore,
            .company:     weights.minCompanyScore,
            .designation: weights.minDesignationScore,
        ]
        var claimedBlocks: Set<Int> = []
        var claimedKinds: [FieldKind: FieldCandidate] = [:]
        let pool = candidates
            .filter { singleValueKinds.contains($0.kind) && $0.score >= (mins[$0.kind] ?? .greatestFiniteMagnitude) }
            .sorted { $0.score > $1.score }
        for c in pool {
            if claimedKinds[c.kind] != nil { continue }
            if claimedBlocks.contains(c.sourceBlockIndex) { continue }
            claimedKinds[c.kind] = c
            claimedBlocks.insert(c.sourceBlockIndex)
        }
        let name        = claimedKinds[.name]
        let company     = claimedKinds[.company]
        let designation = claimedKinds[.designation]

        let address = pickSingle(byKind[.address] ?? [], min: weights.minAddressScore, avoid: [])

        var populated: [Double] = []
        if let n = name        { populated.append(n.score) }
        if let c = company     { populated.append(c.score) }
        if let d = designation { populated.append(d.score) }
        if let a = address     { populated.append(a.score) }
        populated.append(contentsOf: (byKind[.phone] ?? [])
            .filter { $0.score >= weights.minPhoneScore }.map(\.score))
        populated.append(contentsOf: (byKind[.email] ?? [])
            .filter { $0.score >= weights.minEmailScore }.map(\.score))
        populated.append(contentsOf: (byKind[.website] ?? [])
            .filter { $0.score >= weights.minWebsiteScore }.map(\.score))

        let confidence: Double
        if populated.isEmpty {
            confidence = 0
        } else {
            let mean = populated.reduce(0, +) / Double(populated.count)
            confidence = max(0, min(1, mean / 25.0))
        }

        return ExtractedContact(
            name:        name?.text,
            company:     company?.text,
            designation: designation?.text,
            phones:      phones,
            emails:      emails,
            websites:    websites,
            address:     address?.text,
            confidence:  confidence
        )
    }

    private func pickSingle(
        _ candidates: [FieldCandidate],
        min: Double,
        avoid: Set<Int>
    ) -> FieldCandidate? {
        candidates
            .filter { $0.score >= min && !avoid.contains($0.sourceBlockIndex) }
            .max(by: { $0.score < $1.score })
    }

    private func pickMulti(
        _ candidates: [FieldCandidate],
        min: Double
    ) -> [String] {
        var seen: Set<String> = []
        var out: [String] = []
        for c in candidates {
            if c.score < min { continue }
            let key = c.text.lowercased()
            if seen.insert(key).inserted { out.append(c.text) }
        }
        return out
    }

    /// Boost NAME / DESIGNATION pairs where the name's bbox sits
    /// directly above the designation's bbox. The pattern is the
    /// single most reliable layout cue on business cards. Adds
    /// `nameDesignationAdjacencyBonus` to BOTH candidates' scores
    /// when matched. Mirror of `applyNameDesignationAdjacencyBonus`
    /// in the Android pipeline.
    private func applyNameDesignationAdjacencyBonus(
        _ candidates: [FieldCandidate],
        layout: LayoutContext
    ) -> [FieldCandidate] {
        let names  = candidates.filter { $0.kind == .name }
        let desigs = candidates.filter { $0.kind == .designation }
        if names.isEmpty || desigs.isEmpty { return candidates }

        var nameBoosts:  Set<Int> = []
        var desigBoosts: Set<Int> = []
        for n in names {
            let nBox = layout.blocks[n.sourceBlockIndex].bbox
            let nBottom = nBox.y + nBox.height
            for d in desigs {
                let dBox = layout.blocks[d.sourceBlockIndex].bbox
                let gap = dBox.y - nBottom
                if gap >= 0, gap <= weights.adjacencyYDistance {
                    nameBoosts.insert(n.sourceBlockIndex)
                    desigBoosts.insert(d.sourceBlockIndex)
                }
            }
        }

        if nameBoosts.isEmpty { return candidates }
        return candidates.map { c in
            if c.kind == .name && nameBoosts.contains(c.sourceBlockIndex) {
                return FieldCandidate(
                    sourceBlockIndex: c.sourceBlockIndex,
                    text:             c.text,
                    kind:             c.kind,
                    score:            c.score + weights.nameDesignationAdjacencyBonus
                )
            }
            if c.kind == .designation && desigBoosts.contains(c.sourceBlockIndex) {
                return FieldCandidate(
                    sourceBlockIndex: c.sourceBlockIndex,
                    text:             c.text,
                    kind:             c.kind,
                    score:            c.score + weights.nameDesignationAdjacencyBonus
                )
            }
            return c
        }
    }

    /// Strings that should never surface as an extracted field on a
    /// Business Card capture. Compared against each candidate's
    /// alphanumeric-only lower-cased text so "Business Card",
    /// "Business-Card", "businesscard.", and the "8usiness" OCR-
    /// error aliases all collapse to the same key.
    private static let categoryStopWords: Set<String> = [
        "businesscard",
        "card",
        "business",
        "8usinesscard",
        "8usiness",
    ]

    private static func normaliseForStopCheck(_ s: String) -> String {
        s.lowercased().filter { $0.isLetter || $0.isNumber }
    }
}
