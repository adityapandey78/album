package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.system.Os
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToLong

// Fix a photo's date, time and place, for one or many. Where the file allows it, the fix goes into the file itself the
// way Rotate changes orientation: the same number of bytes overwritten in place (Exif dates are fixed-length text, GPS
// values fixed-size numbers), checked before, read back after, and put back if the read-back is wrong. Pixels are never
// touched and the file never grows or moves. Where it doesn't (PNG, HEIC, videos, a photo with no date or GPS to
// overwrite), the fix lives in Album only: collections.db keeps it, the timeline and the smart index use it, and the
// result says so. MediaStore's date is set either way, so other apps see the new date too.

/** Formats whose Exif can be overwritten in place: PNG keeps Exif in a checksummed chunk, HEIC and videos elsewhere. */
private val IN_PLACE = setOf("image/jpeg", "image/webp")

// ---------- Dates ----------

private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
private val GPS_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd")

/** Exif's date and time text, "2024:03:15 18:30:12". */
fun exifDateTime(t: LocalDateTime): String = t.format(EXIF_DATE)

fun parseExifDateTime(text: String?): LocalDateTime? = runCatching { LocalDateTime.parse(text!!.trim(), EXIF_DATE) }.getOrNull()

/** A shift of [days], [hours] and [minutes], in millis: forward in time when [later], else back. */
fun shiftOf(days: Int, hours: Int, minutes: Int, later: Boolean): Long =
    (Duration.ofDays(days.toLong()) + Duration.ofHours(hours.toLong()) + Duration.ofMinutes(minutes.toLong())).toMillis() * if (later) 1 else -1

/**
 * The shift that puts the first photo ([first], epoch millis) at [date] [time] on the phone's clock ([zone]). Every
 * photo moves by it, so they keep their order and the time between them. A time a clock change skipped (2:30 on a
 * spring-forward night) counts as the same time after the change, as the clocks did.
 */
fun shiftToSet(first: Long, date: LocalDate, time: LocalTime, zone: ZoneId): Long =
    date.atTime(time).atZone(zone).toInstant().toEpochMilli() - first

/** "5 hours 30 minutes later", "1 day earlier": how far a shift moves photos, to the minute. */
fun shiftText(ms: Long): String {
    val minutes = abs(ms) / 60_000
    val parts = listOfNotNull(
        (minutes / 1440).takeIf { it > 0 }?.let { counted(it.toInt(), "day", "days") },
        (minutes / 60 % 24).takeIf { it > 0 }?.let { counted(it.toInt(), "hour", "hours") },
        (minutes % 60).takeIf { it > 0 }?.let { counted(it.toInt(), "minute", "minutes") },
    )
    return if (parts.isEmpty()) "Same time" else parts.joinToString(" ") + if (ms > 0) " later" else " earlier"
}

/**
 * Exif date text [old] moved by [shift], or null when [old] isn't a date. Exif keeps the camera's own clock time, with
 * the time zone (if any) in a separate tag Album leaves alone, so the clock time moves by exactly the shift and the photo
 * still means the same zone.
 */
fun shiftedExif(old: String?, shift: Long): String? = parseExifDateTime(old)?.plus(Duration.ofMillis(shift))?.let(::exifDateTime)

/**
 * The GPS date and time (UTC) moved by [shift]: [date] is Exif's "2024:03:15", [time] its hours, minutes and seconds as
 * rationals (numerator, denominator each). Null when either isn't readable. The camera's clock and its GPS time move
 * together: if they disagreed afterwards, MediaStore, which works out a photo's time zone from the two, would undo the fix.
 */
fun shiftedGpsStamp(date: String?, time: LongArray, shift: Long): Pair<String, LongArray>? {
    val day = runCatching { LocalDate.parse(date!!.trim(), GPS_DATE) }.getOrNull() ?: return null
    if (time.size != 6 || time[1] == 0L || time[3] == 0L || time[5] == 0L) return null
    val seconds = time[0] * 3600 / time[1] + time[2] * 60 / time[3] + time[4] / time[5]
    val moved = day.atStartOfDay().plusSeconds(seconds).plus(Duration.ofMillis(shift))
    return moved.format(GPS_DATE) to longArrayOf(moved.hour.toLong(), 1, moved.minute.toLong(), 1, moved.second.toLong(), 1)
}

/** The date Album shows for an item with MediaStore id: its [fix] while the file is unchanged since ([modified] seconds). */
fun fixedTaken(fix: MediaFix?, modified: Long): Long? = fix?.taken?.takeIf { fix.modified == modified }

// ---------- Exif bytes ----------

/** [text] as an Exif ASCII value of exactly [length] bytes, padded with NULs; null if it doesn't fit, so nothing moves. */
fun asciiBytes(text: String, length: Int): ByteArray? {
    val bytes = text.toByteArray(Charsets.US_ASCII)
    return if (bytes.size > length) null else bytes.copyOf(length)
}

/** A latitude or longitude as Exif keeps it: degrees, minutes and seconds as rationals, the seconds to a thousandth. */
fun gpsRationals(value: Double): LongArray {
    val thousandths = (abs(value) * 3_600_000).roundToLong()
    return longArrayOf(thousandths / 3_600_000, 1, thousandths / 60_000 % 60, 1, thousandths % 60_000, 1000)
}

