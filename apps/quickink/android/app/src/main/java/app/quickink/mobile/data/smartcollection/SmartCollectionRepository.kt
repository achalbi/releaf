/*
 * SmartCollectionRepository.kt
 *
 * v1 rule evaluator + seed for the Workspace Smart Collections
 * surface (Phase C.3). Matches captures against a [RuleClause]
 * list in-memory: at v1 corpus sizes (low thousands) the join-free
 * Kotlin filter is sub-millisecond and avoids a SQL-builder
 * surface area we don't need yet. Phase D can swap for a typed
 * @RawQuery if the corpus outgrows it.
 *
 * Seeded rows are flagged `is_seeded = 1` so later releases can
 * silently rev the rule_json without overwriting user-edited
 * collections (per brief §2 entity notes).
 */

package app.quickink.mobile.data.smartcollection

import app.quickink.mobile.data.capture.CaptureDao
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capturetag.CaptureTagDao
import app.quickink.mobile.data.tag.TagDao
import app.quickink.mobile.data.tag.TagRepository
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

class SmartCollectionRepository(
    private val smartCollectionDao: SmartCollectionDao,
    private val captureDao: CaptureDao? = null,
    private val captureTagDao: CaptureTagDao? = null,
    private val tagDao: TagDao? = null,
) {

    fun observeActive(userId: String): Flow<List<SmartCollectionEntity>> =
        smartCollectionDao.observeActive(userId)

    suspend fun findById(id: String): SmartCollectionEntity? =
        smartCollectionDao.findById(id)

    /**
     * Stream the captures matching [collection]'s rule. Composes
     * the captures-by-user Flow with the per-capture tag-id Flow,
     * filtering in-memory. Re-emits on any change to either side.
     * Empty list when DAOs aren't wired or the rule is malformed.
     */
    fun observeMatchingCaptures(
        userId: String,
        collection: SmartCollectionEntity,
    ): Flow<List<CaptureEntity>> {
        val capDao = captureDao ?: return flowOf(emptyList())
        val joinDao = captureTagDao ?: return flowOf(emptyList())
        val rule = SmartCollectionRule.decode(collection.ruleJson)
        if (rule.isEmpty()) return flowOf(emptyList())

        return combine(
            capDao.observeActive(userId),
            joinDao.observeTagCounts(userId),
        ) { captures, _ ->
            captures.filter { matchesAllClauses(it, rule, userId, joinDao) }
        }
    }

    /**
     * Synchronous best-effort match for static contexts (e.g.
     * worker code that already has the captures in hand). Reads
     * tag attachments per row from the DAO; not Flow-based.
     */
    private fun matchesAllClauses(
        capture: CaptureEntity,
        clauses: List<RuleClause>,
        userId: String,
        joinDao: CaptureTagDao,
    ): Boolean {
        for (clause in clauses) {
            if (!matchesClause(capture, clause, userId, joinDao)) return false
        }
        return true
    }

    private fun matchesClause(
        capture: CaptureEntity,
        clause: RuleClause,
        userId: String,
        joinDao: CaptureTagDao,
    ): Boolean = when (clause) {
        is RuleClause.FolderIs ->
            capture.folderId == clause.folderId

        is RuleClause.TagIs ->
            // suspend lookup costs a coroutine context — read
            // via runBlocking is OK here because Flow combine
            // dispatches on Default. Bounded by the join row
            // count per capture (typically <12 per the soft cap).
            kotlinx.coroutines.runBlocking {
                joinDao.listTagIdsForCapture(capture.id).contains(clause.tagId)
            }

        is RuleClause.TagIsNot ->
            kotlinx.coroutines.runBlocking {
                !joinDao.listTagIdsForCapture(capture.id).contains(clause.tagId)
            }

        is RuleClause.DateRange -> {
            val timestamp = when (clause.field) {
                "created_at"     -> capture.createdAt
                "last_opened_at" -> capture.lastOpenedAt ?: return false
                else             -> return false
            }
            inDatePreset(timestamp, clause.preset)
        }

        is RuleClause.SourceIs ->
            capture.source == clause.value

        // OCR-derived signals — placeholder until Phase E. Always
        // false so the rule matches nothing rather than everything.
        is RuleClause.HasHandwriting,
        is RuleClause.HasSignature,
        is RuleClause.HasOcrText -> false
    }

    /**
     * Resolves a preset like "this_month" against the device's
     * local time zone. Returns true when the ISO timestamp falls
     * inside the bucket.
     */
    private fun inDatePreset(isoTimestamp: String, preset: String): Boolean {
        val zone = ZoneId.systemDefault()
        val ts = runCatching { java.time.Instant.parse(isoTimestamp) }.getOrNull() ?: return false
        val tsDate = ts.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)

        return when (preset) {
            "this_week" -> {
                val weekFields = java.time.temporal.WeekFields.ISO
                tsDate.get(weekFields.weekOfWeekBasedYear()) == today.get(weekFields.weekOfWeekBasedYear()) &&
                    tsDate.year == today.year
            }
            "this_month" ->
                tsDate.year == today.year && tsDate.month == today.month
            "last_30_days" ->
                !tsDate.isBefore(today.minusDays(30))
            "this_quarter" ->
                tsDate.year == today.year &&
                    tsDate.get(IsoFields.QUARTER_OF_YEAR) == today.get(IsoFields.QUARTER_OF_YEAR)
            else -> false
        }
    }

    // ────────────────────────────────────────────────────────────
    // Seeding (Phase A.3-equivalent for smart collections)
    // ────────────────────────────────────────────────────────────

    /**
     * Idempotent seed of the Workspace v1 default smart
     * collections — currently just "Needs review", which depends
     * on the `#needs-review` tag seeded in [TagRepository.DEFAULT_SEED].
     *
     * "Invoices this month" and "Contains signatures" from the
     * design brief are deferred:
     *   - Invoices this month: requires an "Invoices" folder seed.
     *     Folder-name choices are user territory; we don't seed.
     *   - Contains signatures: requires has_signature, which is
     *     Phase E OCR-derived metadata.
     */
    suspend fun seedDefaultsIfNeeded(userId: String) {
        val tags = tagDao ?: return
        val existing = smartCollectionDao.listSeeded(userId)
        if (existing.any { it.name == SEED_NEEDS_REVIEW }) return

        val needsReviewTag = tags.findByName(userId, "needs-review") ?: return
        val now = IsoClock.nowIso()
        smartCollectionDao.insert(
            SmartCollectionEntity(
                id        = Uuidv7.generate(),
                userId    = userId,
                name      = SEED_NEEDS_REVIEW,
                icon      = "ti-eye",
                color     = "#E8AE17",
                ruleJson  = SmartCollectionRule.encode(
                    listOf(RuleClause.TagIs(needsReviewTag.id)),
                ),
                position  = 0,
                isSeeded  = true,
                createdAt = now,
                updatedAt = now,
                dirty     = true,
                deletedAt = null,
            ),
        )
    }

    companion object {
        const val SEED_NEEDS_REVIEW = "Needs review"
    }
}
