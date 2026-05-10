/*
 * DesignationVocab.kt
 *
 * Vocabulary of common designation tokens used by [DesignationClassifier]
 * to flag a block as "almost certainly a job title". Lowercased, word-
 * boundary matched; the classifier scores +5 (designationVocabBonus)
 * per matching token in the block.
 *
 * Curated for English-language Indian / global business cards. Not
 * exhaustive — false negatives fall back to size + position
 * heuristics, so adding to the list is purely accuracy-positive.
 *
 * Mirror this list when adding to the iOS counterpart.
 */

package app.releaf.shared.scan.businesscard.vocab

object DesignationVocab {
    /**
     * Token-level matches. Compared after lower-casing and stripping
     * non-alphanumeric punctuation so "C.E.O" / "C.E.O." / "ceo" all
     * match the same entry.
     */
    val tokens: Set<String> = setOf(
        // C-suite
        "ceo", "cto", "cfo", "coo", "cmo", "cio", "cpo", "cso", "chro", "ciso",
        // President / Founder family
        "president", "founder", "cofounder", "co-founder", "owner", "proprietor",
        // Director / VP family
        "director", "vp", "vice", "executive",
        "managing", "deputy", "associate", "assistant", "principal",
        // Manager family
        "manager", "supervisor", "lead", "head", "chief",
        // Engineering
        "engineer", "developer", "programmer", "architect", "specialist", "consultant",
        "scientist", "analyst", "researcher", "technologist", "technician",
        // Sales / marketing
        "sales", "marketing", "business", "growth", "account", "partner", "partnerships",
        // Other common roles
        "designer", "writer", "editor", "producer", "coordinator", "administrator",
        "advisor", "advocate", "officer", "secretary", "accountant", "auditor",
        "trainer", "teacher", "professor", "doctor", "dr",
        // Legal / medical
        "attorney", "lawyer", "counsel", "physician", "surgeon",
    )

    /**
     * Multi-token phrases that don't reduce cleanly to single-token
     * matches. Compared as substrings (case-insensitive) of the
     * block's normalised text.
     */
    val phrases: List<String> = listOf(
        "vice president",
        "managing director",
        "general manager",
        "regional manager",
        "country head",
        "head of",
        "chief of",
        "director of",
        "manager of",
        "lead engineer",
        "senior engineer",
        "junior engineer",
        "software engineer",
        "data scientist",
        "product manager",
        "project manager",
        "program manager",
        "account manager",
    )
}