fun gpsRef(value: Double, latitude: Boolean) = if (latitude) (if (value < 0) "S" else "N") else if (value < 0) "W" else "E"

/** Rationals as Exif bytes: each an unsigned 32-bit numerator, then denominator, in the file's byte [order]. */
fun rationalBytes(values: LongArray, order: ByteOrder): ByteArray =
    ByteBuffer.allocate(values.size * 4).order(order).apply { values.forEach { putInt(it.toInt()) } }.array()

fun rationalsOf(raw: ByteArray, order: ByteOrder): LongArray =
    ByteBuffer.wrap(raw).order(order).let { b -> LongArray(raw.size / 4) { b.int.toLong() and 0xFFFFFFFFL } }

/** Rationals the way ExifInterface writes them as text: "28/1,36/1,5032/100". */
fun rationalText(values: LongArray) = values.toList().chunked(2).joinToString(",") { (n, d) -> "$n/$d" }

/**
 * The byte order of [raw], a rational value ExifInterface reads as [text], or null when neither order or both give that
 * text (all zeros, say). Nothing is written in a guessed order.
 */
fun byteOrderOf(raw: ByteArray, text: String?): ByteOrder? =
    listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN).filter { text != null && rationalText(rationalsOf(raw, it)) == text.replace(" ", "") }.singleOrNull()

/** The point among [points] (lat, lng) nearest their middle: a real spot, never the sea between two beaches. */
fun middleOf(points: List<DoubleArray>): DoubleArray? {
    if (points.isEmpty()) return null
    val lat = points.sumOf { it[0] } / points.size
    val lng = points.sumOf { it[1] } / points.size
    return points.minBy { (it[0] - lat) * (it[0] - lat) + (it[1] - lng) * (it[1] - lng) }
}

/** A run of bytes in a file: where, what's there now, and what goes there. [reversed] puts it back. */
class Patch(val offset: Long, val old: ByteArray, val new: ByteArray) {
    fun reversed() = Patch(offset, new, old)
}

/** A tag's value bytes and where they sit in the file, or null when it's missing or isn't [length] bytes long. */
private fun slot(exif: ExifInterface, tag: String, length: Int? = null): Pair<Long, ByteArray>? {
    val range = exif.getAttributeRange(tag) ?: return null
    val bytes = exif.getAttributeBytes(tag) ?: return null
    return if (bytes.size.toLong() == range[1] && (length == null || bytes.size == length)) range[0] to bytes else null
}

/** Rational tags nearly every photo has; any one gives the byte order of the whole Exif block. */
private val ORDER_TAGS = listOf(
    ExifInterface.TAG_X_RESOLUTION, ExifInterface.TAG_Y_RESOLUTION, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH,
)

private fun byteOrder(exif: ExifInterface): ByteOrder? =
    ORDER_TAGS.firstNotNullOfOrNull { tag -> exif.getAttributeBytes(tag)?.let { byteOrderOf(it, exif.getAttribute(tag)) } }

/**
 * What moves a photo's own dates by [shift]: when it was taken (needed: without it MediaStore doesn't date the photo
 * from Exif at all), when it was digitised, and the GPS date and time, each where present. Null when there's nothing.
 */
private fun datePatches(exif: ExifInterface, shift: Long): List<Patch>? {
    val patches = ArrayList<Patch>()
    fun ascii(tag: String) {
        val (at, old) = slot(exif, tag) ?: return
        shiftedExif(exif.getAttribute(tag), shift)?.let { asciiBytes(it, old.size) }?.let { patches += Patch(at, old, it) }
    }
    ascii(ExifInterface.TAG_DATETIME_ORIGINAL)
    if (patches.isEmpty()) return null
    ascii(ExifInterface.TAG_DATETIME_DIGITIZED)
    val order = byteOrder(exif)
    val date = slot(exif, ExifInterface.TAG_GPS_DATESTAMP)
    val time = slot(exif, ExifInterface.TAG_GPS_TIMESTAMP, 24)
    if (order != null && date != null && time != null) {
        shiftedGpsStamp(exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP), rationalsOf(time.second, order), shift)?.let { (newDate, newTime) ->
            asciiBytes(newDate, date.second.size)?.let { patches += Patch(date.first, date.second, it) }
            patches += Patch(time.first, time.second, rationalBytes(newTime, order))
        }
    }
    return patches
}

/**
 * What puts [to] (lat, lng) into a photo's GPS, or takes its place out when [to] is null: zeroed, as Android does when it
 * hides a location, so no app reads one. Needs latitude and longitude with their N/S and E/W already there to overwrite.
 */
