/*
 * CompanySuffixVocab.kt
 *
 * Suffixes that strongly identify a block as the company line
 * (vs. a name / role / address). Matched as the trailing token (or
 * trailing phrase) of the block's normalised text — case-insensitive,
 * punctuation-stripped.
 *
 * "Acme Corp." → strips to "acme corp" → matches suffix "corp" → +5.
 *
 * Mirror this list when adding to the iOS counterpart.
 */

package app.releaf.shared.scan.businesscard.vocab

object CompanySuffixVocab {
    /** Single-token suffixes (last word match). */
    val singleTokens: Set<String> = setOf(
        "inc", "incorporated",
        "ltd", "limited",
        "llc", "lp", "llp",
        "corp", "corporation",
        "co", "company",
        "plc",
        "gmbh", "ag", "sa", "sas", "bv", "nv",
        "pte", "pty",
        "kk", "kabushiki",
        // Indian-specific
        "pvt", "private",
        "bharati", "ventures",
        "industries", "enterprises", "trading",
        // Domain-specific common suffixes
        "labs", "studio", "studios", "works", "group",
        "technologies", "tech", "systems", "solutions",
        "consulting", "consultants",
        "services", "associates", "partners",
        "holdings", "global", "international",
    )

    /** Multi-token suffixes — checked as a trailing-phrase match. */
    val multiTokens: List<String> = listOf(
        "pvt ltd",
        "private limited",
        "pvt limited",
        "private ltd",
        "co ltd",
        "co limited",
        "company limited",
        "and co",
        "and company",
        "and sons",
        "and associates",
        "and partners",
    )
}
