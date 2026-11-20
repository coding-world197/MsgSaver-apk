package com.example.msgsaver

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class SavedMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val isDeleted: Boolean = false
)

@Entity(tableName = "files", indices = [Index(value = ["category", "name"], unique = true)])
data class BackupFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,          // "status", "voice" or "photo"
    val name: String,
    val path: String,
    val origModified: Long,
    val savedAt: Long,
    val size: Long,
    val isBiz: Boolean = false     // true = WhatsApp Business, false = regular WhatsApp
)

@Dao
interface AppDao {

    
    // messages
    @Insert suspend fun insert(m: SavedMessage)

    @Query("SELECT * FROM files WHERE category = 'viewonce' AND name LIKE 'notif\\_%' ESCAPE '\\'")
    suspend fun notifItems(): List<BackupFile>

    @Query("DELETE FROM files WHERE category = 'viewonce' AND name LIKE 'notif\\_%' ESCAPE '\\'")
    suspend fun deleteNotifItems()

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun messages(): Flow<List<SavedMessage>>

    @Query("SELECT COUNT(*) FROM messages WHERE sender = :s AND text = :t AND timestamp > :since")
    suspend fun recentDuplicates(s: String, t: String, since: Long): Int

    @Query("""UPDATE messages SET isDeleted = 1 WHERE id =
        (SELECT id FROM messages WHERE sender = :s AND isDeleted = 0 ORDER BY timestamp DESC LIMIT 1)""")
    suspend fun markLatestDeleted(s: String)

    @Query("DELETE FROM messages") suspend fun clearMessages()

    // files
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFile(f: BackupFile): Long

    @Query("""SELECT * FROM files WHERE category = :c
        ORDER BY CASE WHEN origModified > 0 THEN origModified ELSE savedAt END DESC""")
    fun files(c: String): Flow<List<BackupFile>>

    @Query("SELECT COUNT(*) FROM files WHERE category = :c AND name = :n")
    suspend fun fileExists(c: String, n: String): Int

    @Delete suspend fun deleteFile(f: BackupFile)

    @Query("DELETE FROM files") suspend fun clearFiles()
}

@Database(entities = [SavedMessage::class, BackupFile::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "msgs.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
