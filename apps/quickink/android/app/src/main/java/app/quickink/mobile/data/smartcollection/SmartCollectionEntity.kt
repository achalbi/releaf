/*
 * SmartCollectionEntity.kt
 *
 * Room @Entity for the `smart_collections` table — rule-based
 * saved views (e.g. "Invoices this month"). The rule lives in
 * `ruleJson` as an AND-of-clauses array; see brief §3 for the v1
 * grammar.
 *
 * Schema mirrors
 * `shared/design-system/migrations/quickink/v4_workspace.sql`.
 */

package app.quickink.mobile.data.smartcollection

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "smart_collections",
    indices = [
        Index(value = ["user_id", "position"], name = "idx_smart_collections_user_position"),
        Index(value = ["dirty"], name = "idx_smart_collections_dirty"),
        Index(value = ["deleted_at"], name = "idx_smart_collections_tombstone"),
    ],
)
data class SmartCollectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "name")
    val name: String,

    /**
     * Tabler icon name, e.g. "ti-receipt", "ti-eye",
     * "ti-signature". Stored as a string so the icon palette can
     * grow without a migration. NULL → default sparkle icon.
     */
    @ColumnInfo(name = "icon")
    val icon: String? = null,

    /**
     * Hex color for the icon background tint in the home strip
     * card. NULL → accent-tint default.
     */
    @ColumnInfo(name = "color")
    val color: String? = null,

    /**
     * AND-of-clauses array as canonical JSON text. See brief §3
     * for the v1 grammar — six clause types, no OR, no nesting.
     * Validated in app code (the [ruleJson] string round-trips
     * through the canonical-JSON serializer), not by the DB.
     *
     * Example seeded value for "Invoices this month":
     * `[{"type":"folder_is","folder_id":"<id>"},
     *   {"type":"date_range","field":"created_at","preset":"this_month"}]`
     */
    @ColumnInfo(name = "rule_json")
    val ruleJson: String,

    @ColumnInfo(name = "position", defaultValue = "0")
    val position: Int = 0,

    /**
     * True for shipped seed collections ("Invoices this month",
     * "Needs review", "Contains signatures"). Lets us silently
     * update the seeded ruleJson in a later release without
     * overwriting user-created collections.
     */
    @ColumnInfo(name = "is_seeded", defaultValue = "0")
    val isSeeded: Boolean = false,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
)
