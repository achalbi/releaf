/*
 * CallHistoryEntity.kt
 *
 * Room entity + DAO for the local Call History log. Rows are
 * written when the user taps a phone number in Contacts (or any
 * other in-app dial affordance), and updated in-place as the
 * TelephonyCallback observes the OFFHOOK → IDLE lifecycle of the
 * outbound call.
 *
 * Local-only today — not sync'd to Drive.
 */

package app.releaf.mobile.data.callhistory

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "call_history",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["started_at"]),
    ],
)
data class CallHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "contact_name")
    val contactName: String,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,

    /** "app" or "device" — which surface the call originated from. */
    @ColumnInfo(name = "source")
    val source: String,

    /** ISO-8601 — when we fired the dial intent. */
    @ColumnInfo(name = "started_at")
    val startedAt: String,

    /** ISO-8601 — when the OS reported OFFHOOK (call connected). */
    @ColumnInfo(name = "connected_at")
    val connectedAt: String? = null,

    /** ISO-8601 — when the OS reported back to IDLE (call ended). */
    @ColumnInfo(name = "ended_at")
    val endedAt: String? = null,

    /**
     * Cached duration — `ended_at - connected_at` in whole seconds.
     * Null when either endpoint was never observed (app process
     * killed mid-call, permission denied, etc.).
     */
    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Long? = null,
)

@Dao
interface CallHistoryDao {

    @Query(
        "SELECT * FROM call_history " +
            "WHERE user_id = :userId " +
            "ORDER BY started_at DESC"
    )
    fun observeAll(userId: String): Flow<List<CallHistoryEntity>>

    @Query("SELECT * FROM call_history WHERE id = :id LIMIT 1")
    suspend fun find(id: String): CallHistoryEntity?

    @androidx.room.Insert
    suspend fun insert(entry: CallHistoryEntity)

    @androidx.room.Update
    suspend fun update(entry: CallHistoryEntity)

    @Query("DELETE FROM call_history WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM call_history WHERE user_id = :userId")
    suspend fun deleteAll(userId: String)
}
