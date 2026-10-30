package com.aditya.album

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * A photo or video Album has copied to a backup folder, as it was then. A cache of what's on the drive, not the truth:
 * losing it only means the next backup checks the drive again (same name, size and hash) instead of copying twice.
 */
@Entity(tableName = "copies", primaryKeys = ["dest", "mediaId"])
data class BackupCopy(
    val dest: String, // the backup's folder tree uri: each drive keeps its own rows, so two drives can take turns
    val mediaId: Long,
    val size: Long,
    val modified: Long, // MediaStore DATE_MODIFIED (seconds); with size, tells an edited photo from an unchanged one
    val sha256: String, // hex, of the bytes on the drive, read back after copying
    val path: String, // where the copy is in the backup folder: "DCIM/Camera/IMG_1.jpg"
)

/** The columns that decide whether an item needs copying again. */
data class CopyStamp(val mediaId: Long, val size: Long, val modified: Long, val sha256: String)

@Dao
interface BackupDao {
    @Query("SELECT mediaId, size, modified, sha256 FROM copies WHERE dest = :dest")
    suspend fun stamps(dest: String): List<CopyStamp>

    @Upsert
    suspend fun save(copy: BackupCopy)

    /** The file changed on the phone but not its bytes (a rescan, say): its copy still stands. */
    @Query("UPDATE copies SET size = :size, modified = :modified WHERE dest = :dest AND mediaId = :mediaId")
    suspend fun touch(dest: String, mediaId: Long, size: Long, modified: Long)

    /** The backup folder is gone from the drive, so these copies are too. */
    @Query("DELETE FROM copies WHERE dest = :dest")
    suspend fun forget(dest: String)
}

@Database(entities = [BackupCopy::class], version = 1)
abstract class BackupDb : RoomDatabase() {
    abstract fun dao(): BackupDao
}
