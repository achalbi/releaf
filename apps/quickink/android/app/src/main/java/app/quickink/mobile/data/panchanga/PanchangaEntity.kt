/*
 * PanchangaEntity.kt
 *
 * One row of the bundled Vontikoppal / Mysore Panchanga dataset
 * (`assets/panchanga_2026_27.csv`). Each row maps a single Gregorian
 * date to the lunar reckoning that Karnataka's Smarta tradition uses:
 * lunar month (`masa`), bright/dark fortnight (`paksha`), lunar day
 * (`thithi` + numeric `thithiNum`), and a free-form `specialDay`
 * column carrying festival or observance names.
 *
 * A handful of dates carry TWO rows because two tithis can land on
 * the same Gregorian date when the lunar day rolls over near
 * midnight. The DAO returns a list per date for that reason — never
 * assume one row per date.
 *
 * Some `thithi` and `thithiNum` cells contain slash-separated dual
 * values (e.g. "Panchami/Shashti", "5/6") for transitional days.
 * Stored verbatim from the CSV so the UI can render the source label
 * without inventing a normalisation.
 *
 * Source: OCR-derived dataset from
 * https://github.com/susheelkv/karnataka-panchanga — community
 * dataset, no upstream licence; verify ritual-critical dates against
 * the printed Ontikoppal Panchanga.
 *
 * Port of Releaf Android's `PanchangaEntity` — package rename only.
 */

package app.quickink.mobile.data.panchanga

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "panchanga",
    indices = [
        Index("date"),
        // Stored as TEXT lower-cased on insert so a `LIKE '%query%'`
        // search can stay case-insensitive without function calls in
        // the WHERE clause.
        Index("special_day_lc"),
    ],
)
data class PanchangaEntity(
    /**
     * Composite key of `date#thithi_num` rather than a synthetic
     * autoincrement so re-inserting the dataset is idempotent: a
     * REPLACE-on-conflict upsert overwrites the existing row instead
     * of duplicating it on every refresh.
     */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** ISO Gregorian date, e.g. `2026-03-19`. */
    @ColumnInfo(name = "date")
    val date: String,

    /** Lunar month, e.g. `Chaitra`, `Vaishakha`, `Nija Jyeshtha`. */
    @ColumnInfo(name = "masa")
    val masa: String,

    /** `Shukla` (waxing) or `Krishna` (waning); rarely `Shukla/Krishna`
     *  on transition days. */
    @ColumnInfo(name = "paksha")
    val paksha: String,

    /** Lunar-day name, e.g. `Pratipada`, `Ekadashi`, `Purnima`. */
    @ColumnInfo(name = "thithi")
    val thithi: String,

    /** Numeric tithi as a string — preserved verbatim from the CSV
     *  (which carries `5/6`-style dual values for some dates). */
    @ColumnInfo(name = "thithi_num")
    val thithiNum: String,

    /** Festival or observance text. Empty string when none. */
    @ColumnInfo(name = "special_day")
    val specialDay: String,

    /** Lower-cased mirror of `special_day` for case-insensitive
     *  `LIKE` search. Computed on insert; never written from the UI. */
    @ColumnInfo(name = "special_day_lc")
    val specialDayLowercase: String,
)