private fun placePatches(exif: ExifInterface, to: DoubleArray?): List<Patch>? {
    val lat = slot(exif, ExifInterface.TAG_GPS_LATITUDE, 24) ?: return null
    val latRef = slot(exif, ExifInterface.TAG_GPS_LATITUDE_REF, 2) ?: return null
    val lng = slot(exif, ExifInterface.TAG_GPS_LONGITUDE, 24) ?: return null
    val lngRef = slot(exif, ExifInterface.TAG_GPS_LONGITUDE_REF, 2) ?: return null
    if (to == null) return listOf(lat, latRef, lng, lngRef).map { (at, old) -> Patch(at, old, ByteArray(old.size)) }
    val order = byteOrder(exif) ?: return null
    return listOf(
        Patch(lat.first, lat.second, rationalBytes(gpsRationals(to[0]), order)),
        Patch(latRef.first, latRef.second, asciiBytes(gpsRef(to[0], latitude = true), 2)!!),
        Patch(lng.first, lng.second, rationalBytes(gpsRationals(to[1]), order)),
        Patch(lngRef.first, lngRef.second, asciiBytes(gpsRef(to[1], latitude = false), 2)!!),
    )
}

/**
 * Writes [patches] into [uri]'s file in place, then reads it back with [check]. Every spot must still hold the bytes
 * Album read there ([Patch.old]), or nothing is written. If the read-back isn't right, the old bytes go back. Returns the
 * file's new DATE_MODIFIED (its time in seconds), or throws with the file as it was.
 */
private fun writePatches(resolver: ContentResolver, uri: Uri, patches: List<Patch>, check: (ExifInterface) -> Boolean): Long {
    fun put(patches: List<Patch>): Long = resolver.openFileDescriptor(uri, "rw")!!.use { file ->
        val fd = file.fileDescriptor
        for (p in patches) {
            val there = ByteArray(p.old.size)
            check(Os.pread(fd, there, 0, there.size, p.offset) == there.size && there.contentEquals(p.old)) { "Exif isn't where it was read" }
        }
        val written = ArrayList<Patch>()
        try {
            for (p in patches) {
                check(Os.pwrite(fd, p.new, 0, p.new.size, p.offset) == p.new.size) { "short write" }
                written += p
            }
        } catch (e: Exception) {
            written.forEach { Os.pwrite(fd, it.old, 0, it.old.size, it.offset) } // a write that stopped part-way leaves no half
            Os.fsync(fd)
            throw e
        }
        Os.fsync(fd)
        Os.fstat(fd).st_mtime
    }
    val modified = put(patches)
    val ok = runCatching { resolver.openFileDescriptor(uri, "r")!!.use { check(ExifInterface(it.fileDescriptor)) } }.getOrDefault(false)
    if (!ok) {
        put(patches.map { it.reversed() }.reversed())
        error("Exif reads back wrong")
    }
    return modified
}

/** [uri] as Album must open it to see (and change) GPS: Android hides location otherwise, even from its write grant. */
private fun Context.original(uri: Uri) =
    if (checkSelfPermission(ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) MediaStore.setRequireOriginal(uri) else uri

/** MediaStore's DATE_MODIFIED (seconds) for [ids], for fixes kept in Album only. */
private fun modifiedOf(resolver: ContentResolver, ids: List<Long>): Map<Long, Long> = ids.chunked(500).flatMap { chunk ->
    resolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(FileColumns._ID, FileColumns.DATE_MODIFIED),
        "${FileColumns._ID} IN (${chunk.joinToString(",")})", null, null,
    )?.use { c -> List(c.count) { c.moveToNext(); c.getLong(0) to c.getLong(1) } }.orEmpty()
}.toMap()

/**
 * MediaStore rescans a file Album wrote once it's closed, and works its date out again from the Exif. Waits (a few
 * seconds at most) for that to happen to every file in [before] (uri to generation), so the date Album sets after it stays.
 */
private suspend fun awaitRescans(resolver: ContentResolver, before: Map<Uri, Long?>) {
    if (before.isEmpty()) return
    val waiting = before.toMutableMap()
    withTimeoutOrNull(3_000L + 10L * before.size) {
        Gallery.changes(resolver).conflate().first {
            waiting.entries.removeAll { (uri, generation) -> generationOf(resolver, uri) != generation }
            waiting.isEmpty()
        }
    }
}

/** One item's file change, for Undo: its uri as written and what was written. */
private class Written(val m: Media, val uri: Uri, val patches: List<Patch>)

/** "3 of their files can't hold it, so Album keeps it": what part of a fix of [of] items lives in Album only, or null when none does. */
fun albumOnly(n: Int, of: Int): String? = when {
    n <= 0 -> null
    n == of -> if (of == 1) "Its file can't hold it, so Album keeps it" else "Their files can't hold it, so Album keeps it"
    else -> "${"%,d".format(n)} of their files can't hold it, so Album keeps it"
}

/**
 * A change of place: to [place], at a spot Album finds in it; to [custom], a place the user pinned and named, at its pin;
 * or, with neither, the place taken away.
 */
class PlaceFix(val place: Place?, val custom: CustomPlace? = null)

/** One part of an edit, done: the items it changed, what to say, and its Undo (which returns what to say then). */
private class Fixed(val done: List<Media>, val text: String, val undo: suspend () -> String)

