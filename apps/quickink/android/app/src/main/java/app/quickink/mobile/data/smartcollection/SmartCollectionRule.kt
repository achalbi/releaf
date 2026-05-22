/*
 * SmartCollectionRule.kt
 *
 * v1 grammar for `smart_collections.rule_json`. AND-of-clauses
 * shape, no OR, no nesting — per brief §3. Six clause types ship
 * in v1; three (handwriting / signature / has-OCR) are placeholders
 * for the Phase E OCR-derived signals.
 *
 * Wire format is JSON, sealed `type` discriminator. The Kotlin
 * model round-trips both ways via kotlinx.serialization with a
 * polymorphic class discriminator. The same JSON is stored in the
 * DB column and synced to Drive as the smart-collection payload's
 * `rule_json` field.
 */

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.quickink.mobile.data.smartcollection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/** One AND clause inside a smart-collection rule.
 *
 *  The `type` JSON field is the discriminator (e.g. "folder_is",
 *  "tag_is", "date_range"). The polymorphic serializer matches it
 *  against the @SerialName on each subclass. */
@JsonClassDiscriminator("type")
@Serializable
sealed class RuleClause {

    @Serializable
    @SerialName("folder_is")
    data class FolderIs(
        @SerialName("folder_id") val folderId: String,
    ) : RuleClause()

    @Serializable
    @SerialName("tag_is")
    data class TagIs(
        @SerialName("tag_id") val tagId: String,
    ) : RuleClause()

    @Serializable
    @SerialName("tag_is_not")
    data class TagIsNot(
        @SerialName("tag_id") val tagId: String,
    ) : RuleClause()

    @Serializable
    @SerialName("date_range")
    data class DateRange(
        @SerialName("field")  val field:  String, // "created_at" | "last_opened_at"
        @SerialName("preset") val preset: String, // "today" | "yesterday" | "this_week" | "this_month" | "last_30_days" | "this_quarter"
    ) : RuleClause()

    @Serializable
    @SerialName("source_is")
    data class SourceIs(
        @SerialName("value") val value: String, // "scan" | "import" | "photo" | "video" | "share-extension"
    ) : RuleClause()

    /**
     * OCR-derived signals. Out of scope for the C.3 ship (no flag
     * columns exist yet); the clause parses but its evaluator
     * matches nothing. Lights up in Phase E.
     */
    @Serializable
    @SerialName("has_handwriting")
    data class HasHandwriting(
        @SerialName("value") val value: Boolean,
    ) : RuleClause()

    @Serializable
    @SerialName("has_signature")
    data class HasSignature(
        @SerialName("value") val value: Boolean,
    ) : RuleClause()

    @Serializable
    @SerialName("has_ocr_text")
    data class HasOcrText(
        @SerialName("value") val value: Boolean,
    ) : RuleClause()
}

object SmartCollectionRule {

    /** Canonical JSON config — matches the manifest serializer. */
    private val json: Json = Json {
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    /** Encode a list of clauses to the JSON string stored on disk. */
    fun encode(clauses: List<RuleClause>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(RuleClause.serializer()), clauses)

    /**
     * Decode a stored rule_json blob. Returns an empty list on any
     * parse failure rather than throwing — a malformed rule turns
     * into "matches nothing" instead of crashing the screen.
     */
    fun decode(text: String?): List<RuleClause> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(RuleClause.serializer()),
                text,
            )
        }.getOrElse { emptyList() }
    }
}

/**
 * Flat editor-input projection of a rule-clause list. Each field
 * is one editable slot in the smart-collection editor; converting
 * to / from a [RuleClause] list lives in [toClauses] /
 * [fromClauses] so the UI doesn't have to reason about the JSON
 * grammar directly.
 *
 * The DateRange clause's `field` is collapsed to `"created_at"`
 * here — the editor doesn't yet expose the `last_opened_at`
 * variant, and a UI for picking the field would need a clearer
 * use case than "the rule grammar supports it".
 *
 * `null` / empty values mean "no clause of that kind". Round-trip
 * is lossless for the slots we surface; clauses the editor
 * doesn't know about (e.g. a date range over `last_opened_at`)
 * are dropped on round-trip — same posture as the JSON decoder's
 * `ignoreUnknownKeys = true`.
 */
data class SmartCollectionRuleInput(
    val folderId: String? = null,
    val datePreset: String? = null,
    val tagIncludeIds: List<String> = emptyList(),
    val tagExcludeIds: List<String> = emptyList(),
    val sourceValue: String? = null,
    val hasHandwriting: Boolean? = null,
    val hasSignature: Boolean? = null,
    val hasOcrText: Boolean? = null,
) {
    /** True when no clause is selected — the editor's Save guard. */
    val isEmpty: Boolean
        get() = folderId == null &&
            datePreset == null &&
            tagIncludeIds.isEmpty() &&
            tagExcludeIds.isEmpty() &&
            sourceValue == null &&
            hasHandwriting == null &&
            hasSignature == null &&
            hasOcrText == null

    /** Compile the input back into the canonical AND-of-clauses list. */
    fun toClauses(): List<RuleClause> = buildList {
        folderId?.let { add(RuleClause.FolderIs(it)) }
        datePreset?.let { add(RuleClause.DateRange(field = "created_at", preset = it)) }
        for (id in tagIncludeIds) add(RuleClause.TagIs(id))
        for (id in tagExcludeIds) add(RuleClause.TagIsNot(id))
        sourceValue?.let { add(RuleClause.SourceIs(it)) }
        hasHandwriting?.let { add(RuleClause.HasHandwriting(it)) }
        hasSignature?.let { add(RuleClause.HasSignature(it)) }
        hasOcrText?.let { add(RuleClause.HasOcrText(it)) }
    }

    companion object {
        /**
         * Build a flat editor-input from a decoded clause list. When
         * a clause type appears more than once the editor only
         * tracks the first (folder / date / source / OCR flags) or
         * unions ids (tag include / exclude).
         */
        fun fromClauses(clauses: List<RuleClause>): SmartCollectionRuleInput {
            var folderId: String? = null
            var datePreset: String? = null
            val includes = mutableListOf<String>()
            val excludes = mutableListOf<String>()
            var source: String? = null
            var hand: Boolean? = null
            var sig: Boolean? = null
            var ocr: Boolean? = null
            for (c in clauses) {
                when (c) {
                    is RuleClause.FolderIs       -> if (folderId == null) folderId = c.folderId
                    is RuleClause.DateRange      -> if (datePreset == null && c.field == "created_at") datePreset = c.preset
                    is RuleClause.TagIs          -> if (c.tagId !in includes) includes += c.tagId
                    is RuleClause.TagIsNot       -> if (c.tagId !in excludes) excludes += c.tagId
                    is RuleClause.SourceIs       -> if (source == null) source = c.value
                    is RuleClause.HasHandwriting -> if (hand   == null) hand   = c.value
                    is RuleClause.HasSignature   -> if (sig    == null) sig    = c.value
                    is RuleClause.HasOcrText     -> if (ocr    == null) ocr    = c.value
                }
            }
            return SmartCollectionRuleInput(
                folderId       = folderId,
                datePreset     = datePreset,
                tagIncludeIds  = includes,
                tagExcludeIds  = excludes,
                sourceValue    = source,
                hasHandwriting = hand,
                hasSignature   = sig,
                hasOcrText     = ocr,
            )
        }
    }
}
