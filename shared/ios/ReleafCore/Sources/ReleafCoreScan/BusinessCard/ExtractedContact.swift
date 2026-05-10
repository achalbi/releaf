/*
 * ExtractedContact.swift
 *
 * Structured output of the [BusinessCardExtractor]. Mirror of
 * `ExtractedContact.kt` — same field shape, same semantics, same
 * `confidence` calibration so a future shared-test fixture can
 * produce identical golden outputs across both platforms.
 */

import Foundation

public struct ExtractedContact: Equatable, Sendable {
    public let name: String?
    public let company: String?
    public let designation: String?
    public let phones: [String]
    public let emails: [String]
    public let websites: [String]
    public let address: String?
    public let confidence: Double
    public let trace: ExtractionTrace?

    public init(
        name: String?,
        company: String?,
        designation: String?,
        phones: [String],
        emails: [String],
        websites: [String],
        address: String?,
        confidence: Double,
        trace: ExtractionTrace? = nil
    ) {
        self.name        = name
        self.company     = company
        self.designation = designation
        self.phones      = phones
        self.emails      = emails
        self.websites    = websites
        self.address     = address
        self.confidence  = confidence
        self.trace       = trace
    }

    public static let empty = ExtractedContact(
        name:        nil,
        company:     nil,
        designation: nil,
        phones:      [],
        emails:      [],
        websites:    [],
        address:     nil,
        confidence:  0.0
    )
}

/// What kind of field a `FieldCandidate` represents.
public enum FieldKind: Sendable {
    case name, company, designation, phone, email, website, postcode, address
}

/// One classifier's vote for a single block.
public struct FieldCandidate: Equatable, Sendable {
    public let sourceBlockIndex: Int
    public let text: String
    public let kind: FieldKind
    public let score: Double

    public init(sourceBlockIndex: Int, text: String, kind: FieldKind, score: Double) {
        self.sourceBlockIndex = sourceBlockIndex
        self.text             = text
        self.kind             = kind
        self.score            = score
    }
}

/// Optional debug payload — populated when callers ask for it via
/// `BusinessCardExtractor.extract(keepTrace: true)`.
public struct ExtractionTrace: Equatable, Sendable {
    public let candidates: [FieldCandidate]
    public let timings: [String: UInt64]

    public init(candidates: [FieldCandidate], timings: [String: UInt64]) {
        self.candidates = candidates
        self.timings    = timings
    }
}