/** The date and place fixes, run as one album job: one Android pop-up for the batch, a progress card, Stop, and Undo. */
object FixDetails {
    /**
     * Edit details for [items] (at most [MAX_REQUEST]): moves their dates by [shift] millis (0 leaves them), then sets their
     * place to [place] (null leaves it). One job, so one pop-up covers both, and one Undo puts both back.
     */
    fun edit(context: Context, items: List<Media>, shift: Long, place: PlaceFix?) = AlbumOps.runJob {
        val dated = if (shift != 0L) dates(context, items, shift) ?: return@runJob null else null
        // Stopped part-way through the dates: the place is left for another go rather than set on only some
        val placed = if (place == null || (dated != null && AlbumOps.stopped)) null else {
            // The date's pop-up covered every file, and Android's grant lasts while Album runs
            places(context, dated?.done?.map { it.copy(taken = it.taken + shift) } ?: items, place, asked = dated != null)
        }
        val fixes = listOfNotNull(dated, placed)
        if (fixes.isEmpty()) return@runJob if (AlbumOps.stopped) AlbumOps.Message("Stopped. Nothing was changed.") else null
        AlbumOps.Message(fixes.joinToString(". ") { it.text }) {
            // The place goes back first: it was written last, over the file as the date left it
            AlbumOps.runJob { AlbumOps.Message(fixes.reversed().map { it.undo() }.joinToString(". ")) }
        }
    }

    /**
     * Moves [items] by [shift] millis: their own Exif dates where it can be overwritten, MediaStore's date for every one,
     * and Album's own record of it (see [MediaFix]). Null when Android's pop-up was turned down, or Stop came first.
     */
    private suspend fun dates(context: Context, items: List<Media>, shift: Long): Fixed? {
        val resolver = context.contentResolver
        CrashLog.note("fix dates ${items.size}")
        // DATE_TAKEN needs write access as much as the files do, so every item is in the one request
        if (!AlbumOps.ask(MediaStore.createWriteRequest(resolver, items.map { it.uri }))) return null
        val ids = items.map { it.id }
        val before = Collections.fixesOf(ids).associateBy { it.mediaId }
        val written = ArrayList<Written>()
        val modified = HashMap<Long, Long>()
        val generations = HashMap<Uri, Long?>()
        val done = ArrayList<Media>()
        for ((i, m) in items.withIndex()) {
            if (AlbumOps.stopped) break
            AlbumOps.progress.value = AlbumOps.Progress("Changing", i + 1, items.size)
            val uri = context.original(m.uri)
            if (resolver.getType(m.uri) in IN_PLACE) {
                runCatching {
                    val patches = resolver.openFileDescriptor(uri, "r")!!.use { datePatches(ExifInterface(it.fileDescriptor), shift) } ?: return@runCatching
                    val expected = patches.first().new.toString(Charsets.US_ASCII).trimEnd('\u0000')
                    val generation = generationOf(resolver, m.uri)
                    modified[m.id] = writePatches(resolver, uri, patches) { it.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == expected }
                    generations[m.uri] = generation
                    written += Written(m, uri, patches)
                }.onFailure { CrashLog.note("date patch failed: $it") }
            }
            done += m
        }
        if (done.isEmpty()) return null
        awaitRescans(resolver, generations)
        modified.putAll(modifiedOf(resolver, done.filter { it.id !in modified }.map { it.id }))
        setTaken(resolver, done.associateWith { it.taken + shift })
        Collections.putFixes(done.map { m -> (before[m.id] ?: MediaFix(m.id, 0)).copy(modified = modified[m.id] ?: 0, taken = m.taken + shift) })
        val what = if (done.size < items.size) "${done.size} of ${itemCount(items.size)}" else itemCount(done.size)
        val text = listOfNotNull("Changed the date of $what", albumOnly(done.size - written.size, done.size)).joinToString(". ")
        return Fixed(done, text) { undo(context, "date", done, written, before) { setTaken(resolver, done.associateWith { it.taken }) } }
    }

