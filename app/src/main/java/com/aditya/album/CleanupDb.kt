package com.aditya.album

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * What cleanup measured on one photo or video, keyed by its MediaStore id. [modified], [size] and [version] tell an
 * unchanged file from an edited one, so each file is measured once. Videos only get a row when they need a [hash].
 * Photo measurements are of the picture as it shows: turned upright, at most LOOK_PX on its long side, any plain frame
 * trimmed, and for screenshots without the status and navigation bars.
 */
@Entity(tableName = "items")
data class CleanupRow(
    @PrimaryKey val id: Long,
    val modified: Long, // MediaStore DATE_MODIFIED (seconds)
    val size: Long,
    val version: Int, // CLEANUP_VERSION it was measured with; other versions are redone
    val pdq: String? = null, // PDQ hash, 64 hex digits; null for videos and unreadable photos
    val quality: Int? = null, // PDQ's quality, 0 to 100: how much detail it had to go on
    val blur: Float? = null, // sharpness of the sharpest textured tiles, var(LoG) / var(brightness); null with none textured
    val textured: Float? = null, // share of the 8 x 8 tiles with enough texture to judge sharpness on
    val low: Int? = null, // brightness (0 to 255) of the darkest 1% of pixels
    val high: Int? = null, // brightness of the brightest 1%
    val clipped: Float? = null, // share of pixels blown to white or crushed to black
    val capture: String? = null, // camera EXIF capture time with fractions of a second; null without camera details (shared copies lose them)
    val night: Boolean? = null, // an exposure of 1/15 s or longer, or ISO 1600 and up: soft by nature
    val portrait: Boolean? = null, // has depth data (portrait mode): the soft background is on purpose
    val faces: Int? = null, // faces found on a closer look; null until one was needed (similar shots, blur suspects)
    val eyes: Float? = null, // lowest eyes-open probability over those faces
    val smile: Float? = null, // average smiling probability
    val faceSharpness: Float? = null, // sharpness of the sharpest face, as [blur] measures it
    val hash: String? = null, // SHA-256 without metadata (see imageHash), only when another file might be the same picture
)

/**
 * Two photos that look the same, turned or mirrored or not: PDQ hashes at most COPY_DISTANCE bits apart, both good enough
 * to compare. Found when the later of the two was measured, so finding copies across the whole library costs one search
 * per new photo instead of one over every pair whenever the library changes. [a] < [b].
 */
@Entity(tableName = "copies", primaryKeys = ["a", "b"], indices = [Index("b")])
data class CopyPair(val a: Long, val b: Long, val distance: Int)

@Dao
interface CleanupDao {
    @Query("SELECT * FROM items")
    suspend fun all(): List<CleanupRow>

    @Query("SELECT * FROM copies")
    suspend fun pairs(): List<CopyPair>

    @Upsert
    suspend fun upsert(rows: List<CleanupRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pairs: List<CopyPair>)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteRows(ids: List<Long>)

    @Query("DELETE FROM copies WHERE a IN (:ids) OR b IN (:ids)")
    suspend fun deletePairs(ids: List<Long>)

    /** Saves a batch: [rows], and for the photos in [looked] (measured afresh) their copies, replacing what they had. */
    @Transaction
    suspend fun save(rows: List<CleanupRow>, looked: List<Long>, pairs: List<CopyPair>) {
        upsert(rows)
        if (looked.isNotEmpty()) deletePairs(looked)
        insert(pairs)
    }

    /** Forgets media that's gone, in pieces under SQLite's limit on query arguments. */
    @Transaction
    suspend fun remove(ids: List<Long>) = ids.chunked(400).forEach {
        deleteRows(it)
        deletePairs(it)
    }
}

// Its own file, apart from the smart index, so either can be rebuilt without the other. Schema exported to app/schemas.
// 2: PDQ, copies, tiled blur, brightness and camera details (v0.10). A cache: opened with fallbackToDestructiveMigration,
// so the update drops version 1's measurements and the next pass measures every photo again.
@Database(entities = [CleanupRow::class, CopyPair::class], version = 2)
abstract class CleanupDb : RoomDatabase() {
    abstract fun dao(): CleanupDao
}
