package com.aditya.album

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A photo that shows a dog or a cat, as pets were last grouped (see groupPets). [cluster] is 0 until it has a
 * describe-it vector, [UNSURE], or its pet or automatic group. [quality] is how sure ML Kit is of the animal, for covers.
 */
@Entity(tableName = "pets", indices = [Index("cluster")])
data class PetRow(
    @PrimaryKey val mediaId: Long,
    val species: Species,
    val cluster: Long,
    val fit: Float, // see FaceVector.fit
    val quality: Float,
) {
    /** The photo as People's screens take a face: the whole of it, under its own id (see petTileId). */
    fun tile() = FaceTile(petTileId(mediaId), mediaId, cluster, quality, fit, WHOLE)
}

@Dao
interface PetDao {
    @Query("SELECT * FROM pets")
    suspend fun rows(): List<PetRow>

    @Query("SELECT * FROM pets WHERE cluster > 0")
    fun tiles(): Flow<List<PetRow>>

    @Query("SELECT mediaId FROM pets WHERE cluster = :cluster")
    suspend fun photos(cluster: Long): List<Long>

    @Query("SELECT species FROM pets WHERE cluster = :cluster LIMIT 1")
    suspend fun speciesOf(cluster: Long): Species?

    @Query("SELECT EXISTS(SELECT 1 FROM pets WHERE cluster = :cluster)")
    suspend fun has(cluster: Long): Boolean

    @Query("SELECT mediaId FROM pets WHERE cluster = :cluster AND mediaId IN (:mediaIds)")
    suspend fun photosIn(cluster: Long, mediaIds: List<Long>): List<Long>

    @Query("UPDATE pets SET cluster = :to WHERE cluster = :from")
    suspend fun moveCluster(from: Long, to: Long)

    @Query("UPDATE pets SET cluster = :cluster WHERE mediaId = :mediaId")
    suspend fun setCluster(mediaId: Long, cluster: Long)

    @Transaction
    suspend fun setClusters(clusters: Map<Long, Long>) = clusters.forEach { (mediaId, cluster) -> setCluster(mediaId, cluster) }

    @Query("DELETE FROM pets WHERE mediaId IN (:mediaIds)")
    suspend fun delete(mediaIds: List<Long>)

    @Upsert
    suspend fun upsert(rows: List<PetRow>)

    /** Saves a grouping in one transaction: [rows] that changed, and [gone], photos that no longer show a pet. */
    @Transaction
    suspend fun save(rows: List<PetRow>, gone: List<Long>) {
        gone.chunked(500).forEach { delete(it) } // under SQLite's 999-variable limit
        upsert(rows)
    }
}

/**
 * Pet groups: a cache rebuilt from the index's labels and describe-it vectors, so a schema change may drop it
 * (fallbackToDestructiveMigration). Names and corrections live in people.db, like people's.
 */
@Database(entities = [PetRow::class], version = 1)
abstract class PetDb : RoomDatabase() {
    abstract fun dao(): PetDao
}
