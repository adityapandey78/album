package com.aditya.album

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.system.Os
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// Local backup: copies the Library's photos and videos to a folder the user picks on an SD card, a USB drive or this
// phone, keeping their folders and file names. It only ever adds to the backup: nothing on the drive is overwritten or
// deleted (bar its own unfinished copies), and the originals are only read. Nothing goes online.

private const val JOB = "Backup" // on the Organising card
private const val FOLDER = "Album backup" // made inside the chosen folder, so the backup keeps apart from the drive's other files
private const val PARTIAL = ".album-partial" // a copy still being written; it gets its real name once it's checked
private const val RESERVE = 100L shl 20 // left free on the drive, as the safe leaves on the phone
private const val FAT32_MAX = (1L shl 32) - 1 // the biggest file a FAT32 drive holds; most USB drives up to 32 GB come as FAT32
private const val PROVIDER = "com.android.externalstorage.documents" // Android's own storage: this phone, SD cards and USB drives
private const val AUTO = "auto"
private const val NOW = "backup-now" // Back up now
private const val PLUGGED = "backup" // a drive plugged in
private const val DAILY = "backup-daily" // once a day while charging
private const val CHANNEL = "backup"
private const val NOTIFICATION_ID = 1002 // its own, so this and the organising notification never replace each other
private val DOC_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)

/** A photo or video as the backup sees it. [volume] is MediaStore's VOLUME_NAME, [path] its RELATIVE_PATH. */
data class BackupSource(val id: Long, val isVideo: Boolean, val size: Long, val modified: Long, val volume: String, val path: String, val name: String)

/** What to back up: [albums] null for every album, new ones too, else these [folderKey]s; [hidden] for Hidden items too. */
data class BackupChoice(val albums: Set<String>? = null, val hidden: Boolean = false)

/** Why the last backup stopped before the end. Finishing, or the user's Stop, leaves none. */
enum class BackupStop { Unplugged, Full }

/** The backup's place and choices, kept in the "backup" prefs. */
data class BackupSettings(
    val tree: String? = null, // the folder picked in Android's folder picker, as a tree uri; null until one is
    val folder: String? = null, // document id of the "Album backup" folder in it
    val drive: String = "", // as Android names it: "SanDisk USB drive", "SD card", "Internal storage"
    val volume: String = "", // MediaStore's name for that drive, to leave the backup's own copies out
    val onPhone: Boolean = false, // the folder is on this phone's own storage
    val auto: Boolean = false,
    val choice: BackupChoice = BackupChoice(),
    val last: Long = 0, // when a backup last finished with everything copied, epoch millis; 0 for never
    val failed: Int = 0, // items the last backup couldn't copy
    val tooBig: Int = 0, // ...of which were over FAT32's 4 GB
    val stop: BackupStop? = null,
)

/** Whether the backup's drive is there to write to: [Missing] when it's unplugged, [Lost] when Album can't open its folder any more. */
enum class Reach { Ready, Missing, Lost }

/** The drive as the Backup page shows it. [free] in bytes, null when it can't be read. */
data class Drive(val reach: Reach, val free: Long?)

/** Of the [chosen] items, [items] aren't backed up yet (newest first, as the Library has them). */
class Pending(val chosen: Int, val items: List<Media>) {
    val bytes = items.sumOf { it.size }
}

/**
 * Whether [item] is one to back up: in a chosen album, or Hidden items when those are chosen; never one of the backup's
 * own copies, should MediaStore list the backup folder ([ownVolume], [ownPath]: "" for none, or for a whole drive).
 */
fun backsUp(item: BackupSource, choice: BackupChoice, h: HiddenState, ownVolume: String, ownPath: String): Boolean = when {
    item.volume == ownVolume && item.path.startsWith(ownPath, ignoreCase = true) -> false
    h.hides(item.id, item.path) -> choice.hidden
    else -> choice.albums == null || folderKey(item.path) in choice.albums
}

/** RELATIVE_PATH of a backup folder from its document id ("1234-ABCD:Backups/Album backup" is "Backups/Album backup/"). */
fun ownPath(folderId: String): String = folderId.substringAfter(':', "").trim('/').let { if (it.isEmpty()) "" else "$it/" }