    /**
     * Sets the place of [items] as [fix] says. In the files it's the custom place's pin, or a spot in the place where Album
     * knows one (from photos taken there, see [middleOf]). Files with GPS to overwrite (JPEG and WebP) change in place,
     * after one Android pop-up for just those (none when [asked] already covered them); the rest keep the fix in Album.
     * The smart index takes it at once, so Places and search update. Null when nothing changed.
     */
    private suspend fun places(context: Context, items: List<Media>, fix: PlaceFix, asked: Boolean): Fixed? {
        val resolver = context.contentResolver
        val custom = fix.custom
        CrashLog.note(if (custom == null && fix.place == null) "remove place ${items.size}" else "fix place ${items.size}") // never which place
        // A pinned place is named by GeoNames' state and country around its pin
        val to = custom?.let { namedPlace(geoPlaceAt(context, it.lat, it.lng), it) } ?: fix.place
        val point = custom?.let { doubleArrayOf(it.lat, it.lng) } ?: to?.let { SmartIndex.spotIn(it) }
        val canLocate = context.checkSelfPermission(ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        // Which files can take it is known before asking, so the pop-up never comes up for files Album then leaves alone
        val plans = HashMap<Long, List<Patch>>()
        var located = 0 // files that have a location of their own, to say when one stays in a file Album can't change
        if (canLocate && (to == null || point != null)) {
            for ((i, m) in items.withIndex()) {
                if (AlbumOps.stopped) return null
                AlbumOps.progress.value = AlbumOps.Progress("Checking", i + 1, items.size, "Nothing has changed yet.")
                if (m.isVideo) continue
                runCatching {
                    resolver.openFileDescriptor(context.original(m.uri), "r")!!.use { fd ->
                        val exif = ExifInterface(fd.fileDescriptor)
                        if (exif.latLong.let { it != null && (it[0] != 0.0 || it[1] != 0.0) }) located++
                        if (resolver.getType(m.uri) in IN_PLACE) placePatches(exif, point)?.let { plans[m.id] = it }
                    }
                }
            }
        }
        val inFile = items.filter { it.id in plans }
        if (inFile.isNotEmpty() && !asked && !AlbumOps.ask(MediaStore.createWriteRequest(resolver, inFile.map { it.uri }))) return null
        val ids = items.map { it.id }
        val before = Collections.fixesOf(ids).associateBy { it.mediaId }
        val spots = SmartIndex.spotsOf(ids)
        val written = ArrayList<Written>()
        val modified = HashMap<Long, Long>()
        val generations = inFile.associate { it.uri to generationOf(resolver, it.uri) }
        for ((i, m) in inFile.withIndex()) {
            if (AlbumOps.stopped) break
            AlbumOps.progress.value = AlbumOps.Progress("Changing", i + 1, inFile.size)
            val uri = context.original(m.uri)
            runCatching {
                modified[m.id] = writePatches(resolver, uri, plans.getValue(m.id)) { exif ->
                    val now = exif.latLong?.takeIf { it[0] != 0.0 || it[1] != 0.0 }
                    if (point == null) now == null else now != null && abs(now[0] - point[0]) < 1e-5 && abs(now[1] - point[1]) < 1e-5
                }
                written += Written(m, uri, plans.getValue(m.id))
            }.onFailure { CrashLog.note("place patch failed: $it") }
        }
        // A file Stop left out keeps its place; one whose patch failed gets the fix in Album like any other
        val done = if (AlbumOps.stopped) items.filter { it.id !in plans || it.id in modified } else items
        if (done.isEmpty()) return null
        // The rescan after a write works the date out again from the file; put back the one Album shows
        awaitRescans(resolver, generations.filterKeys { uri -> written.any { it.m.uri == uri } })
        setTaken(resolver, written.associate { it.m to it.m.taken })
        modified.putAll(modifiedOf(resolver, done.filter { it.id !in modified }.map { it.id }))
        val fixes = done.map { m ->
            (before[m.id] ?: MediaFix(m.id, 0)).copy(modified = modified[m.id] ?: 0, placeFixed = true, lat = point?.get(0), lng = point?.get(1), place = to)
        }
        Collections.putFixes(fixes)
        SmartIndex.applyFixes(fixes)
        val what = if (done.size < items.size) "${done.size} of ${itemCount(items.size)}" else itemCount(done.size)
        val text = if (to == null) {
            val stuck = (located - written.size).coerceAtLeast(0)
            listOfNotNull(
                "Removed the place from $what",
                stuck.takeIf { it > 0 }?.let { "${if (it == 1) "1 file still holds it" else "$it files still hold it"}, but Album leaves it out" },
            ).joinToString(". ")
        } else {
            listOfNotNull("Set the place of $what to ${to.city}", albumOnly(done.size - written.size, done.size)).joinToString(". ")
        }
        return Fixed(done, text) {
            undo(context, "place", done, written, before) {
                SmartIndex.restoreSpots(spots)
                setTaken(resolver, written.associate { it.m to it.m.taken })
            }
        }
    }

    /** GeoNames' place at a point, from the 236k places in assets (a moment to read, so only for a pinned place). */
    private fun geoPlaceAt(context: Context, lat: Double, lng: Double): Place? =
        context.assets.open("geonames/places.tsv").bufferedReader().useLines { Places.parse(it) }.at(lat, lng)

    /** MediaStore's date for each item. Best effort per item: Album's own record already has the fix. */
    private fun setTaken(resolver: ContentResolver, taken: Map<Media, Long>) = taken.forEach { (m, t) ->
        runCatching { resolver.update(m.uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, t) }, null, null) }
            .onFailure { CrashLog.note("date taken update failed: $it") }
    }

    /**
     * Undo for a fix: the files' old bytes go back (Android's grant from the fix lasts while Album runs, so no pop-up),
     * then [also] (MediaStore's dates, or the index's places), then Album's records as they were before.
     */
    private suspend fun undo(context: Context, what: String, done: List<Media>, written: List<Written>, before: Map<Long, MediaFix>, also: suspend () -> Unit): String {
        val resolver = context.contentResolver
        CrashLog.note("undo $what ${done.size}")
        val generations = written.associate { it.m.uri to generationOf(resolver, it.m.uri) }
        val modified = HashMap<Long, Long>()
        var failed = 0
        for ((i, w) in written.withIndex()) {
            AlbumOps.progress.value = AlbumOps.Progress("Putting back", i + 1, written.size)
            val old = w.patches.first().old
            runCatching {
                modified[w.m.id] = writePatches(resolver, w.uri, w.patches.map { it.reversed() }.reversed()) { exif ->
                    // The first patch is the date taken (dates) or the latitude (places): it must read as before
                    exif.getAttributeBytes(if (what == "date") ExifInterface.TAG_DATETIME_ORIGINAL else ExifInterface.TAG_GPS_LATITUDE)?.contentEquals(old) == true
                }
            }.onFailure {
                CrashLog.note("undo patch failed: $it")
                failed++
            }
        }
        awaitRescans(resolver, generations.filterKeys { uri -> written.any { it.m.uri == uri && it.m.id in modified } })
        also()
        // A row kept from before stays tied to the file as it is now, which is as it was then
        Collections.restoreFixes(done.map { it.id }, before.values.map { f -> modified[f.mediaId]?.let { f.copy(modified = it) } ?: f })
        val text = "Put back the ${what}s of ${itemCount(done.size)}"
        return if (failed > 0) "$text. $failed of their files couldn't be put back" else text
    }
}

