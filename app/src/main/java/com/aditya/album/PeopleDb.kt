package com.aditya.album

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A person the user named, hid or corrected. Automatic groups only live in the face index; touching one makes it a person.
 * [centroid] is the mean of the person's face embeddings, so their new photos find them while a rebuilt index is still
 * re-finding the old ones.
 */
@Entity(tableName = "people")
class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String?, // null: corrected or hidden but not named yet
    val hidden: Boolean,
    val created: Long, // epoch millis
    val centroid: ByteArray? = null, // unit length, little-endian floats
    val centroidFaces: Int = 0, // how many faces it was averaged from: its weight, and how many it takes to replace it
    val centroidModel: Int = 0, // FACE_MODEL the centroid came from; any other is ignored
)

/**
 * A face the user put in a person, by naming or hiding its group, or merging. Kept by photo and box, not by face id,
 * so it survives a rebuild of the face index and even a newer face detector (boxes are matched by overlap).
 */
@Entity(
    tableName = "person_faces",
    foreignKeys = [ForeignKey(Person::class, ["id"], ["personId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("personId"), Index("mediaId")],
)
data class PersonFace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, // later rows win when two people claim one face
    val personId: Long,
    val mediaId: Long,
    @Embedded val box: FaceBox,
)

/** "Not <name>": no face in this photo is this person, whatever the automatic clustering thinks. */
@Entity(
    tableName = "not_person",
    primaryKeys = ["personId", "mediaId"],
    foreignKeys = [ForeignKey(Person::class, ["id"], ["personId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mediaId")],
)
data class NotPerson(val personId: Long, val mediaId: Long)

/** What People screens show of a person. */
data class PersonName(val id: Long, val name: String?, val hidden: Boolean)

/**
 * What a "Not <name>" changed, so Undo can put it back exactly. [personId] may be new: an automatic group became a person.
 * [faceIds] are the faces (face index ids) that were in the person, including ones it gained automatically.
 */
class NotPersonChange(val personId: Long, val removed: List<PersonFace>, val added: List<NotPerson>, val faceIds: List<Long> = emptyList())

/** SQLite allows a limited number of `?` per statement, so long id lists go in chunks. */
private const val CHUNK = 500

@Dao
interface PeopleDao {
    @Query("SELECT id, name, hidden FROM people")
    fun names(): Flow<List<PersonName>>

    @Query("SELECT * FROM people")
    suspend fun people(): List<Person>

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun person(id: Long): Person?

    @Query("SELECT * FROM person_faces")
    suspend fun faces(): List<PersonFace>

    @Query("SELECT * FROM not_person")
    suspend fun notPerson(): List<NotPerson>

    @Insert
    suspend fun insert(person: Person): Long

    @Insert
    suspend fun insertFaces(faces: List<PersonFace>)

    /** A new person holding the faces at [spots]. One transaction, so a person never exists without its faces. */
    @Transaction
    suspend fun create(person: Person, spots: List<FaceSpot>): Long {
        val id = insert(person)
        insertFaces(spots.map { PersonFace(personId = id, mediaId = it.mediaId, box = it.box) })
        return id
    }

    /** Row ids, -1 for photos that were "Not" this person already. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNot(rows: List<NotPerson>): List<Long>

    @Query("UPDATE people SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String?)

    @Query("UPDATE people SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE people SET centroid = :centroid, centroidFaces = :faces, centroidModel = :model WHERE id = :id")
    suspend fun setCentroid(id: Long, centroid: ByteArray, faces: Int, model: Int)

    @Query("SELECT * FROM person_faces WHERE mediaId = :mediaId")
    suspend fun facesOf(mediaId: Long): List<PersonFace>

    @Update
    suspend fun update(faces: List<PersonFace>)

    /** Faces, centroid and "Not" rows go too (ON DELETE CASCADE). */
    @Query("DELETE FROM people WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE person_faces SET personId = :to WHERE personId = :from")
    suspend fun moveFaces(from: Long, to: Long)

    // OR IGNORE: a photo "Not" both people stays one row
    @Query("UPDATE OR IGNORE not_person SET personId = :to WHERE personId = :from")
    suspend fun moveNot(from: Long, to: Long)

    @Query("SELECT * FROM person_faces WHERE personId = :personId AND mediaId IN (:mediaIds)")
    suspend fun facesIn(personId: Long, mediaIds: List<Long>): List<PersonFace>

    @Query("DELETE FROM person_faces WHERE personId = :personId AND mediaId IN (:mediaIds)")
    suspend fun deleteFaces(personId: Long, mediaIds: List<Long>)

    @Query("DELETE FROM not_person WHERE personId = :personId AND mediaId IN (:mediaIds)")
    suspend fun deleteNot(personId: Long, mediaIds: List<Long>)

    /** [from]'s faces and "Not" photos become [to]'s, then [from] goes. */
    @Transaction
    suspend fun merge(from: Long, to: Long) {
        moveFaces(from, to)
        moveNot(from, to)
        delete(from)
    }

    /** Takes [mediaIds] out of [personId] for good: drops its faces there and marks the photos "Not" it. */
    @Transaction
    suspend fun notPerson(personId: Long, mediaIds: List<Long>): NotPersonChange {
        val removed = mediaIds.chunked(CHUNK).flatMap { chunk -> facesIn(personId, chunk).also { deleteFaces(personId, chunk) } }
        val rows = mediaIds.map { NotPerson(personId, it) }
        val ids = rows.chunked(CHUNK).flatMap { insertNot(it) }
        return NotPersonChange(personId, removed, rows.filterIndexed { i, _ -> ids[i] != -1L })
    }

    /** Undo for [notPerson]. */
    @Transaction
    suspend fun undo(change: NotPersonChange) {
        change.added.groupBy({ it.personId }, { it.mediaId }).forEach { (personId, mediaIds) -> mediaIds.chunked(CHUNK).forEach { deleteNot(personId, it) } }
        insertFaces(change.removed)
    }
}

/**
 * People are user data, unlike the face index: never add fallbackToDestructiveMigration. Without a migration, Room
 * refuses to open a newer version (a crash on launch) rather than silently dropping everyone's names and corrections.
 * To change the schema: bump the version, keep the exported app/schemas/com.aditya.album.PeopleDb/N.json files in git,
 * and add `autoMigrations = [AutoMigration(from = N, to = N + 1)]` (or a hand-written Migration for renames and splits).
 */
@Database(entities = [Person::class, PersonFace::class, NotPerson::class], version = 1)
abstract class PeopleDb : RoomDatabase() {
    abstract fun dao(): PeopleDao
}