/**
 * What to copy, in [found]'s order: items without a copy, and items changed since theirs, each with that copy's stamp.
 * An edited photo gets a new copy alongside the old; one only touched (same bytes) is matched by its hash and kept.
 */
fun planBackup(found: List<BackupSource>, copies: List<CopyStamp>): List<Pair<BackupSource, CopyStamp?>> {
    val known = copies.associateBy { it.mediaId }
    return found.mapNotNull { item ->
        val copy = known[item.id]
        if (copy != null && copy.size == item.size && copy.modified == item.modified) null else item to copy
    }
}

/**
 * [name] as FAT and exFAT drives take it: Android's storage swaps the characters they refuse for "_" when it makes the
 * file, so doing the same first lets the next backup find the copy by its name. Dot names can't climb out of the folder.
 */
fun fatName(name: String): String = when (name) {
    "", ".", ".." -> "_"
    else -> name.map { if (it.code < 0x20 || it.code == 0x7F || it in "\"*/:<>?\\|") '_' else it }.joinToString("")
}

/** The folders a copy goes in, inside the backup folder: RELATIVE_PATH "DCIM/Camera/" is ["DCIM", "Camera"]. */
fun backupDirs(relativePath: String): List<String> = relativePath.split('/').filter { it.isNotEmpty() }.map(::fatName)

/**
 * [name], then the names Android and [freeNames] give it on a clash: "IMG_1.jpg", "IMG_1 (1).jpg", "IMG_1 (2).jpg"… The
 * first ones a folder has are where an earlier copy may be; the first one it hasn't is free for a new copy.
 */
fun clashNames(name: String): Sequence<String> {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    return generateSequence(0) { it + 1 }.map { n -> if (n == 0) name else "${name.take(dot)} ($n)${name.drop(dot)}" }
}

private val shortDay = DateTimeFormatter.ofPattern("EEE, d MMM")
private val oldDay = DateTimeFormatter.ofPattern("d MMM yyyy")

/** When a backup finished: "today at 21:04", "yesterday at 21:04", "Sat, 24 Oct", "24 Oct 2025". [time] formats a clock time. */
fun backedUpWhen(at: Long, now: Long, zone: ZoneId, time: (Long) -> String): String {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        day == today -> "today at ${time(at)}"
        day == today.minusDays(1) -> "yesterday at ${time(at)}"
        day.year == today.year -> day.format(shortDay)
        else -> day.format(oldDay)
    }
}

/** Hex SHA-256 of what [input] gives, writing each chunk to [out] on the way, and how many bytes that was. */
internal suspend fun copyHashing(input: java.io.InputStream, out: java.io.OutputStream): Pair<String, Long> {
    val sha = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 20) // 1 MB chunks, as AlbumOps copies
    var size = 0L
    while (true) {
        currentCoroutineContext().ensureActive() // a stopped backup doesn't finish a 4 GB video first
        val n = input.read(buffer)
        if (n < 0) return sha.digest().hex() to size
        out.write(buffer, 0, n)
        sha.update(buffer, 0, n)
        size += n
    }
}

/**
 * Backs the Library up to a folder on a drive. Read side for the Backup page: [settings], [progress], [drive] and
 * [pending]. Copies run as WorkManager work ([BackupWorker]), one at a time, and carry on after restarts.
 */
object Backup {
    private lateinit var app: Context
    private val dao by lazy {
        Room.databaseBuilder(app, BackupDb::class.java, "backup.db")
            .fallbackToDestructiveMigration(true) // a cache of what's on the drive: dropping it costs a re-check, never a second copy
            .build()
            .dao()
    }
    private val prefs by lazy { app.getSharedPreferences("backup", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()
    @Volatile private var stopping = false

    private val _settings = MutableStateFlow<BackupSettings?>(null)

    /** Null until loaded from disk, a moment after start. */
    val settings: StateFlow<BackupSettings?> = _settings.asStateFlow()

    private val _progress = MutableStateFlow<IndexProgress?>(null)

    /** How far a backup running now has got, over everything chosen, so a resumed one shows where it is. Null when none runs. */
    val progress: StateFlow<IndexProgress?> = _progress.asStateFlow()

    private val _volumes = MutableStateFlow(0)

    /** Goes up whenever a drive is plugged in or out, so the Backup page looks again. */
    val volumes: StateFlow<Int> = _volumes.asStateFlow()

    /** Call once from Application.onCreate. Carries on a backup that unplugging the drive stopped, once it's back. */
    fun start(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        scope.launch {
            val s = load()
            _settings.value = s
            schedule(s)
            if (s.stop == BackupStop.Unplugged) mounted(app) // the drive may have come back while Album wasn't running
        }
        // Plugged in or out while Album runs. No permission needed; see BackupMountReceiver for while it doesn't.
        app.getSystemService(StorageManager::class.java).registerStorageVolumeCallback(
            app.mainExecutor,
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    _volumes.update { it + 1 }
                    if (volume.state == Environment.MEDIA_MOUNTED) mounted(app)
                }
            },
        )
    }

