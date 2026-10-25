package com.aditya.album

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * A photo or video describe-it search has encoded, keyed by its MediaStore id. [vector] is the model's unit-length
 * embedding as int8 (see [quantize]); multiplied by [scale] it's the real values. Null when the file couldn't be read:
 * the row still stops it being tried again until it changes.
 */
@Entity(tableName = "vectors")
class PhotoVector(
    @PrimaryKey val id: Long,
    val modified: Long,
    val size: Long,
    val version: Int, // DESCRIBE_VERSION it was encoded with; other versions are redone
    val vector: ByteArray?,
    val scale: Float,
)

class StoredVector(val id: Long, val vector: ByteArray, val scale: Float)

@Dao
interface VectorDao {
    @Query("SELECT id, modified, size, version, 1 AS located FROM vectors")
    suspend fun stamps(): List<IndexStamp>

    @Upsert
    suspend fun save(rows: List<PhotoVector>)

    @Query("DELETE FROM vectors WHERE id IN (:ids)")
    suspend fun remove(ids: List<Long>)

    @Query("SELECT id, vector, scale FROM vectors WHERE vector IS NOT NULL")
    suspend fun vectors(): List<StoredVector>

    /** Blocking (call off the main thread), so a removal runs start to finish before a download queued after it. */
    @Query("DELETE FROM vectors")
    fun clear()
}

/** Describe-it search's index: a cache rebuilt from the photos, so a schema change may drop it (destructive migration). */
@Database(entities = [PhotoVector::class], version = 1)
abstract class EmbeddingDb : RoomDatabase() {
    abstract fun dao(): VectorDao
}
