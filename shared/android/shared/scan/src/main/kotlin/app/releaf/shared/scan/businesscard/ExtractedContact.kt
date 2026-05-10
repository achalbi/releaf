/*
 * ExtractedContact.kt
 *
 * Structured output of the [BusinessCardExtractor]. One per scanned
 * card; each field is best-effort with the field's own confidence
 * surfaced via [confidence] (the whole-card mean) and via the per-
 * candidate [ExtractionTrace] when callers ask for it.
 *
 * Mirror of `ExtractedContact.swift` in `ReleafCoreScan.BusinessCard`
 * — both platforms speak the same vocabulary so a future shared-test
 * fixture can produce identical golden outputs.
 */

package app.releaf.shared.scan.businesscard

/** Final structured contact derived from a card's OCR blocks. */
data class ExtractedContact(
    /** Best-guess full name (may include middle initials). */
    val name: String?,
    /** Company / organisation as printed (with suffix preserved). */
    val company: String?,
    /** Job title / designation. */
    val designation: String?,
    /**
     * All recognised phone numbers in normalised form
     * (10-digit Indian mobile, "+91…" international, or 0-prefixed
     * 11-digit dropped to 10). Order is first-seen on the card.
     */
    val phones: List<String>,
    /** All recognised email addresses, lower-cased, deduplicated. */
    val emails: List<String>,
    /** All recognised websites, normalised to a parseable form. */
    val websites: List<String>,
    /**
     * Multi-line address. Joined with "\n" so the system contact
     * form can split into structured fields downstream.
     */
    val address: String?,
    /**
     * Aggregate confidence across populated fields, 0..1. Callers
     * can show this in the review UI to flag low-confidence cards
     * for the user to double-check before saving.
     */
    val confidence: Double,
    /**
     * Optional extraction trace for debugging / benchmarking. Not
     * populated by default — callers pass `keepTrace = true` to
     * `BusinessCardExtractor.extract` to populate.
     */
    val trace: ExtractionTrace? = null,
) {
    companion object {
        /** Empty result for cards that yielded no usable signal. */
        val empty = ExtractedContact(
            name        = null,
            company     = null,
            designation = null,
            phones      = emptyList(),
            emails      = emptyList(),
            websites    = emptyList(),
            address     = null,
            confidence  = 0.0,
        )
    }
}

/** What kind of field a [FieldCandidate] represents. */
enum class FieldKind {
    NAME, COMPANY, DESIGNATION, PHONE, EMAIL, WEBSITE, POSTCODE, ADDRESS
}

/**
 * One classifier's vote for a single block. The pipeline collects
 * candidates from every classifier, then resolves to a final
 * [ExtractedContact] by picking the highest-scoring candidate per
 * field (with multi-value fields like phones / emails / websites
 * collecting all hits above a per-kind threshold).
 */
data class FieldCandidate(
    /** Original block index in the input list, for debug / dedup. */
    val sourceBlockIndex: Int,
    /** The text the classifier extracted (may differ from block.text — e.g. a phone number cleaned out of "Mobile: 98765 43210"). */
    val text: String,
    /** What the classifier thinks this is. */
    val kind: FieldKind,
    /** Score, higher = better. Negative means actively rejected. */
    val score: Double,
)

/**
 * Optional debug payload emitted alongside an [ExtractedContact]
 * when the caller asks for it. Lets benchmarks attribute a
 * misclassification to a specific candidate's score breakdown
 * without re-running the whole pipeline.
 */
data class ExtractionTrace(
    /** Every candidate every classifier produced, post-scoring. */
    val candidates: List<FieldCandidate>,
    /** Stage-by-stage timing in nanoseconds (engine-agnostic). */
    val timings: Map<String, Long>,
)
