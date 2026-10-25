package com.aditya.album

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * What cleanup measured on one photo or video, keyed by its MediaStore id. [modified], [size] and [version] tell an
 * unchanged file from an edited one, so each file is measured once. Videos only get a row when they need a [hash].
 */
@Entity(tableName = "items")
data class CleanupRow(
    @PrimaryKey val id: Long,
    val modified: Long, // MediaStore DATE_MODIFIED (seconds)
    val size: Long,
    val version: Int, // CLEANUP_VERSION it was measured with; other versions are redone
    val dhash: Long?, // 64-bit difference hash of the thumbnail; null for videos and unreadable photos
    val sharpness: Float?, // variance of the Laplacian on the small grey image
    val contrast: Float?, // standard deviation of the small grey image
    val hash: String?, // SHA-256 of the file, only worked out when another file has the same size
)

@Dao
interface CleanupDao {
    @Query("SELECT * FROM items")
    suspend fun all(): List<CleanupRow>

    @Upsert
    suspend fun save(rows: List<CleanupRow>)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun remove(ids: List<Long>)
}

// Its own file, apart from the smart index, so either can be rebuilt without the other. Schema exported to app/schemas.
@Database(entities = [CleanupRow::class], version = 1)
abstract class CleanupDb : RoomDatabase() {
    abstract fun dao(): CleanupDao
}
