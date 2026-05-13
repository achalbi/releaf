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
        @SerialName("preset") val preset: String, // "this_week" | "this_month" | "last_30_days" | "this_quarter"
    ) : RuleClause()

    @Serializable
    @SerialName("source_is")
    data class SourceIs(
        @SerialName("value") val value: String, // "scan" | "import" | "share-extension"
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
