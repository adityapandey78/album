package com.aditya.album

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A photo the face index has looked at, with or without faces, so it isn't scanned again until it changes. */
@Entity(tableName = "scanned")
data class ScannedPhoto(
    @PrimaryKey val id: Long, // MediaStore id
    val modified: Long,
    val size: Long,
    val version: Int, // FACE_INDEX_VERSION it was scanned with; other versions are redone
)

/**
 * One face in a photo. [cluster] is the person or automatic group it's in (see cluster()): 0 until clustered, UNSURE
 * when it belongs to nobody yet. [quality] picks covers (see faceQuality); a [weak] face never starts a person.
 */
@Entity(
    tableName = "faces",
    foreignKeys = [ForeignKey(ScannedPhoto::class, ["id"], ["mediaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mediaId"), Index("cluster")],
)
class IndexedFace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    @Embedded val box: FaceBox,
    val quality: Float,
    val weak: Boolean,
    val embedding: ByteArray, // EdgeFace-S embedding, unit length, little-endian floats
    val cluster: Long = 0,
    val fit: Float = 0f, // see FaceVector.fit
)

/** What the People screens need of a face: no embedding, so loading every face stays cheap. */
data class FaceTile(val id: Long, val mediaId: Long, val cluster: Long, val quality: Float, val fit: Float, @Embedded val box: FaceBox)

class FaceRow(val id: Long, val mediaId: Long, @Embedded val box: FaceBox, val embedding: ByteArray, val cluster: Long, val weak: Boolean, val fit: Float)

data class FaceSpot(val mediaId: Long, @Embedded val box: FaceBox)

@Dao
interface FaceDao {
    @Query("SELECT id, modified, size, version, 1 AS located FROM scanned")
    suspend fun stamps(): List<IndexStamp>

    /** Replaces [photos]' faces in one transaction, so a stopped run never leaves half a photo. */
    @Transaction
    suspend fun save(photos: List<ScannedPhoto>, faces: List<IndexedFace>) {
        deleteFaces(photos.map { it.id })
        upsert(photos)
        insert(faces)
    }

    @Upsert
    suspend fun upsert(photos: List<ScannedPhoto>)

    @Insert
    suspend fun insert(faces: List<IndexedFace>)

    @Query("DELETE FROM faces WHERE mediaId IN (:mediaIds)")
    suspend fun deleteFaces(mediaIds: List<Long>)

    /** Faces go too (ON DELETE CASCADE). */
    @Query("DELETE FROM scanned WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT id, mediaId, `left`, top, `right`, bottom, embedding, cluster, weak, fit FROM faces")
    suspend fun rows(): List<FaceRow>

    @Query("SELECT id, mediaId, cluster, quality, fit, `left`, top, `right`, bottom FROM faces WHERE cluster > 0")
    fun tiles(): Flow<List<FaceTile>>

    @Query("SELECT mediaId, `left`, top, `right`, bottom FROM faces WHERE cluster = :cluster")
    suspend fun spots(cluster: Long): List<FaceSpot>

    @Query("SELECT EXISTS(SELECT 1 FROM faces WHERE cluster = :cluster)")
    suspend fun has(cluster: Long): Boolean

    @Query("SELECT id FROM faces WHERE cluster = :cluster AND mediaId IN (:mediaIds)")
    suspend fun facesIn(cluster: Long, mediaIds: List<Long>): List<Long>

    @Query("UPDATE faces SET cluster = :cluster, fit = :fit WHERE id = :id")
    suspend fun place(id: Long, cluster: Long, fit: Float)

    @Query("UPDATE faces SET cluster = :cluster WHERE id = :id")
    suspend fun setCluster(id: Long, cluster: Long)

    @Query("UPDATE faces SET cluster = :to WHERE cluster = :from")
    suspend fun moveCluster(from: Long, to: Long)

    @Transaction
    suspend fun place(placed: Map<Long, Placed>) = placed.forEach { (id, p) -> place(id, p.cluster, p.fit) }

    @Transaction
    suspend fun setClusters(clusters: Map<Long, Long>) = clusters.forEach { (id, cluster) -> setCluster(id, cluster) }
}

/**
 * The face index: a cache rebuilt from the photos, so a schema change may drop it (fallbackToDestructiveMigration).
 * Version 2: EdgeFace-S embeddings (512 floats) replace SFace's; dropping the old ones means no model is ever compared with the other.
 */
@Database(entities = [ScannedPhoto::class, IndexedFace::class], version = 2)
abstract class FaceDb : RoomDatabase() {
    abstract fun dao(): FaceDao
}
