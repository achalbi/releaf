/*
 * NotepadCategory.kt
 *
 * Pure-data helpers for the category column on `notepad_entries`.
 * There's no separate `categories` table — the column itself is the
 * source of truth, and this file provides:
 *
 *   - The seed list of predefined categories (Home / Work / Personal
 *     / Health / Travel / Ideas) the editor's picker shows up front.
 *   - Custom-category discovery: any non-predefined string the user
 *     has typed on at least one active entry becomes a custom chip
 *     automatically — `deriveCustomCategories(entries)` returns that
 *     set, alphabetised, ready for the picker to append after the
 *     predefined row.
 *
 * Comparisons use `equals(...ignoreCase = true)` everywhere so
 * "home" and "Home" don't fork into two chips. The display form is
 * always the canonical-cased version (predefined: matched against
 * `Predefined`; custom: trimmed as the user typed it on the most
 * recent entry).
 */

package app.releaf.mobile.data.notepad

object NotepadCategory {

    /**
     * Predefined categories shown in the picker before any custom
     * chips. Order is the picker's display order; don't sort
     * alphabetically here.
     */
    val Predefined: List<String> = listOf(
        "Home",
        "Work",
        "Personal",
        "Health",
        "Travel",
        "Ideas",
    )

    /**
     * True when [name] matches one of the predefined categories,
     * case-insensitive. Used by the picker to colour predefined
     * chips differently from custom chips.
     */
    fun isPredefined(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return Predefined.any { it.equals(trimmed, ignoreCase = true) }
    }

    /**
     * Canonicalise a stored category value to its display form. For
     * predefined categories this returns the canonical-cased entry
     * from [Predefined] ("home" → "Home"); for custom strings it
     * returns the trimmed input as-is so the user's casing wins.
     * Null / blank returns null (uncategorised).
     */
    fun displayName(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return Predefined.firstOrNull { it.equals(trimmed, ignoreCase = true) }
            ?: trimmed
    }

    /**
     * Walk [entries] and return every distinct *custom* category
     * (i.e. anything not in [Predefined]) that's currently in use,
     * canonicalised by lower-case key, sorted alphabetically by
     * display form. The picker appends this list after the
     * predefined row so the user sees their own categories without
     * any explicit "manage categories" step.
     *
     * Soft-deleted entries are filtered out by the caller (this
     * helper is dumb on purpose — it just dedupes whatever it's
     * handed).
     */
    fun deriveCustomCategories(entries: List<NotepadEntry>): List<String> {
        if (entries.isEmpty()) return emptyList()
        val seen = LinkedHashMap<String, String>()
        for (entry in entries) {
            val raw = entry.category?.trim().orEmpty()
            if (raw.isEmpty()) continue
            if (Predefined.any { it.equals(raw, ignoreCase = true) }) continue
            val key = raw.lowercase()
            // First occurrence wins for the display casing — entries
            // are usually delivered newest-first, so the most recent
            // typing of a custom category sets its display form.
            seen.putIfAbsent(key, raw)
        }
        return seen.values.sortedBy { it.lowercase() }
    }

    /**
     * Resolve the user's preferred display order against the live
     * predefined + custom sets.
     *
     * Result: every available name listed exactly once. Names the
     * user has explicitly ordered come first, in [userOrder]. Any
     * predefined still missing is appended next in its declared
     * order (so a fresh install with no preference falls through to
     * the natural Home → Work → … ordering). Any custom still
     * missing is appended last alphabetically. Names in [userOrder]
     * that no longer exist (deleted custom, etc.) are silently
     * dropped — they don't need to clutter the chip row.
     *
     * Comparisons are case-insensitive on the lower-cased trimmed
     * key so "garden" and "Garden" don't fork.
     */
    fun applyOrder(
        userOrder: List<String>,
        customs: List<String>,
    ): List<String> {
        // Build a key → display map of every name currently available.
        // LinkedHashMap so the fallback paths preserve their insertion
        // order (predefined declared, customs alphabetised).
        val available = LinkedHashMap<String, String>()
        for (name in Predefined) available[name.lowercase()] = name
        for (name in customs.sortedBy { it.lowercase() }) {
            val key = name.trim().lowercase()
            if (key.isEmpty()) continue
            // First entry wins (predefined). If a custom collides
            // case-insensitively with a predefined name, the
            // predefined display wins for casing consistency.
            if (!available.containsKey(key)) available[key] = name.trim()
        }

        val result  = mutableListOf<String>()
        val claimed = mutableSetOf<String>()

        // Pass 1: user's explicit order, name by name.
        for (raw in userOrder) {
            val key = raw.trim().lowercase()
            if (key.isEmpty() || claimed.contains(key)) continue
            val display = available[key] ?: continue
            result.add(display)
            claimed.add(key)
        }
        // Pass 2: every still-available name, in the LinkedHashMap's
        // natural order (predefined declared → customs alphabetical).
        for ((key, display) in available) {
            if (claimed.contains(key)) continue
            result.add(display)
            claimed.add(key)
        }
        return result
    }
}