// ---------- Sheets ----------

private enum class DateMode { Set, Shift }

private enum class Picking { None, Date, Time, Place, Map, Name }

/** The place chosen in Edit details, as a saveable key: [REMOVE], [CUSTOM] and a name, or a place's [placeKey]. */
private const val REMOVE = "remove"
private const val CUSTOM = "custom:"

/**
 * Edit details for [items], one or many: their date and time, and their place, with one Save. The date is set for the
 * first (the others keep their order and spacing after it), or all are shifted by days, hours and minutes, earlier or
 * later; one item opens on Set, many on Shift, and with no change the dates stay. The place comes from Album's places,
 * the ones the user named, or a new one pinned on the map. [onApply] gets the shift in millis (0 for none) and the place
 * (null to leave it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDetailsSheet(items: List<Media>, onDismiss: () -> Unit, onApply: (Long, PlaceFix?) -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val first = remember(items) { items.minOf { it.taken } }
    val last = remember(items) { items.maxOf { it.taken } }
    val clock = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val day = remember { DateTimeFormatter.ofPattern("EEE, d MMM yyyy") }
    fun whenText(millis: Long) = "${Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(day)}, ${clock.format(Date(millis))}"

    var mode by rememberSaveable { mutableStateOf(if (items.size == 1) DateMode.Set else DateMode.Shift) }
    var picking by rememberSaveable { mutableStateOf(Picking.None) }
    val start = remember(first) { Instant.ofEpochMilli(first).atZone(zone).toLocalDateTime() }
    var date by rememberSaveable { mutableStateOf(start.toLocalDate().toEpochDay()) }
    var time by rememberSaveable { mutableStateOf(start.toLocalTime().toSecondOfDay()) }
    var later by rememberSaveable { mutableStateOf(true) }
    var days by rememberSaveable { mutableStateOf("") }
    var hours by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) } // null: the place stays
    var query by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }

    val places = Explore.places.collectAsState().value.orEmpty()
    val custom by remember(context) { CustomPlaces.load(context) }.collectAsState()
    val placeFix: PlaceFix? = chosen?.let { key ->
        when {
            key == REMOVE -> PlaceFix(null)
            key.startsWith(CUSTOM) -> custom.find { it.name == key.removePrefix(CUSTOM) }?.let { PlaceFix(null, it) }
            else -> places.find { placeKey(it.key) == key }?.let { PlaceFix(it.key) }
        }
    }
    val placeName = when {
        chosen == REMOVE -> "Removed"
        placeFix?.custom != null -> placeFix.custom.name
        placeFix?.place != null -> placeNames(places.map { it.key }).getValue(placeFix.place)
        else -> "Stays as it is"
    }

    when (picking) {
        Picking.Date -> {
            // Material's date picker works in UTC days, so the day goes in and comes out as UTC midnight
            val state = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            Sheet({ picking = Picking.None }) {
                DatePicker(
                    state,
                    colors = DatePickerDefaults.colors(
                        containerColor = c.surface, titleContentColor = c.inkMuted, headlineContentColor = c.ink, weekdayContentColor = c.inkMuted,
                        navigationContentColor = c.ink, yearContentColor = c.ink, currentYearContentColor = c.flashInk,
                        selectedYearContainerColor = c.flash, selectedYearContentColor = c.onFlash, dayContentColor = c.ink,
                        selectedDayContainerColor = c.flash, selectedDayContentColor = c.onFlash, todayContentColor = c.flashInk,
                        todayDateBorderColor = c.flashInk, dividerColor = c.line,
                    ),
                )
                PickerButtons({ picking = Picking.None }) {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    picking = Picking.None
                }
            }
            return
        }
        Picking.Time -> {
            val now = LocalTime.ofSecondOfDay(time.toLong())
            val state = rememberTimePickerState(now.hour, now.minute, android.text.format.DateFormat.is24HourFormat(context))
            Sheet({ picking = Picking.None }, "Time") {
                TimeInput(
                    state,
                    Modifier.padding(vertical = Space.s4),
                    colors = TimePickerDefaults.colors(
                        timeSelectorSelectedContainerColor = c.flashSoft, timeSelectorSelectedContentColor = c.ink,
                        timeSelectorUnselectedContainerColor = c.surfaceHigh, timeSelectorUnselectedContentColor = c.ink,
                        periodSelectorSelectedContainerColor = c.flashSoft, periodSelectorSelectedContentColor = c.ink,
                        periodSelectorUnselectedContainerColor = c.surface, periodSelectorUnselectedContentColor = c.inkMuted,
                        periodSelectorBorderColor = c.lineStrong,
                    ),
                )
                PickerButtons({ picking = Picking.None }) {
                    // Seconds stay as the first photo had them, so a time set to the minute moves it only as far as asked
                    time = LocalTime.of(state.hour, state.minute, start.second).toSecondOfDay()
                    picking = Picking.None
                }
            }
            return
        }
        Picking.Place -> {
            PlaceSearch(
                items.size, places, custom, query, { query = it },
                onDismiss = { picking = Picking.None },
                onPick = { key ->
                    chosen = key
                    picking = Picking.None
                },
                onAdd = {
                    name = customPlaceName(query).let { TextFieldValue(it, TextRange(it.length)) } // what they searched for is likely its name
                    picking = Picking.Map
                },
            )
            return
        }
        Picking.Map -> {
            // The photos' own spot, when one has GPS: naming where they were taken is the usual case
            val focus by produceState<MapFocus?>(null, items) {
                value = SmartIndex.spotsOf(items.take(100).map { it.id }).firstNotNullOfOrNull { s ->
                    if (s.lat != null && s.lng != null) MapFocus(s.id, s.lat, s.lng) else null
                }
            }
            PinDialog(focus, onBack = { picking = Picking.Place }) { lat, lng ->
                pin = doubleArrayOf(lat, lng)
                picking = Picking.Name
            }
            return
        }
        Picking.Name -> {
            val text = customPlaceName(name.text)
            NameSheet(
                "Name this place", name, { name = it }, "Save place", enabled = text.isNotEmpty(),
                onDismiss = { picking = Picking.Map },
                onConfirm = {
                    val at = pin
                    if (text.isNotEmpty() && at != null) {
                        CustomPlaces.add(context, CustomPlace(text, at[0], at[1]))
                        chosen = CUSTOM + text
                        picking = Picking.None
                    }
                },
                hint = "Saved on this phone. Photos taken here are called by this name.",
                placeholder = "Nani's house",
                capitalization = KeyboardCapitalization.Words,
            )
            return
        }
        Picking.None -> {}
    }

    fun number(text: String) = text.toIntOrNull()?.coerceIn(0, 100_000) ?: 0
    val shift = when (mode) {
        DateMode.Set -> shiftToSet(first, LocalDate.ofEpochDay(date), LocalTime.ofSecondOfDay(time.toLong()), zone)
        DateMode.Shift -> shiftOf(number(days), number(hours), number(minutes), later)
    }.takeIf { abs(it) >= 1000 } ?: 0L // the picker works to the second: what's left is the photo's own milliseconds
    val many = items.size > 1
    Sheet(onDismiss, "Edit details") {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            if (many) {
                Text("${itemCount(items.size)} · ${whenText(first)} to ${whenText(last)}", style = AlbumTheme.type.caption, color = c.inkMuted)
            } else {
                // Its date and time show in the rows below
                Text(
                    "For photos saved with the wrong date or place, like WhatsApp downloads. The file is updated on the phone.",
                    style = AlbumTheme.type.body, color = c.inkMuted,
                )
            }
            Text("Date and time", style = AlbumTheme.type.label, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Chip("Set date and time", { mode = DateMode.Set }, selected = mode == DateMode.Set)
                Chip("Shift", { mode = DateMode.Shift }, selected = mode == DateMode.Shift)
            }
            when (mode) {
                DateMode.Set -> {
                    val target = LocalDate.ofEpochDay(date).atTime(LocalTime.ofSecondOfDay(time.toLong())).atZone(zone).toInstant().toEpochMilli()
                    MenuRow(Sym.CalendarMonth, "Date", { picking = Picking.Date }, sub = LocalDate.ofEpochDay(date).format(day))
                    MenuRow(Sym.Schedule, "Time", { picking = Picking.Time }, sub = clock.format(Date(target)))
                    if (many) Text("The first one gets this date and time. The rest keep their order and the time between them.", style = AlbumTheme.type.caption, color = c.inkMuted)
                }
                DateMode.Shift -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        Chip("Later", { later = true }, selected = later)
                        Chip("Earlier", { later = false }, selected = !later)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        NumberField("Days", days, { days = it }, Modifier.weight(1f))
                        NumberField("Hours", hours, { hours = it }, Modifier.weight(1f))
                        NumberField("Minutes", minutes, { minutes = it }, Modifier.weight(1f))
                    }
                }
            }
            Text(
                if (shift == 0L) "The date stays as it is" else "${if (many) "Moves them" else "Moves it"} ${shiftText(shift).replaceFirstChar { it.lowercase() }}",
                style = AlbumTheme.type.body, color = c.inkMuted,
            )
            Text("Place", style = AlbumTheme.type.label, color = c.ink)
            MenuRow(if (chosen == REMOVE) Sym.LocationOff else Sym.LocationOn, "Place", { picking = Picking.Place }, sub = placeName)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                AlbumButton("Cancel", onDismiss, Modifier.weight(1f).heightIn(min = 56.dp))
                AlbumButton(
                    "Save",
                    { if (shift != 0L || placeFix != null) onApply(shift, placeFix) },
                    Modifier.weight(1.5f).heightIn(min = 56.dp), ButtonVariant.Primary, enabled = shift != 0L || placeFix != null,
                )
            }
        }
    }
}

@Composable
private fun PickerButtons(onCancel: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        AlbumButton("Cancel", onCancel, Modifier.weight(1f))
        AlbumButton("Done", onDone, Modifier.weight(1.5f), ButtonVariant.Primary)
    }
}

/** A short number field in the design's field style: digits only. */
@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value,
        { text -> onValue(text.filter(Char::isDigit).take(6)) },
        modifier,
        textStyle = AlbumTheme.type.body,
        label = { Text(label) },
        placeholder = { Text("0") },
        singleLine = true,
        shape = RoundedCornerShape(Space.radiusMd),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        colors = albumFieldColors(),
    )
}

