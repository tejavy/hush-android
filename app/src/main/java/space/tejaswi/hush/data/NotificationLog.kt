package space.tejaswi.hush.data

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "notif_log",
    indices = [Index(value = ["dedupeKey"], unique = true), Index("postedAt"), Index("packageName")],
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    /** For MessagingStyle notifications: who sent this message. */
    val sender: String?,
    /** Notification title, or conversation title for group chats. */
    val title: String?,
    val text: String,
    val postedAt: Long,
    /** Stable hash so the same message re-posted (snooze expiry, group update) is stored once. */
    val dedupeKey: String,
)

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<LogEntry>)

    @Query(
        """SELECT * FROM notif_log
           WHERE (:pkg IS NULL OR packageName = :pkg)
             AND (:q = '' OR text LIKE '%' || :q || '%' OR sender LIKE '%' || :q || '%' OR title LIKE '%' || :q || '%')
           ORDER BY postedAt DESC LIMIT 1000"""
    )
    fun search(q: String, pkg: String?): Flow<List<LogEntry>>

    @Query("SELECT DISTINCT packageName FROM notif_log ORDER BY packageName")
    fun packages(): Flow<List<String>>

    @Query("DELETE FROM notif_log WHERE postedAt < :before")
    suspend fun pruneBefore(before: Long)

    @Query("DELETE FROM notif_log")
    suspend fun clear()
}

@Database(entities = [LogEntry::class], version = 1, exportSchema = false)
abstract class HushDatabase : RoomDatabase() {
    abstract fun log(): LogDao

    companion object {
        @Volatile private var instance: HushDatabase? = null
        fun get(context: Context): HushDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, HushDatabase::class.java, "hush.db")
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}

object LogExtractor {
    /**
     * Turn one StatusBarNotification into zero or more log rows.
     *
     * Messaging apps (WhatsApp, Signal, Telegram…) use MessagingStyle, which carries the last N
     * messages with sender and timestamp. Each becomes its own row keyed on the message timestamp,
     * so repeated re-posts of the conversation don't duplicate anything — and a later
     * "This message was deleted" update can't overwrite what we already stored.
     */
    fun extract(sbn: StatusBarNotification): List<LogEntry> {
        val n = sbn.notification
        val extras = n.extras
        val pkg = sbn.packageName
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        if (style != null && style.messages.isNotEmpty()) {
            val convTitle = style.conversationTitle?.toString() ?: title
            return style.messages.mapNotNull { m ->
                val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sender = m.person?.name?.toString()
                LogEntry(
                    packageName = pkg,
                    sender = sender,
                    title = convTitle,
                    text = text,
                    postedAt = m.timestamp,
                    dedupeKey = key(pkg, convTitle, sender, text, m.timestamp),
                )
            }
        }

        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n"))
            ?.toString()?.takeIf { it.isNotBlank() } ?: return emptyList()

        // Non-messaging notifications have no per-message timestamp; bucket to the minute so a
        // snooze re-post (same content) isn't logged twice, but a genuinely new identical
        // notification an hour later is.
        val bucket = sbn.postTime / 60_000
        return listOf(
            LogEntry(
                packageName = pkg,
                sender = null,
                title = title,
                text = text,
                postedAt = sbn.postTime,
                dedupeKey = key(pkg, title, null, text, bucket),
            )
        )
    }

    private fun key(vararg parts: Any?): String = parts.joinToString("\u0000").hashCode().toString(16) +
        ":" + parts.joinToString("\u0000").length
}
