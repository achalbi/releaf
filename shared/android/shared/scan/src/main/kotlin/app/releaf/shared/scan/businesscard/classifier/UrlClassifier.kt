/*
 * UrlClassifier.kt
 *
 * Pulls website URLs out of business-card OCR text. Three accept
 * paths, in priority order:
 *   1. Explicit `http://` / `https://` schemes — high confidence.
 *   2. `www.` prefix — also high confidence.
 *   3. Bare-domain heuristic: `<word>.<word>` where the trailing
 *      token is a known TLD (com / in / co / org / net / io / app
 *      / gov / edu / etc.). Lower confidence; emitted with a small
 *      penalty so a stray "abc.xyz" string isn't mistaken for a URL.
 *
 * Output is the URL trimmed of trailing punctuation, lower-cased
 * for the host, scheme preserved when present.
 */

package app.releaf.shared.scan.businesscard.classifier

import app.releaf.shared.scan.businesscard.FieldCandidate
import app.releaf.shared.scan.businesscard.FieldKind
import app.releaf.shared.scan.businesscard.ScoringWeights
import app.releaf.shared.scan.businesscard.pipeline.LayoutContext
import app.releaf.shared.scan.businesscard.pipeline.engineConfidenceBonus

class UrlClassifier : Classifier {

    override fun classify(layout: LayoutContext, weights: ScoringWeights): List<FieldCandidate> {
        val out = mutableListOf<FieldCandidate>()
        for ((idx, block) in layout.blocks.withIndex()) {
            // Pass 1: explicit scheme.
            for (match in SCHEME_URL_REGEX.findAll(block.text)) {
                out += FieldCandidate(
                    sourceBlockIndex = idx,
                    text             = cleanTrailing(match.value),
                    kind             = FieldKind.WEBSITE,
                    score            = weights.websiteBase + engineConfidenceBonus(block, weights),
                )
            }
            // Pass 2: www-prefixed.
            for (match in WWW_URL_REGEX.findAll(block.text)) {
                // Skip if already covered by the scheme pass.
                if (block.text.indexOf("://${match.value.removePrefix("www.")}") >= 0) continue
                out += FieldCandidate(
                    sourceBlockIndex = idx,
                    text             = cleanTrailing(match.value),
                    kind             = FieldKind.WEBSITE,
                    score            = weights.websiteBase + engineConfidenceBonus(block, weights),
                )
            }
            // Pass 3: bare domain with a known TLD.
            for (match in BARE_DOMAIN_REGEX.findAll(block.text)) {
                val tld = match.groupValues[2].lowercase()
                if (tld !in COMMON_TLDS) continue
                // Skip emails — the local part of an email contains
                // an @ which the bare-domain regex doesn't match,
                // but the host part (after @) does. Reject when the
                // match is preceded by "@".
                val start = match.range.first
                if (start > 0 && block.text[start - 1] == '@') continue
                out += FieldCandidate(
                    sourceBlockIndex = idx,
                    text             = cleanTrailing(match.value).lowercase(),
                    kind             = FieldKind.WEBSITE,
                    // Slight discount vs. scheme/www since the signal is weaker.
                    score            = (weights.websiteBase - 1.0) + engineConfidenceBonus(block, weights),
                )
            }
        }
        return out
    }

    companion object {
        private val SCHEME_URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val WWW_URL_REGEX    = Regex("""www\.[A-Za-z0-9\-._~]+\.[A-Za-z]{2,24}(?:/\S*)?""", RegexOption.IGNORE_CASE)
        private val BARE_DOMAIN_REGEX = Regex("""\b([A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9\-]+)*)\.([A-Za-z]{2,24})\b""")

        private val COMMON_TLDS = setOf(
            "com", "co", "in", "org", "net", "io", "app", "ai", "dev",
            "gov", "edu", "biz", "info", "us", "uk", "de", "fr", "jp",
            "ca", "au", "br", "ru", "cn", "tech", "design", "studio",
            "store", "online", "site", "xyz", "me",
        )

        private fun cleanTrailing(s: String): String =
            s.trimEnd { it == '.' || it == ',' || it == ';' || it == ':' || it == ')' || it == ']' }
    }
}
