/*
 * LayoutAnalysis.swift
 *
 * Pure layout helpers — pre-computed once per extraction and shared
 * across classifiers. Mirror of `LayoutAnalysis.kt`.
 */

import Foundation

public struct LayoutContext: Sendable {
    public let blocks: [OcrBlock]
    public let maxHeight: Double
    public let meanHeight: Double
    public let topY: Double
    public let bottomY: Double
}

public func layoutOf(_ blocks: [OcrBlock]) -> LayoutContext {
    guard !blocks.isEmpty else {
        return LayoutContext(blocks: [], maxHeight: 0, meanHeight: 0, topY: 0, bottomY: 0)
    }
    var maxH = 0.0
    var sumH = 0.0
    var topY: Double = .infinity
    var botY: Double = -.infinity
    for b in blocks {
        maxH = max(maxH, b.bbox.height)
        sumH += b.bbox.height
        topY = min(topY, b.bbox.y)
        botY = max(botY, b.bbox.y + b.bbox.height)
    }
    return LayoutContext(
        blocks:     blocks,
        maxHeight:  maxH,
        meanHeight: sumH / Double(blocks.count),
        topY:       topY,
        bottomY:    botY
    )
}

public func largeTextBonus(_ block: OcrBlock, _ layout: LayoutContext, _ weights: ScoringWeights) -> Double {
    guard layout.maxHeight > 0 else { return 0 }
    let ratio = max(0.0, min(1.0, block.bbox.height / layout.maxHeight))
    return ratio * weights.largeTextBonusMax
}

public func topPositionBonus(_ block: OcrBlock, _ weights: ScoringWeights) -> Double {
    let centreY = block.bbox.y + (block.bbox.height / 2.0)
    let factor = max(0.0, min(1.0, 1.0 - centreY))
    return factor * weights.topPositionBonusMax
}

public func bottomPositionBonus(_ block: OcrBlock, _ weights: ScoringWeights) -> Double {
    let centreY = block.bbox.y + (block.bbox.height / 2.0)
    let factor = max(0.0, min(1.0, (centreY - 0.66) / 0.34))
    return factor * weights.topPositionBonusMax
}

public func engineConfidenceBonus(_ block: OcrBlock, _ weights: ScoringWeights) -> Double {
    guard let c = block.confidence else { return 0 }
    return max(0.0, min(1.0, c)) * weights.engineConfidenceWeight
}

public func verticallyAdjacent(_ a: OcrBbox, _ b: OcrBbox, _ weights: ScoringWeights) -> Bool {
    let aBottom = a.y + a.height
    let bBottom = b.y + b.height
    let gap = min(abs(b.y - aBottom), abs(a.y - bBottom))
    return gap <= weights.adjacencyYDistance
}

public func iou(_ a: OcrBbox, _ b: OcrBbox) -> Double {
    let xLeft   = max(a.x, b.x)
    let yTop    = max(a.y, b.y)
    let xRight  = min(a.x + a.width, b.x + b.width)
    let yBottom = min(a.y + a.height, b.y + b.height)
    if xRight < xLeft || yBottom < yTop { return 0 }
    let inter = (xRight - xLeft) * (yBottom - yTop)
    let areaA = a.width * a.height
    let areaB = b.width * b.height
    let union = areaA + areaB - inter
    return union <= 0 ? 0 : inter / union
}
