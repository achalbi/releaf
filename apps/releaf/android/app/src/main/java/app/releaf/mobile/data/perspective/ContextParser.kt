/*
 * ContextParser.kt
 *
 * Shared regex + helpers for reading the first `@tag` token out of a
 * task title. Lives next to the perspective model because the tag
 * IS the perspective's match key — the task schema intentionally
 * doesn't carry a foreign-key to [PerspectiveEntity] so any layer
 * that needs to ask "which perspective owns this task?" goes
 * through here.
 *
 * Shape: `@` followed by a letter, then letters, digits, underscores
 * or hyphens. Names are lower-cased on extract so every call site
 * compares apples-to-apples.
 */

package app.releaf.mobile.data.perspective

/** Matches `@name` tokens. See file header for the grammar. */
val CONTEXT_REGEX: Regex = Regex("@([a-zA-Z][\\w-]*)")

/** First `@tag` in the title, lower-cased, or null if none. */
fun extractContext(title: String): String? =
    CONTEXT_REGEX.find(title)?.groupValues?.get(1)?.lowercase()

/** Title with every `@tag` removed and whitespace collapsed. */
fun stripContext(title: String): String =
    title.replace(CONTEXT_REGEX, "").replace(Regex("\\s+"), " ").trim()
