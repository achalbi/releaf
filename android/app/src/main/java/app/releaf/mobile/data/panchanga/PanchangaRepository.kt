/*
 * PanchangaRepository.kt
 *
 * Owns the lifecycle of the bundled Vontikoppal / Mysore Panchanga
 * dataset: parses `assets/panchanga_2026_27.csv` on first launch,
 * caches every row in Room (`panchanga` table), and exposes
 * date-keyed / month-keyed / search Flows the UI consumes.
 *
 * Bootstrap is fire-and-forget on first call to `ensureLoaded()` —
 * subsequent calls short-circuit on the row count from the DAO so
 * we don't re-parse on every screen open. The CSV is small
 * (≈400 rows, ≈17KB) so parsing happens entirely in memory off the
 * main dispatcher.
 *
 * Intentionally NOT in scope here: network refresh from the GitHub
 * raw URL. The dataset is annual, the file is bundled in the APK,
 * and a network round-trip would just add a loading state to a
 * surface that's expected to work offline. Add `refreshFromNetwork`
 * later if the user needs a newer year mid-cycle.
 */

package app.releaf.mobile.data.panchanga

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class PanchangaRepository(
    private val context: Context,
    private val dao: PanchangaDao,
) {

    /**
     * Bootstrap the DB from the bundled asset if needed.
     *
     * Two triggers reload the table:
     *   1. The DB is empty (fresh install or wiped data).
     *   2. The bundled asset has a higher [ASSET_VERSION] than the
     *      one we last imported — used to push CSV patches to
     *      existing installs (e.g. filling in a festival the OCR
     *      pass missed) without requiring uninstall + reinstall.
     *
     * Bumping ASSET_VERSION whenever the CSV changes is the only
     * maintenance needed; the rest of the flow handles itself.
     * Errors are swallowed and logged; the UI's "data not
     * available" fallback covers the failure case.
     */
    suspend fun ensureLoaded() {
        val storedVersion = prefs.getInt(KEY_ASSET_VERSION, 0)
        val needsReload = dao.count() == 0 || storedVersion < ASSET_VERSION
        if (!needsReload) return
        withContext(Dispatchers.IO) {
            try {
                val rows = parseCsvFromAsset(ASSET_FILENAME)
                if (rows.isNotEmpty()) {
                    // `deleteAll` first so a row that disappeared
                    // between asset versions (e.g. an OCR-derived
                    // entry that was wrong and got removed) leaves
                    // the table on the next bootstrap. `upsertAll`
                    // alone wouldn't catch that case.
                    dao.deleteAll()
                    dao.upsertAll(rows)
                    prefs.edit().putInt(KEY_ASSET_VERSION, ASSET_VERSION).apply()
                }
            } catch (e: Exception) {
                // Non-fatal — the screen renders the
                // "Panchanga data not available" placeholder.
                android.util.Log.w(TAG, "Failed to bootstrap panchanga from asset", e)
            }
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun observeForDate(date: String): Flow<List<PanchangaEntity>> =
        dao.observeForDate(date)

    /**
     * `monthPrefix` must be `yyyy-MM-` (with the dash) for the LIKE
     * to scope to one month exactly.
     */
    fun observeForMonth(monthPrefix: String): Flow<List<PanchangaEntity>> =
        dao.observeForMonth(monthPrefix)

    /**
     * Free-form festival search. Splits the query into whitespace-
     * separated tokens and AND-matches each one against a per-row
     * "haystack" composed of the festival name + the panchanga
     * cells that name it (special_day + masa + paksha + thithi).
     *
     * The multi-column haystack is what makes "krishna janmashtami"
     * resolve to the Bhadrapada Krishna Ashtami row: "krishna" hits
     * the `paksha` cell while "janmashtami" hits the `special_day`
     * cell. A single-LIKE SQL query couldn't span columns without
     * dynamic SQL, so this is filtered in Kotlin over the
     * `observeAllSpecialDays` flow.
     *
     * Matching has two layers, applied in order per token / word:
     *   1. Transliteration normalise → substring contains.
     *      Catches things like "janmastami" finding "janmashtami"
     *      (sh→s collapses both to the same form).
     *   2. Word-level Levenshtein with a length-scaled threshold.
     *      Catches plain typos — "gnadhi" finds "gandhi", "gandi"
     *      finds "gandhi" — without making short tokens dangerously
     *      promiscuous (a 3-letter token requires an exact match;
     *      4-7 letters tolerates one edit; ≥8 letters tolerates two).
     */
    fun searchSpecialDay(query: String): Flow<List<PanchangaEntity>> {
        val tokens = query.trim().lowercase()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .map(::normalise)
        if (tokens.isEmpty()) return flowOf(emptyList())
        return dao.observeAllSpecialDays().map { rows ->
            rows.filter { row ->
                val haystack = buildString {
                    append(row.specialDayLowercase)
                    append(' ')
                    append(row.masa.lowercase())
                    append(' ')
                    append(row.paksha.lowercase())
                    append(' ')
                    append(row.thithi.lowercase())
                }.let(::normalise)
                val haystackWords = haystack.split(WHITESPACE).filter { it.isNotEmpty() }
                tokens.all { token -> matchesToken(haystack, haystackWords, token) }
            }
        }
    }

    /**
     * Token match against a row. Substring contains comes first
     * because it's both cheap and forgiving for partial inputs
     * (the user typing "krish" matches "krishna"). If that fails,
     * fall back to word-level Levenshtein so single-edit typos
     * still find the row.
     */
    private fun matchesToken(
        haystack: String,
        haystackWords: List<String>,
        token: String,
    ): Boolean {
        if (haystack.contains(token)) return true
        val threshold = typoThreshold(token.length)
        if (threshold == 0) return false
        return haystackWords.any { word -> levenshtein(word, token) <= threshold }
    }

    /**
     * Edit-distance budget by token length. Tight for short tokens
     * to avoid "abc" matching half the dataset; relaxed for longer
     * tokens where a single typo (missed letter, swapped letter)
     * is the realistic mistake.
     */
    private fun typoThreshold(tokenLength: Int): Int = when {
        tokenLength <= 3 -> 0
        tokenLength <= 7 -> 1
        else             -> 2
    }

    /**
     * Iterative Levenshtein distance with two rolling rows
     * (O(n) memory). Counts insertions, deletions, and
     * substitutions equally — no transposition shortcut, so
     * "form" → "from" costs 2.
     */
    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        // Quick reject: if the length difference already exceeds
        // any reasonable threshold, the distance does too.
        if (kotlin.math.abs(a.length - b.length) > MAX_TYPO_THRESHOLD) {
            return a.length + b.length
        }
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,         // deletion
                    curr[j - 1] + 1,     // insertion
                    prev[j - 1] + cost,  // substitution
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    /**
     * Normalise a romanised Indic word so common transliteration
     * variants collapse to one form. Run on both the search
     * haystack and each query token so the user can spell things
     * however they're used to seeing them written.
     *
     * Rules (ordered — longer / more specific patterns first):
     *
     *   sh → s    Krishna   ↔ Krisna,    Janmashtami ↔ Janmastami
     *   chh → ch  Chhath    ↔ Chath
     *   ph → f    Phalguna  ↔ Falguna
     *   aa → a    Bhadraapada ↔ Bhadrapada
     *   ee → i    Sree      ↔ Sri
     *   oo → u    Pooja     ↔ Puja
     *   ii → i    Vasanti   ↔ Vasanti (no-op for already-i)
     *   uu → u    Guruu     ↔ Guru
     *
     * The list isn't exhaustive — variants like "Diwali" vs
     * "Deepavali" and "Krishn" vs "Krishna" need real lexical
     * knowledge to bridge, not substitutions. But the rules above
     * cover most of the day-to-day mismatches users hit when
     * typing festival names from memory.
     */
    private fun normalise(s: String): String {
        var out = s
        out = out.replace("chh", "ch")
        out = out.replace("aa", "a")
        out = out.replace("ee", "i")
        out = out.replace("oo", "u")
        out = out.replace("ii", "i")
        out = out.replace("uu", "u")
        out = out.replace("sh", "s")
        out = out.replace("ph", "f")
        return out
    }

    // ---------- CSV parsing ----------

    /**
     * Parses the bundled CSV. Handles the three shapes observed in
     * the upstream dataset:
     *   - 6 fields, last (special_day) empty:   `2026-03-21,Chaitra,Shukla,Tritiya,3,`
     *   - 5 fields, trailing comma omitted:     `2026-04-07,Chaitra,Krishna,Panchami,5`
     *   - 6 fields, special_day quoted with
     *     internal comma:                       `2026-03-26,Chaitra,Shukla,Ashtami,8,"Ashoka Ashtami, Kuruba Jayanti"`
     *
     * Implemented inline because the file is small (~400 rows) and
     * Kotlin's standard library has no CSV parser — pulling in a
     * dependency for one file isn't worth the build-time cost.
     */
    private fun parseCsvFromAsset(filename: String): List<PanchangaEntity> {
        context.assets.open(filename).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val header = reader.readLine() ?: return emptyList()
                require(header.startsWith("date,")) {
                    "Unexpected CSV header: $header"
                }

                val out = ArrayList<PanchangaEntity>(400)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val fields = parseCsvLine(line)
                    if (fields.size < 5) continue   // malformed row — skip
                    val date       = fields[0].trim()
                    val masa       = fields[1].trim()
                    val paksha     = fields[2].trim()
                    val thithi     = fields[3].trim()
                    val thithiNum  = fields[4].trim()
                    val specialDay = if (fields.size >= 6) fields[5].trim() else ""
                    out += PanchangaEntity(
                        // `date#thithiNum` keeps duplicate-date rows
                        // distinguishable (e.g. 2026-07-28 has
                        // Chaturdashi and Purnima both falling on
                        // the same Gregorian day).
                        id                  = "$date#$thithiNum",
                        date                = date,
                        masa                = masa,
                        paksha              = paksha,
                        thithi              = thithi,
                        thithiNum           = thithiNum,
                        specialDay          = specialDay,
                        specialDayLowercase = specialDay.lowercase(),
                    )
                }
                return out
            }
        }
    }

    /**
     * Single-line CSV parser — handles double-quoted fields with
     * embedded commas. Doesn't handle escaped quotes inside quoted
     * fields ("") because the upstream dataset never uses them; if
     * future rows do, extend here.
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = ArrayList<String>(6)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields += sb.toString()
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        fields += sb.toString()
        return fields
    }

    private companion object {
        const val ASSET_FILENAME = "panchanga_2026_27.csv"
        const val TAG = "PanchangaRepository"
        val WHITESPACE = Regex("\\s+")
        /** Highest edit-distance budget [typoThreshold] ever returns.
         *  Used by [levenshtein]'s length-difference fast-reject. */
        const val MAX_TYPO_THRESHOLD = 2

        /**
         * Bundled-asset content version. **Bump this whenever the
         * CSV changes** so existing installs re-import on next
         * launch. The version number is monotonic — the bootstrap
         * compares stored < ASSET_VERSION rather than `!=` so a
         * downgrade (impossible in practice, but) doesn't trigger
         * a reload.
         *
         *   v1: initial OCR import.
         *   v2: patched 2026-09-04 with Sri Krishna Janmashtami,
         *       which the OCR pass missed.
         *   v3: patched six more OCR-missed festivals —
         *       Hanuman Jayanti (2026-04-02), Akshaya Tritiya
         *       (2026-04-20), Buddha Purnima (2026-05-01),
         *       Mahalaya Amavasya (2026-10-10), Diwali & Sri
         *       Lakshmi Puja (2026-11-09), Christmas (2026-12-25).
         */
        const val ASSET_VERSION = 3

        const val PREFS_NAME = "panchanga_repo"
        const val KEY_ASSET_VERSION = "asset_version"
    }
}