    /**
     * A drive was plugged in, or the SD card mounted: a backup starts if it's automatic or one was waiting for the drive
     * (the worker decides, once it knows the settings), when the battery isn't low. Not when charging: most phones can't
     * charge while they power a USB drive.
     */
    fun mounted(context: Context) {
        start(context)
        enqueue(PLUGGED, auto = true, Constraints.Builder().setRequiresBatteryNotLow(true).build())
    }

    /** Back up now: at once, and in the foreground while Album is on screen, so it carries on if Album is closed. */
    fun now() {
        CrashLog.note("backup now")
        enqueue(NOW, auto = false, Constraints.NONE)
    }

    /** Stops a running backup after the item in hand. Everything copied so far stays copied. */
    fun stop() {
        CrashLog.note("backup stop")
        stopping = true
    }

    private fun enqueue(name: String, auto: Boolean, constraints: Constraints) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints).setInputData(workDataOf(AUTO to auto)).build()
        WorkManager.getInstance(app).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    // Once a day while charging: for an SD card or a folder on the phone, which stay connected. KEEP, so turning it on
    // again doesn't push the next run back a day.
    private fun schedule(s: BackupSettings) {
        val work = WorkManager.getInstance(app)
        if (s.tree != null && s.auto) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf(AUTO to true))
                .build()
            work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            work.cancelUniqueWork(DAILY)
        }
    }

    fun setAuto(on: Boolean) {
        CrashLog.note("backup auto $on")
        schedule(update { it.copy(auto = on) })
    }

    /** Which albums to back up: null for all. */
    fun setAlbums(albums: Set<String>?) {
        update { it.copy(choice = it.choice.copy(albums = albums)) }
    }

    /** Hidden items too, or not. Only while privacy is unlocked (see [rememberPrivacyAsk]): that's who may decide it. */
    fun setHidden(on: Boolean) {
        if (!PrivacyLock.isOpen) return
        update { it.copy(choice = it.choice.copy(hidden = on)) }
    }

    /**
     * Makes [tree], from Android's folder picker, the place to back up to: keeps Album's access to it across restarts and
     * makes the "Album backup" folder in it. Returns why it can't be used, or null. Blocking: call off the main thread.
     */
    fun choose(tree: Uri): String? {
        if (tree.authority != PROVIDER) return "Choose a folder on this phone, an SD card or a USB drive"
        val volume = volumeOf(tree) ?: return "Album can't find that drive. Plug it in again, then choose the folder."
        val resolver = app.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            resolver.takePersistableUriPermission(tree, flags)
            val folder = folderIn(tree)
            marker(tree, folder)
            val old = settings.value?.tree
            val same = old == tree.toString()
            if (!same) stopping = true // a backup running to the old place ends there; the next goes to the new one
            CrashLog.note("backup: chose a folder (${if (volume.isPrimary) "phone" else "drive"})")
            schedule(
                update {
                    // Last backed up and how it went are about the drive; a new one starts afresh
                    val fresh = if (same) it else it.copy(last = 0, failed = 0, tooBig = 0, stop = null)
                    fresh.copy(
                        tree = tree.toString(), folder = folder, drive = volume.getDescription(app), volume = volume.mediaStoreVolumeName.orEmpty(),
                        onPhone = volume.isPrimary,
                    )
                },
            )
            if (old != null && !same) runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), flags) }
            null
        } catch (e: Exception) {
            CrashLog.note("backup: can't use that folder: ${e.javaClass.simpleName}")
            "Album can't write to that folder. Choose another."
        }
    }

    /** Whether the drive is there and how much room it has. Blocking: call off the main thread. Null before a folder is chosen. */
    fun drive(): Drive? {
        val s = settings.value ?: return null
        val tree = s.tree?.let(Uri::parse) ?: return null
        val reach = reach(tree)
        val free = if (reach == Reach.Ready) runCatching { freeBytes(tree, checkNotNull(s.folder)) }.getOrNull() else null
        return Drive(reach, free)
    }

    /** The chosen items not backed up yet, as [media] (the Library) has them. Null before a folder is chosen. */
    suspend fun pending(media: List<Media>, h: HiddenState): Pending? = withContext(Dispatchers.IO) {
        val s = settings.value ?: return@withContext null
        val tree = s.tree ?: return@withContext null
        val found = query() ?: return@withContext null
        val chosen = found.filter { backsUp(it, s.choice, h, s.volume, s.folder?.let(::ownPath).orEmpty()) }
        val ids = planBackup(chosen, dao.stamps(tree)).mapTo(HashSet()) { it.first.id }
        Pending(chosen.size, media.filter { it.id in ids })
    }

    /** One backup run, as [BackupWorker] calls it. [auto]: only if backing up is automatic, or a backup waits for the drive. */
    internal suspend fun run(auto: Boolean, onLongPass: suspend () -> Unit = {}) = withContext(Dispatchers.IO) {
        val s = settings.filterNotNull().first()
        val tree = s.tree?.let(Uri::parse) ?: return@withContext
        if (auto && !s.auto && s.stop != BackupStop.Unplugged) return@withContext
        if (!running.tryLock()) return@withContext // another run is copying, and it covers this one
        stopping = false
        try {
            copyAll(s, tree, onLongPass)
        } finally {
            running.unlock()
            _progress.value = null
            IndexStatus.report(JOB, 0, 0) // finished or stopped: off the card
        }
    }

    private suspend fun copyAll(s: BackupSettings, tree: Uri, onLongPass: suspend () -> Unit) {
        val has = { p: String -> app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        if (!has(READ_MEDIA_IMAGES) && !has(READ_MEDIA_VIDEO) && !has(READ_MEDIA_VISUAL_USER_SELECTED)) return
        if (reach(tree) != Reach.Ready) return // the page says why; a drive plugged in later starts it again
        val dest = tree.toString()
        val folder = folderFor(s, tree)
        // Never act on a failed query. An empty one only means nothing to copy: nothing on the drive is ever removed.
        val found = query() ?: return
        // Hidden items as the Library has them. Read here, not from Hidden.state: this may run with no screen ever shown.
        AlbumPrefs.load(app)
        val people = People.locked.first()
        val h = HiddenState(AlbumPrefs.locked.value, people.ids, people.photos)
        val chosen = found.filter { backsUp(it, s.choice, h, s.volume, ownPath(folder)) }
        val plan = planBackup(chosen, dao.stamps(dest))
        var done = chosen.size - plan.size
        progress(done, chosen.size)
        val dirs = Dirs(tree, folder)
        var free = runCatching { freeBytes(tree, folder) }.getOrNull()
        var failed = 0
        var tooBig = 0
        var stop: BackupStop? = null
        for ((i, step) in plan.withIndex()) {
            if (stopping) break
            if (plan.size - i >= LONG_PASS) onLongPass()
            val (item, copy) = step
            try {
                if (free != null && item.size + RESERVE > free) {
                    free = runCatching { freeBytes(tree, folder) }.getOrNull() // it was only an estimate since the last look
                    if (free != null && item.size + RESERVE > free) {
                        stop = BackupStop.Full
                        break
                    }
                }
                if (backUp(item, copy, dest, dirs) && free != null) free -= item.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unplugged mid-copy looks like any failed write, so ask the drive which it was
                when (reach(tree)) {
                    Reach.Missing -> {
                        stop = BackupStop.Unplugged
                        break
                    }
                    Reach.Lost -> break
                    Reach.Ready -> {
                        CrashLog.note("backup: couldn't copy an item: ${e.javaClass.simpleName}")
                        failed++
                        if (item.size > FAT32_MAX) tooBig++
                    }
                }
            }
            progress(++done, chosen.size)
        }
        val finished = done == chosen.size && failed == 0 && stop == null && !stopping
        CrashLog.note("backup: ${plan.size} to do, ${done - (chosen.size - plan.size)} done, $failed failed, stop $stop")
        update { it.copy(last = if (finished) System.currentTimeMillis() else it.last, failed = failed, tooBig = tooBig, stop = stop) }
    }

    private fun progress(done: Int, total: Int) {
        _progress.value = IndexProgress(done, total)
        IndexStatus.report(JOB, done, total)
    }

    /**
     * Makes sure [item] has a copy in the backup. An untouched file, or one already on the drive with the same name, size
     * and hash (a backup Album has since forgotten: data cleared, photo index rebuilt), is only recorded; anything else
     * is copied under a name nothing has yet. True when it wrote a new copy.
     */
    private suspend fun backUp(item: BackupSource, copy: CopyStamp?, dest: String, dirs: Dirs): Boolean {
        val resolver = app.contentResolver
        // Without this Android blanks the GPS tags in what it reads out, and the copy would lose where it was taken
        val source = mediaUri(item.id, item.isVideo).let { if (app.checkSelfPermission(ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) MediaStore.setRequireOriginal(it) else it }
        var hash: String? = null
        fun sourceHash() = hash ?: checkNotNull(resolver.openInputStream(source)).use { sha256(it).hex() }.also { hash = it }
        if (copy != null && copy.size == item.size && sourceHash() == copy.sha256) {
            dao.touch(dest, item.id, item.size, item.modified)
            return false
        }
        val dir = dirs.of(item.path)
        val name = fatName(item.name)
        // Looked up by name, not by going through the folder: DCIM/Camera may hold 10,000 files
        val there = clashNames(name).map(dir::file).takeWhile { it != null }.filterNotNull().filter { it.size == item.size }
            .firstOrNull { doc -> sourceHash() == checkNotNull(resolver.openInputStream(dirs.uri(doc.id))).use { sha256(it).hex() } }
        if (there != null) {
            dao.save(BackupCopy(dest, item.id, item.size, item.modified, sourceHash(), dir.path + there.name))
            return false
        }
        val (written, sha) = dir.write(source, clashNames(name).first { dir.file(it) == null })
        dao.save(BackupCopy(dest, item.id, item.size, item.modified, sha, dir.path + written.name))
        return true
    }

    /** The backup folder, made again (with what Album knew of it forgotten) if it's gone from the drive. */
    private suspend fun folderFor(s: BackupSettings, tree: Uri): String {
        val known = s.folder?.takeIf { runCatching { nameOf(DocumentsContract.buildDocumentUriUsingTree(tree, it)) }.isSuccess }
        val folder = known ?: folderIn(tree).also { made ->
            CrashLog.note("backup: folder was gone, made it again")
            dao.forget(tree.toString())
            update { it.copy(folder = made) }
        }
        marker(tree, folder)
        return folder
    }

    // The "Album backup" folder in [tree], made if needed; the chosen folder itself when it's already called that
    private fun folderIn(tree: Uri): String {
        val top = DocumentsContract.getTreeDocumentId(tree)
        if (nameOf(DocumentsContract.buildDocumentUriUsingTree(tree, top)).equals(FOLDER, ignoreCase = true)) return top
        children(tree, top).find { it.isDir && it.name.equals(FOLDER, ignoreCase = true) }?.let { return it.id }
        val made = DocumentsContract.createDocument(app.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, top), Document.MIME_TYPE_DIR, FOLDER)
        return DocumentsContract.getDocumentId(checkNotNull(made))
    }

    /**
     * The backup folder's .nomedia, made if missing. It keeps Android's media index out of the backup, so a connected
     * drive doesn't show every photo twice in the Library (or in other galleries), and gives a file to read free space through.
     */
    private fun marker(tree: Uri, folder: String): Uri {
        val found = children(tree, folder).find { !it.isDir && it.name == ".nomedia" }?.id
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(tree, folder)
        return found?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            ?: checkNotNull(DocumentsContract.createDocument(app.contentResolver, folderUri, "application/octet-stream", ".nomedia"))
    }

    // From the file system holding the marker: Android's folder access gives no other way to ask a drive's free space
    private fun freeBytes(tree: Uri, folder: String): Long = checkNotNull(app.contentResolver.openFileDescriptor(marker(tree, folder), "r")).use {
        val stat = Os.fstatvfs(it.fileDescriptor)
        stat.f_bavail * stat.f_frsize
    }

    private fun volumeOf(tree: Uri): StorageVolume? {
        val root = DocumentsContract.getTreeDocumentId(tree).substringBefore(':') // "primary", or a drive's id ("1234-ABCD")
        return app.getSystemService(StorageManager::class.java).storageVolumes.find { if (root == "primary") it.isPrimary else it.uuid.equals(root, ignoreCase = true) }
    }

    private fun reach(tree: Uri): Reach {
        if (volumeOf(tree)?.state != Environment.MEDIA_MOUNTED) return Reach.Missing
        // Access is gone if the user took it back in Android's settings, or the folder was deleted or the drive formatted
        val access = app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        val there = access && runCatching { nameOf(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))) }.isSuccess
        return if (there) Reach.Ready else Reach.Lost
    }

    private class Doc(val id: String, val name: String, val isDir: Boolean, val size: Long)

    private fun children(tree: Uri, id: String): List<Doc> =
        checkNotNull(app.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), DOC_COLUMNS, null, null, null)).use { c ->
            List(c.count) {
                c.moveToNext()
                Doc(c.getString(0), c.getString(1).orEmpty(), c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(3))
            }
        }

    private fun nameOf(doc: Uri): String = checkNotNull(app.contentResolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)).use {
        check(it.moveToFirst())
        it.getString(0).orEmpty()
    }

    /** The backup folder's folders, each listed once a run and made when a copy first needs it. By lower-case path, as FAT is. */
    private class Dirs(val tree: Uri, val root: String) {
        private val dirs = HashMap<String, Dir>()

        fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

        fun of(relativePath: String): Dir {
            var dir = dirs.getOrPut("") { Dir(this, root, "") }
            for (name in backupDirs(relativePath)) {
                val parent = dir
                dir = dirs.getOrPut((parent.path + name + "/").lowercase()) { parent.child(name) }
            }
            return dir
        }
    }

    /** One folder in the backup, [path] inside it ("DCIM/Camera/"), with what's in it. */
    private class Dir(val dirs: Dirs, val id: String, val path: String) {
        private val files = HashMap<String, Doc>()
        private val folders = HashMap<String, String>()

        init {
            for (doc in children(dirs.tree, id)) {
                when {
                    doc.isDir -> folders[doc.name.lowercase()] = doc.id
                    // A copy a stopped backup or an unplugged drive left half-written: Album's own, never a photo of the user's
                    doc.name.endsWith(PARTIAL) -> runCatching { DocumentsContract.deleteDocument(app.contentResolver, dirs.uri(doc.id)) }
                    else -> files[doc.name.lowercase()] = doc
                }
            }
        }

        fun file(name: String): Doc? = files[name.lowercase()]

        fun child(name: String): Dir {
            val id = folders[name.lowercase()]
                ?: DocumentsContract.getDocumentId(checkNotNull(DocumentsContract.createDocument(app.contentResolver, dirs.uri(id), Document.MIME_TYPE_DIR, name)))
            return Dir(dirs, id, path + nameOf(dirs.uri(id)) + "/")
        }

        /**
         * Writes [source] here as [name] (or a free name, should one appear meanwhile: Android never overwrites), under a
         * temporary name until every byte is on the drive and reads back the same. Returns the new file and its hash.
         */
        suspend fun write(source: Uri, name: String): Pair<Doc, String> {
            val resolver = app.contentResolver
            // A plain binary type, so Android keeps the name as given instead of adding an extension for the photo's type
            val temp = checkNotNull(DocumentsContract.createDocument(resolver, dirs.uri(id), "application/octet-stream", name + PARTIAL))
            var renamed = false
            try {
                val (sha, size) = checkNotNull(resolver.openInputStream(source)).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(checkNotNull(resolver.openFileDescriptor(temp, "w"))).use { out ->
                        copyHashing(input, out).also {
                            out.flush()
                            out.fd.sync() // on the drive, not just in a write cache, before it counts
                        }
                    }
                }
                // Read back what the drive has: a short or garbled write never passes for a backup
                val stored = checkNotNull(resolver.query(temp, arrayOf(Document.COLUMN_SIZE), null, null, null)).use { check(it.moveToFirst()); it.getLong(0) }
                check(stored == size) { "size" }
                check(checkNotNull(resolver.openInputStream(temp)).use { sha256(it).hex() } == sha) { "hash" }
                val final = DocumentsContract.renameDocument(resolver, temp, name) ?: temp
                renamed = true
                val doc = Doc(DocumentsContract.getDocumentId(final), nameOf(final), false, size)
                files[doc.name.lowercase()] = doc
                return doc to sha
            } finally {
                // Only ever the temporary file this made: a renamed copy is kept, whatever happens next
                if (!renamed) runCatching { DocumentsContract.deleteDocument(resolver, temp) }
            }
        }
    }

    /** Newest first. Null if MediaStore didn't answer. Trashed and pending items aren't in it. */
    private fun query(): List<BackupSource>? {
        val projection = arrayOf(
            FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.SIZE, FileColumns.DATE_MODIFIED, FileColumns.VOLUME_NAME,
            FileColumns.RELATIVE_PATH, FileColumns.DISPLAY_NAME,
        )
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return app.contentResolver.query(uri, projection, PHOTOS_AND_VIDEOS, null, "${FileColumns._ID} DESC")?.use { c ->
            List(c.count) {
                c.moveToNext()
                BackupSource(
                    c.getLong(0), c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO, c.getLong(2), c.getLong(3), c.getString(4).orEmpty(),
                    c.getString(5).orEmpty(), c.getString(6).orEmpty(),
                )
            }
        }
    }

    private fun load(): BackupSettings = with(prefs) {
        BackupSettings(
            tree = getString("tree", null),
            folder = getString("folder", null),
            drive = getString("drive", null).orEmpty(),
            volume = getString("volume", null).orEmpty(),
            onPhone = getBoolean("on_phone", false),
            auto = getBoolean("auto", false),
            choice = BackupChoice(
                albums = if (getBoolean("all_albums", true)) null else getStringSet("albums", null).orEmpty().toSet(),
                hidden = getBoolean("hidden", false),
            ),
            last = getLong("last", 0),
            failed = getInt("failed", 0),
            tooBig = getInt("too_big", 0),
            stop = BackupStop.entries.find { it.name == getString("stop", null) },
        )
    }

    private fun update(change: (BackupSettings) -> BackupSettings): BackupSettings {
        var now = BackupSettings()
        _settings.update { change(it ?: load()).also { s -> now = s } }
        prefs.edit {
            putString("tree", now.tree)
            putString("folder", now.folder)
            putString("drive", now.drive)
            putString("volume", now.volume)
            putBoolean("on_phone", now.onPhone)
            putBoolean("auto", now.auto)
            putBoolean("all_albums", now.choice.albums == null)
            putStringSet("albums", now.choice.albums.orEmpty())
            putBoolean("hidden", now.choice.hidden)
            putLong("last", now.last)
            putInt("failed", now.failed)
            putInt("too_big", now.tooBig)
            putString("stop", now.stop?.name)
        }
        return now
    }
}

