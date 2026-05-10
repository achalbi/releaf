/*
 * ScoringWeights.kt
 *
 * Centralised tunables for the business-card extraction pipeline.
 * Every magic number lives here — classifiers use the typed accessors,
 * and the benchmark harness can drive grid searches over alternative
 * weight sets without reaching into individual classifiers.
 *
 * Defaults are calibrated for typical Indian business cards (English-
 * Latin, mixed-script names, +91 / 10-digit phone numbers). Callers
 * with different priors can supply their own instance to
 * [BusinessCardExtractor.extract].
 */

package app.releaf.shared.scan.businesscard

data class ScoringWeights(
    // Per-kind base scores. Higher base = "this kind has a stronger
    // independent signal". Email's regex hit is near-certain, name
    // requires multiple weak signals to add up.
    val emailBase: Double       = 10.0,
    val phoneBase: Double       = 9.0,
    val websiteBase: Double     = 8.0,
    val designationBase: Double = 5.0,
    val nameBase: Double        = 4.0,
    val companyBase: Double     = 4.0,
    val addressBase: Double     = 4.0,
    val postcodeBase: Double    = 6.0,

    // Layout bonuses. Top-of-card and large-font text are strong
    // priors for name / company. Caps at the listed values so a
    // single ultra-tall block doesn't dominate.
    val largeTextBonusMax: Double  = 6.0,
    val topPositionBonusMax: Double = 6.0,
    /**
     * Bonus applied when a designation candidate sits directly below
     * a name candidate (within `adjacencyYDistance` on the y-axis).
     * Captures the "Name / Title" stacking that's near-universal on
     * cards.
     */
    val nameDesignationAdjacencyBonus: Double = 4.0,
    val adjacencyYDistance: Double            = 0.10,

    // Vocabulary bonuses.
    val designationVocabBonus: Double = 5.0,
    val companySuffixBonus: Double    = 5.0,

    // Engine confidence multiplier — Apple Vision exposes per-block
    // confidence; ML Kit Play-Services 19.0.1 emits null. Treated as
    // neutral (0) when null so Android isn't penalised by absence
    // of signal.
    val engineConfidenceWeight: Double = 4.0,

    // Penalties — applied to specific (kind, signal) combinations.
    val nameDigitsPenalty: Double         = 10.0,
    val nameTokenCountPenalty: Double     = 5.0   // > 4 words doesn't read as a name
    ,
    val companyPunctuationPenalty: Double = 3.0,
    val duplicateOverlapPenalty: Double   = 2.0,

    // Selection thresholds. Candidates below the per-kind minimum
    // are dropped; pipeline emits null for the field (or skips the
    // entry for multi-value fields).
    val minNameScore: Double        = 5.0,
    val minCompanyScore: Double     = 5.0,
    val minDesignationScore: Double = 5.0,
    val minAddressScore: Double     = 4.0,
    val minPhoneScore: Double       = 6.0,
    val minEmailScore: Double       = 8.0,
    val minWebsiteScore: Double     = 6.0,
)
