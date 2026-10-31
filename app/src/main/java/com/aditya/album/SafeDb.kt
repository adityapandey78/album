package com.aditya.album

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
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
// The index serves the list (bucket, newest first) without sorting every row; see [all] for why it's read in pages
@Entity(tableName = "items", indices = [Index("bucket", "taken")])
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
    /**
     * A move in made while privacy was locked: the item's own key, which its files are encrypted with, sealed to the safe's
     * public key (base64, see Safe). Only the safe key opens it. Null: the safe key encrypts its files itself.
     */
    val sealedKey: String? = null,
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

    /** The [limit] items of [bucket] after ([taken], [id]) in the list's order. The index finds the first, so a page costs only its rows. */
    @Query(
        "SELECT * FROM items WHERE original IS NULL AND bucket = :bucket AND taken <= :taken AND (taken < :taken OR id < :id) " +
            "ORDER BY taken DESC, id DESC LIMIT :limit",
    )
    suspend fun page(bucket: Int, taken: Long, id: Long, limit: Int): List<SafeItem>

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
 * Everything in [bucket], newest first, [size] rows a query. One query for all of it would be read by Android in 2 MB
 * windows (about 3,500 rows), running the query again from the first row for each: quadratic in the safe's size, over half
 * a second at 20,000 items. A page fits in one window, and each starts where the last ended.
 */
suspend fun SafeDao.all(bucket: Int, size: Int = 2000): List<SafeItem> {
    val out = ArrayList<SafeItem>()
    while (true) {
        val last = out.lastOrNull()
        val page = page(bucket, last?.taken ?: Long.MAX_VALUE, last?.id ?: Long.MAX_VALUE, size)
        out += page
        if (page.size < size) return out
    }
}

/**
 * The safe's list is user data, and the only map from files to photos: never add fallbackToDestructiveMigration. Without
 * a migration Room refuses to open a newer version (a crash on launch) rather than silently forgetting the safe.
 * To change the schema: bump the version, keep the exported app/schemas/com.aditya.album.SafeDb/N.json files in git, and
 * add `autoMigrations = [AutoMigration(from = N, to = N + 1)]` (or a hand-written Migration for renames and splits).
 */
// 2: ID cards' bucket. 3: sealed move-ins' keys. 4: the list's index
@Database(
    entities = [SafeItem::class],
    version = 4,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class SafeDb : RoomDatabase() {
    abstract fun dao(): SafeDao
}
