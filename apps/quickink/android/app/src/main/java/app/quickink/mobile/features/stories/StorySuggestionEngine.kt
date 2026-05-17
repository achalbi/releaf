/*
 * StorySuggestionEngine.kt
 *
 * Stories Phase 5 — the date-clustering engine. Mirror of iOS
 * `StorySuggestionEngine.swift`; see `shared/algorithms/story-
 * suggestions.md` for the canonical spec. Both platforms MUST emit
 * identical output for identical inputs (the property that lets a
 * session dismissal stick).
 */

package app.quickink.mobile.features.stories

import app.quickink.mobile.data.capture.CaptureDao
import app.quickink.mobile.data.capture.CaptureEntity
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object StorySuggestionEngine {

    /** Gap above which we cut a cluster, per spec §3. */
    private const val CUT_GAP_SECONDS: Long = 18L * 3600L

    /** Async DB-backed entry. */
    suspend fun compute(
        userId: String,
        captureDao: CaptureDao,
        dismissed: Set<String> = emptySet(),
    ): StorySuggestion? {
        val captures = captureDao.activeRows(userId)
            .mapNotNull { it.toPoint() }
        return compute(captures, dismissed)
    }

    /** Pure-function entry — testable without a database. */
    fun compute(captures: List<CapturePoint>, dismissed: Set<String>): StorySuggestion? {
        val sorted = captures.sortedBy { it.timestamp }
        val clusters = greedyCluster(sorted)
        val qualified = clusters.filter { it.size >= 4 }
        if (qualified.isEmpty()) return null

        val ranked = qualified.sortedWith(
            compareByDescending<List<CapturePoint>> { score(it) }
                .thenByDescending { it.last().timestamp }
        )
        for (cluster in ranked) {
            val suggestion = buildSuggestion(cluster)
            if (suggestion.id !in dismissed) return suggestion
        }
        return null
    }

    data class CapturePoint(
        val id: String,
        val timestamp: OffsetDateTime,
        val source: String,
        val locality: String?,
    )

    // ── Internals ───────────────────────────────────────────────

    private fun greedyCluster(sorted: List<CapturePoint>): List<List<CapturePoint>> {
        val out = mutableListOf<MutableList<CapturePoint>>()
        var current = mutableListOf<CapturePoint>()
        for (cap in sorted) {
            if (current.isEmpty()) { current.add(cap); continue }
            val prev = current.last()
            val gap = ChronoUnit.SECONDS.between(prev.timestamp, cap.timestamp)
            if (gap > CUT_GAP_SECONDS && current.size >= 3) {
                out.add(current)
                current = mutableListOf(cap)
            } else {
                current.add(cap)
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun score(cluster: List<CapturePoint>): Double {
        val kinds = cluster.map { it.source }.toSet().size
        return cluster.size.toDouble() * maxOf(1, kinds).toDouble()
    }

    private fun buildSuggestion(cluster: List<CapturePoint>): StorySuggestion {
        val first = cluster.first()
        val last  = cluster.last()
        val id    = stableId(first.id, last.id)
        val dateRange = formatDateRange(first.timestamp, last.timestamp)
        val reason = buildReason(cluster, dateRange)
        return StorySuggestion(
            id            = id,
            reason        = reason,
            candidateRefs = cluster.map { it.id },
            score         = score(cluster),
        )
    }

    private fun buildReason(cluster: List<CapturePoint>, dateRange: String): String {
        val scanCount   = cluster.count { it.source == "scan" }
        val importCount = cluster.count { it.source == "import" }
        val parts = mutableListOf<String>()
        if (scanCount > 0)   parts += "$scanCount ${if (scanCount == 1) "scan" else "scans"}"
        if (importCount > 0) parts += "$importCount ${if (importCount == 1) "photo" else "photos"}"
        if (parts.isEmpty())
            parts += "${cluster.size} ${if (cluster.size == 1) "capture" else "captures"}"
        return "${parts.joinToString(" and ")}, $dateRange"
    }

    /** sha1(firstId + lastId) → first 16 hex chars. */
    private fun stableId(firstId: String, lastId: String): String {
        val md  = MessageDigest.getInstance("SHA-1")
        val raw = md.digest((firstId + lastId).toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    private val DAY_ONLY  = DateTimeFormatter.ofPattern("d",     Locale.ENGLISH)

    private fun formatDateRange(start: OffsetDateTime, end: OffsetDateTime): String {
        val sd = start.toLocalDate()
        val ed = end.toLocalDate()
        if (sd == ed) return MONTH_DAY.format(sd)
        if (sd.year == ed.year && sd.month == ed.month) {
            return "${MONTH_DAY.format(sd)}–${DAY_ONLY.format(ed)}"
        }
        return "${MONTH_DAY.format(sd)} – ${MONTH_DAY.format(ed)}"
    }

    private fun CaptureEntity.toPoint(): CapturePoint? {
        val ts = runCatching { OffsetDateTime.parse(createdAt) }.getOrNull() ?: return null
        return CapturePoint(
            id        = id,
            timestamp = ts,
            source    = source,
            locality  = locality,
        )
    }
}
