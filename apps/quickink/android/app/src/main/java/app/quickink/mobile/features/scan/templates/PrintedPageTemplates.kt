/*
 * PrintedPageTemplates.kt
 *
 * Registry of page templates for the printed QuickInk notebook. A
 * scanned page that identifies as one of these is filed into the
 * template's folder, gets its tags, and has its title / date read
 * from fixed zones on the page (see `TemplateDetection.kt`).
 *
 * The canonical source is
 * `shared/design-system/seeds/page_templates.json`. Like the
 * workspace seed, this file is a hard-coded mirror (no launch-time
 * asset read); `PrintedPageTemplatesTest` parses the JSON and fails
 * when the two drift. [PrintedPageTemplateRegistry.fromJson] is the
 * same parser, so a future downloadable / user-defined template set
 * plugs in without code changes.
 *
 * Mirror of iOS `PrintedPageTemplates.swift`.
 */

package app.quickink.mobile.features.scan.templates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Normalised page rectangle: 0..1, origin top-left. */
data class PageZone(val x: Double, val y: Double, val width: Double, val height: Double) {
    fun contains(px: Double, py: Double): Boolean =
        px >= x && px <= x + width && py >= y && py <= y + height

    companion object {
        /** Default printed-header band: the top 8% of the page. */
        val TOP_BAND = PageZone(0.0, 0.0, 1.0, 0.08)
    }
}

data class PrintedPageTemplate(
    /** Stable id; what the printed mark encodes (`quickink://t/<id>`). */
    val id: String,
    val name: String,
    /** Destination folder — a stable id from `workspace_seed.json`. */
    val folderId: String?,
    /** Tag names applied to the capture; the first is the primary tag. */
    val tags: List<String>,
    /** Where the handwritten title sits. Null → no title zone. */
    val titleZone: PageZone?,
    /** Where the handwritten date sits. Null → no date zone. */
    val dateZone: PageZone?,
    /**
     * Whether the printed page has a checkbox column. Informational
     * for now: checkbox → to-do extraction runs on every page
     * whether or not it came from a template.
     */
    val hasCheckboxColumn: Boolean,
    /** Band the printed header words are searched in (fallback detector). */
    val headerZone: PageZone = PageZone.TOP_BAND,
    /**
     * Printed header phrases. A page matches when every word of one
     * phrase appears in the OCR text inside [headerZone]. Phrases
     * should include a word handwriting is unlikely to produce
     * ("quickink") so a handwritten "Meeting notes" doesn't trip it.
     */
    val headerPhrases: List<String> = emptyList(),
)

class PrintedPageTemplateRegistry(val templates: List<PrintedPageTemplate>) {

    private val byId = templates.associateBy { it.id.lowercase() }

    fun byId(id: String): PrintedPageTemplate? = byId[id.lowercase()]

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The built-in set — mirror of `page_templates.json`. */
        val default: PrintedPageTemplateRegistry by lazy { PrintedPageTemplateRegistry(PrintedPageTemplates.seed) }

        /**
         * Parse a `page_templates.json` document. Templates missing an
         * `id` or `name` are skipped; unknown keys are ignored.
         */
        fun fromJson(raw: String): PrintedPageTemplateRegistry {
            val root = json.parseToJsonElement(raw) as? JsonObject
                ?: return PrintedPageTemplateRegistry(emptyList())
            val list = (root["templates"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.string("id") ?: return@mapNotNull null
                val name = o.string("name") ?: return@mapNotNull null
                PrintedPageTemplate(
                    id = id,
                    name = name,
                    folderId = o.string("folder_id"),
                    tags = o.strings("tags"),
                    titleZone = o.zone("title_zone"),
                    dateZone = o.zone("date_zone"),
                    hasCheckboxColumn = (o["has_checkbox_column"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    headerZone = o.zone("header_zone") ?: PageZone.TOP_BAND,
                    headerPhrases = o.strings("header_phrases"),
                )
            }
            return PrintedPageTemplateRegistry(list)
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

        private fun JsonObject.zone(key: String): PageZone? {
            val z = this[key] as? JsonObject ?: return null
            fun d(k: String) = (z[k] as? JsonPrimitive)?.doubleOrNull
            return PageZone(d("x") ?: return null, d("y") ?: return null, d("width") ?: return null, d("height") ?: return null)
        }
    }
}

/** Hard-coded mirror of `shared/design-system/seeds/page_templates.json`. */
object PrintedPageTemplates {
    private val TITLE = PageZone(0.08, 0.08, 0.60, 0.08)
    private val DATE = PageZone(0.70, 0.08, 0.24, 0.08)

    val seed: List<PrintedPageTemplate> = listOf(
        PrintedPageTemplate(
            id = "meeting-notes", name = "Meeting notes", folderId = "projects", tags = listOf("meetings"),
            titleZone = TITLE, dateZone = DATE, hasCheckboxColumn = true,
            headerPhrases = listOf("quickink meeting notes"),
        ),
        PrintedPageTemplate(
            id = "journal", name = "Journal", folderId = "lifestyle", tags = listOf("journal"),
            titleZone = TITLE, dateZone = DATE, hasCheckboxColumn = false,
            headerPhrases = listOf("quickink journal"),
        ),
        PrintedPageTemplate(
            id = "todo", name = "To-do", folderId = "inbox", tags = listOf("todo"),
            titleZone = TITLE, dateZone = DATE, hasCheckboxColumn = true,
            headerPhrases = listOf("quickink todo", "quickink to do"),
        ),
        PrintedPageTemplate(
            id = "ideas", name = "Ideas", folderId = "ideas", tags = listOf("ideas"),
            titleZone = TITLE, dateZone = DATE, hasCheckboxColumn = false,
            headerPhrases = listOf("quickink ideas"),
        ),
    )
}
