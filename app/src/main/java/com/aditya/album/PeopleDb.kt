package com.aditya.album

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
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
 * [coverMediaId] and [cover]: the face the user picked as their cover photo, by photo and box like [PersonFace], or null
 * for the automatic pick. [hidden] only leaves them out of People; [locked] puts them and every photo they're in behind
 * the privacy PIN (Hidden items). [species] makes it a pet (see Pets.kt): its faces are whole photos ([WHOLE]).
 */
@Entity(tableName = "people")
class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String?, // null: corrected or hidden but not named yet
    val hidden: Boolean,
    val created: Long, // epoch millis
    // v0.7 to v0.9 kept a mean face here, in SFace's space. Unused since v0.10 (EdgeFace-S, matched by exemplars); the
    // columns stay so the table never has to be rebuilt under its user data.
    val centroid: ByteArray? = null,
    val centroidFaces: Int = 0,
    val centroidModel: Int = 0,
    val coverMediaId: Long? = null,
    @Embedded(prefix = "cover_") val cover: FaceBox? = null,
    @ColumnInfo(defaultValue = "0") val locked: Boolean = false,
    val species: Species? = null, // null: a person
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

/** "Same person?" answered Different: [a] and [b] (a < b) are never offered as one again. */
@Entity(
    tableName = "different_people",
    primaryKeys = ["a", "b"],
    foreignKeys = [
        ForeignKey(Person::class, ["id"], ["a"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Person::class, ["id"], ["b"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("b")],
)
data class DifferentPeople(val a: Long, val b: Long)

/** What People screens show of a person. */
data class PersonName(
    val id: Long,
    val name: String?,
    val hidden: Boolean,
    val coverMediaId: Long? = null,
    @Embedded(prefix = "cover_") val cover: FaceBox? = null,
    val locked: Boolean = false,
    val species: Species? = null,
)

/**
 * What a "Not <name>" changed, so Undo can put it back exactly. [personId] may be new: an automatic group became a person.
 * [faceIds] are the faces (face index ids) that were in the person, including ones it gained automatically; for a [pet],
 * its photos' ids in pets.db.
 */
class NotPersonChange(
    val personId: Long,
    val removed: List<PersonFace>,
    val added: List<NotPerson>,
    val faceIds: List<Long> = emptyList(),
    val pet: Boolean = false,
)

/** SQLite allows a limited number of `?` per statement, so long id lists go in chunks. */
private const val CHUNK = 500

@Dao
interface PeopleDao {
    @Query("SELECT id, name, hidden, coverMediaId, cover_left, cover_top, cover_right, cover_bottom, locked, species FROM people")
    fun names(): Flow<List<PersonName>>

    @Query("SELECT id FROM people WHERE locked = 1")
    fun lockedIds(): Flow<List<Long>>

    /** Photos with a face confirmed for a locked person: hidden even while the face index is being rebuilt. */
    @Query("SELECT DISTINCT mediaId FROM person_faces WHERE personId IN (SELECT id FROM people WHERE locked = 1)")
    fun lockedPhotos(): Flow<List<Long>>

    @Query("SELECT * FROM people")
    suspend fun people(): List<Person>

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun person(id: Long): Person?

    @Query("SELECT * FROM person_faces")
    suspend fun faces(): List<PersonFace>

    @Query("SELECT * FROM not_person")
    suspend fun notPerson(): List<NotPerson>

    @Query("SELECT * FROM different_people")
    suspend fun different(): List<DifferentPeople>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDifferent(row: DifferentPeople)

    @Query("UPDATE people SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String?)

    @Query("UPDATE people SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE people SET locked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Query("SELECT * FROM person_faces WHERE personId = :personId")
    suspend fun facesOfPerson(personId: Long): List<PersonFace>

    /** Locks [id] and confirms [faces] for them in one go, so a locked person's photos are never left unhidden (see People.setLocked). */
    @Transaction
    suspend fun lock(id: Long, faces: List<PersonFace>) {
        setLocked(id, true)
        insertFaces(faces)
    }

    /** Pins the face at [mediaId] and [box] as [id]'s cover; nulls go back to the automatic pick. */
    @Query("UPDATE people SET coverMediaId = :mediaId, cover_left = :left, cover_top = :top, cover_right = :right, cover_bottom = :bottom WHERE id = :id")
    suspend fun setCover(id: Long, mediaId: Long?, left: Float?, top: Float?, right: Float?, bottom: Float?)

    @Query("SELECT * FROM people WHERE coverMediaId = :mediaId")
    suspend fun coveredBy(mediaId: Long): List<Person>

    @Query("SELECT * FROM person_faces WHERE mediaId = :mediaId")
    suspend fun facesOf(mediaId: Long): List<PersonFace>

    @Update
    suspend fun update(faces: List<PersonFace>)

    /** Turns the boxes of photo [mediaId]: its confirmed faces and any cover pinned on it. */
    @Transaction
    suspend fun rotate(mediaId: Long, clockwise: Boolean) {
        update(facesOf(mediaId).map { it.copy(box = it.box.rotated(clockwise)) })
        for (p in coveredBy(mediaId)) p.cover?.rotated(clockwise)?.let { setCover(p.id, mediaId, it.left, it.top, it.right, it.bottom) }
    }

    /** Faces, "Not" rows and "Different" pairs go too (ON DELETE CASCADE). */
    @Query("DELETE FROM people WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE person_faces SET personId = :to WHERE personId = :from")
    suspend fun moveFaces(from: Long, to: Long)

    // OR IGNORE: a photo "Not" both people stays one row
    @Query("UPDATE OR IGNORE not_person SET personId = :to WHERE personId = :from")
    suspend fun moveNot(from: Long, to: Long)

    /** [from]'s "Different" pairs, copied to [to] (smaller id first); [from]'s own rows go when it does. */
    @Query(
        "INSERT OR IGNORE INTO different_people (a, b) SELECT MIN(:to, other), MAX(:to, other) FROM " +
            "(SELECT CASE WHEN a = :from THEN b ELSE a END AS other FROM different_people WHERE a = :from OR b = :from) WHERE other != :to",
    )
    suspend fun copyDifferent(from: Long, to: Long)

    @Query("SELECT * FROM person_faces WHERE personId = :personId AND mediaId IN (:mediaIds)")
    suspend fun facesIn(personId: Long, mediaIds: List<Long>): List<PersonFace>

    @Query("DELETE FROM person_faces WHERE personId = :personId AND mediaId IN (:mediaIds)")
    suspend fun deleteFaces(personId: Long, mediaIds: List<Long>)

    @Query("DELETE FROM not_person WHERE personId = :personId AND mediaId IN (:mediaIds)")
    suspend fun deleteNot(personId: Long, mediaIds: List<Long>)

    /** [from]'s faces, "Not" photos and "Different" pairs become [to]'s, then [from] goes. */
    @Transaction
    suspend fun merge(from: Long, to: Long) {
        moveFaces(from, to)
        moveNot(from, to)
        copyDifferent(from, to)
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
 * and add an AutoMigration (or a hand-written Migration for renames and splits), with a test in PeopleDbTest.
 * 2 (v0.10): pinned cover photos (nullable columns) and "Different" pairs (a new table); nothing existing changes.
 * 3 (v1.1): people locked behind the privacy PIN (a column that starts false for everyone); nothing existing changes.
 * 4 (v1.1.1): pets (a species column, null for everyone already there); nothing existing changes.
 */
@Database(
    entities = [Person::class, PersonFace::class, NotPerson::class, DifferentPeople::class],
    version = 4,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class PeopleDb : RoomDatabase() {
    abstract fun dao(): PeopleDao
}
