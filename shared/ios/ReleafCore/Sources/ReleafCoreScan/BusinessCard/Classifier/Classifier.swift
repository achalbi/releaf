/*
 * Classifier.swift
 *
 * Strategy protocol for entity classifiers. Mirror of `Classifier.kt`.
 */

import Foundation

public protocol Classifier: Sendable {
    func classify(layout: LayoutContext, weights: ScoringWeights) -> [FieldCandidate]
}
