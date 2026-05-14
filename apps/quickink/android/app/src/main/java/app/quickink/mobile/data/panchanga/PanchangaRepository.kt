/*
 * PanchangaRepository.kt
 *
 * Owns the lifecycle of the bundled Vontikoppal / Mysore Panchanga
 * dataset: parses `assets/panchanga_2026_27.csv` on first launch,
 * caches every row in Room (`panchanga` table), and exposes
 * date-keyed / month-keyed / search Flows the UI consumes.
 *
 * Bootstrap is fire-and-forget on first call to `ensureLoaded()` —
 * subsequent calls short-circuit on the row count from the DAO so we
 * don't re-parse on every screen open. The CSV is small (≈400 rows,
 * ≈17KB) so parsing happens entirely in memory off the main
 * dispatcher.
 *
 * Port of Releaf Android's `PanchangaRepository` — package rename +
 * SharedPreferences key namespace change (`panchanga_repo` →
 * `quickink_panchanga_repo`) only.
 */

package app.quickink.mobile.data.panchanga

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
     *      existing installs (e.g. filling in a festival the OCR pass
     *      missed) without requiring uninstall + reinstall.
     *
     * Bumping ASSET_VERSION whenever the CSV changes is the only
     * maintenance needed; the rest of the flow handles itself.
     * Errors are swallowed and logged; the UI's "data not available"
     * fallback covers the failure case.
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
     * Matching has two layers per token:
     *   1. Transliteration normalise → substring contains. Catches
     *      "janmastami" finding "janmashtami" (sh→s collapses both).
     *   2. Word-level Levenshtein with a length-scaled threshold.
     *      ≤3 letters: exact match required; 4-7 letters: 1 edit;
     *      ≥8 letters: 2 edits.
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

    private fun typoThreshold(tokenLength: Int): Int = when {
        tokenLength <= 3 -> 0
        tokenLength <= 7 -> 1
        else             -> 2
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
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
                    if (fields.size < 5) continue
                    val date       = fields[0].trim()
                    val masa       = fields[1].trim()
                    val paksha     = fields[2].trim()
                    val thithi     = fields[3].trim()
                    val thithiNum  = fields[4].trim()
                    val specialDay = if (fields.size >= 6) fields[5].trim() else ""
                    out += PanchangaEntity(
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
        const val MAX_TYPO_THRESHOLD = 2

        /**
         * Bundled-asset content version. **Bump this whenever the
         * CSV changes** so existing installs re-import on next launch.
         * Mirror of Releaf's ASSET_VERSION; keep in sync when patches
         * are pushed to either app's copy of the CSV.
         */
        const val ASSET_VERSION = 3

        // Distinct prefs file from Releaf's `panchanga_repo` so two
        // apps on the same device don't collide. Both are app-
        // sandboxed anyway, but the explicit namespace keeps intent
        // clear.
        const val PREFS_NAME = "quickink_panchanga_repo"
        const val KEY_ASSET_VERSION = "asset_version"
    }
}