/** A place name made comparable: lower case, accents gone, so "mumbai" finds Mumbai and "kochi" finds Kochi. */
private fun plain(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

/**
 * The place for [count] items: the ones the user named ([custom]) first, then Album's places (from photos it has located),
 * searchable by name, town, state or country, biggest first; Remove place; and at the end Add a new place, which pins one
 * on the map ([onAdd]). [onPick] gets the choice's key (see [REMOVE] and [CUSTOM]).
 */
@Composable
private fun PlaceSearch(
    count: Int,
    places: List<Group<Place, Media>>,
    custom: List<CustomPlace>,
    query: String,
    onQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val c = AlbumTheme.colors
    val names = remember(places) { placeNames(places.map { it.key }) }
    val q = plain(query.trim())
    val mine = remember(custom, q) { custom.filter { q.isEmpty() || plain(it.name).contains(q) }.sortedBy { plain(it.name) } }
    val shown = remember(places, custom, q) {
        // A named place's photos make a place of the same name: it's listed once, as the one they named
        val named = custom.mapTo(HashSet()) { it.name }
        places.filter { p -> p.key.city !in named && (q.isEmpty() || listOf(p.key.city, p.key.admin1, p.key.country).any { plain(it).contains(q) }) }
    }
    Sheet(onDismiss, "Place") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text(itemCount(count), style = AlbumTheme.type.caption, color = c.inkMuted)
            OutlinedTextField(
                query,
                onQuery,
                Modifier.fillMaxWidth(),
                textStyle = AlbumTheme.type.body,
                placeholder = { Text("Search your places") },
                leadingIcon = { Icon(Sym.Search, null, tint = c.inkMuted) },
                singleLine = true,
                shape = RoundedCornerShape(Space.radiusMd),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = albumFieldColors(),
            )
            LazyColumn {
                if (q.isEmpty()) {
                    item(key = "remove") {
                        MenuRow(Sym.LocationOff, "Remove place", { onPick(REMOVE) }, sub = "Album stops showing where they were taken", trailing = null)
                    }
                }
                items(mine, key = { CUSTOM + it.name }) { p ->
                    MenuRow(Sym.Home, p.name, { onPick(CUSTOM + p.name) }, sub = "Named by you", trailing = null)
                }
                items(shown, key = { placeKey(it.key) }) { p ->
                    val where = listOf(p.key.admin1, p.key.country).filter { it.isNotEmpty() && it != p.key.city }.distinct().joinToString(", ")
                    MenuRow(Sym.LocationOn, names.getValue(p.key), { onPick(placeKey(p.key)) }, sub = listOf(where, itemCount(p.items.size)).filter { it.isNotEmpty() }.joinToString(" · "), trailing = null)
                }
                if (shown.isEmpty() && mine.isEmpty()) {
                    item(key = "none") {
                        Text(
                            if (places.isEmpty() && custom.isEmpty()) "Places show up here once Album has found photos with a location." else "No place called $query in your photos.",
                            Modifier.padding(vertical = Space.s4), style = AlbumTheme.type.caption, color = c.inkMuted,
                        )
                    }
                }
                item(key = "add") {
                    MenuRow(Sym.Add, "Add a new place", onAdd, sub = "Drop a pin on the map and name it")
                }
            }
        }
    }
}

/**
 * The offline map in its own window over everything (the viewer included), to drop a pin for a new place. Opens on
 * [focus] when the photos have a spot, else on all the photos. [onPick] gets the pin's latitude and longitude.
 */
@Composable
private fun PinDialog(focus: MapFocus?, onBack: () -> Unit, onPick: (Double, Double) -> Unit) {
    Dialog(onBack, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The map draws its own background; the window adds no dim or motion
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { w ->
            SideEffect {
                w.setDimAmount(0f)
                w.setWindowAnimations(0)
            }
        }
        key(focus) { MapScreen(onBack, onOpen = {}, focus = focus, onPick = onPick) } // opens again on the spot once it's found
    }
}