/** Runs [Backup.run] under WorkManager: carries on after Album or the phone restarts, and waits out a low battery when automatic. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val longPass = LongPassGate(this, ::backingUp)

    override suspend fun doWork(): Result = try {
        Backup.run(auto = inputData.getBoolean(AUTO, false), onLongPass = longPass::tick)
        Result.success()
    } catch (e: CancellationException) {
        throw e // stopped by WorkManager: copies so far are recorded, the rest goes next time
    } catch (e: Exception) {
        CrashLog.note("backup failed: ${e.javaClass.simpleName}")
        Result.failure()
    }
}

/**
 * A drive was plugged in (Android's media-mounted broadcast, which needs no permission and reaches Album while it isn't
 * running): see [Backup.mounted]. Exported because Android sends it; another app sending one only makes Album look for its drive.
 */
class BackupMountReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Backup.mounted(context)
}

// The foreground notification for a long backup, on a channel of its own so it can be silenced apart from organising
private fun backingUp(context: Context): ForegroundInfo {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Album copies your photos and videos to your backup drive"
            setShowBadge(false)
        },
    )
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val drive = Backup.settings.value?.drive.orEmpty().ifEmpty { "your backup drive" }
    val notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Backing up your photos")
        .setContentText("Copying to $drive")
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(open)
        .build()
    // As organising's: media processing on Android 15+, data sync before that
    val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    return ForegroundInfo(NOTIFICATION_ID, notification, type)
}
