package com.aditya.album

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A photo or video in the private safe or in ID cards ([bucket]). Its content lives encrypted in filesDir/safe/<id>.bin
 * (thumbnail: <id>.thumb), or filesDir/safe/id/ for ID cards; this row keeps what's needed to show it and to put it back
 * where it came from, as it was.
 */
@Entity(tableName = "items")
data class SafeItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, // DISPLAY_NAME
    val mime: String,
    val isVideo: Boolean,
    val taken: Long, // epoch millis
    val width: Int, // as shown, already turned upright
    val height: Int,
    val durationMs: Long,
    val path: String, // the RELATIVE_PATH it came from
    val favorite: Boolean,
    val size: Long, // plain bytes, set once verified
    val sha256: String?, // hex, of the plain bytes, set once verified; a restored copy must match it
    /**
     * The MediaStore uri of the original while the move in waits for Android to delete it; null once moved. Rows with
     * one are not in the safe yet, and are finished or undone by Safe.settle.
     */
    val original: String?,
    val added: Long, // epoch millis
    /** [SAFE] or [ID_CARDS]: which key encrypts it, and which page lists it. */
    @ColumnInfo(defaultValue = "0") val bucket: Int = SAFE,
) {
    companion object {
        const val SAFE = 0
        const val ID_CARDS = 1
    }
}

@Dao
interface SafeDao {
    @Query("SELECT * FROM items WHERE original IS NULL AND bucket = :bucket ORDER BY taken DESC")
    fun items(bucket: Int): Flow<List<SafeItem>>

    @Query("SELECT * FROM items WHERE original IS NOT NULL")
    suspend fun waiting(): List<SafeItem>

    @Query("SELECT COUNT(*) FROM items WHERE original IS NULL AND bucket = :bucket")
    suspend fun count(bucket: Int): Int

    @Insert
    suspend fun insert(item: SafeItem): Long

    @Query("UPDATE items SET size = :size, sha256 = :sha256 WHERE id = :id")
    suspend fun verified(id: Long, size: Long, sha256: String)

    @Query("UPDATE items SET original = NULL WHERE id = :id")
    suspend fun moved(id: Long)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun delete(id: Long)

    /** Only for the resets, after Android erased the key that every file of [bucket] needs. */
    @Query("DELETE FROM items WHERE bucket = :bucket")
    suspend fun clear(bucket: Int)
}

/**
 * The safe's list is user data, and the only map from files to photos: never add fallbackToDestructiveMigration. Without
 * a migration Room refuses to open a newer version (a crash on launch) rather than silently forgetting the safe.
 * To change the schema: bump the version, keep the exported app/schemas/com.aditya.album.SafeDb/N.json files in git, and
 * add `autoMigrations = [AutoMigration(from = N, to = N + 1)]` (or a hand-written Migration for renames and splits).
 */
@Database(entities = [SafeItem::class], version = 2, autoMigrations = [AutoMigration(from = 1, to = 2)]) // 2: ID cards' bucket
abstract class SafeDb : RoomDatabase() {
    abstract fun dao(): SafeDao
}
