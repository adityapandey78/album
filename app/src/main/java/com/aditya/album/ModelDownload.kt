package com.aditya.album

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** A file of the describe-it model in Album's GitHub release, with the exact size and SHA-256 it must have. */
class ModelFile(val name: String, val size: Long, val sha256: String)

/** A download failure, with a message that can be shown as it is. */
class DownloadError(message: String) : IOException(message)

/**
 * The one-time download of describe-it search's model (Meta's Perception Encoder, see tools/describe_convert.py), from
 * Album's own GitHub release, only when the user taps Download. With the updater, this is the only code that uses the
 * network. The sizes and hashes are built into the app, so a file changed on GitHub is refused, not installed.
 *
 * A file is installed (renamed into [dir]) only once its size and SHA-256 match. Until then it's a partial file in the
 * cache folder: a dropped connection resumes where it stopped on the next try, and Android may clear it if storage
 * runs low. Nothing partial is ever where the model is loaded from.
 */
object ModelDownload {
    private const val RELEASE = "https://github.com/adityapandey78/album/releases/download/models-v1/"

    val IMAGE = ModelFile("pe-core-s16-384-image.tflite", 31_375_280, "c32a395050ac01fb55865933d663c44be2c0845ff1b8819da0075f45c62e31cd")
    val TEXT = ModelFile("pe-core-s16-384-text.tflite", 65_138_960, "b1b0294abdb7d59c267bfd9a238ac7d881265661e3c7d7334a27a42d4a231292")
    private val FILES = listOf(IMAGE, TEXT)

    /** Bytes to download, for the Settings row. */
    val TOTAL = FILES.sumOf { it.size }

    /** Where the installed files live: app-private, and left out of backups (it can be downloaded again). */
    fun dir(context: Context) = File(context.noBackupFilesDir, "describe")

    private fun partial(context: Context) = File(context.cacheDir, "describe")

    /** Every file is in place. Sizes only: each file's SHA-256 was checked as it was installed. */
    fun installed(context: Context) = FILES.all { File(dir(context), it.name).length() == it.size }

    /**
     * Downloads, checks and installs whatever is missing, reporting 0 to 1. A file already in place is checked again
     * first, so trying again after a failure also replaces a damaged one. Blocking: call off the main thread. Throws
     * [DownloadError] for anything the user can act on.
     */
    fun download(context: Context, onProgress: (Float) -> Unit) {
        val dir = dir(context).apply { mkdirs() }
        val parts = partial(context).apply { mkdirs() }
        val missing = FILES.filter { file ->
            val installed = File(dir, file.name)
            !verified(installed, file).also { ok -> if (!ok) installed.delete() }
        }
        val needed = missing.sumOf { it.size - resumeFrom(File(parts, it.name).length(), it.size) }
        if (dir.usableSpace < needed + (16 shl 20)) throw DownloadError("Not enough free storage. Describe-it search needs ${needed / 1_000_000 + 1} MB.")
        var done = TOTAL - missing.sumOf { it.size }
        for (file in missing) {
            val part = File(parts, file.name)
            fetch(RELEASE + file.name, file, part) { length -> onProgress((done + length).toFloat() / TOTAL) }
            if (!verified(part, file)) {
                part.delete() // which bytes are wrong can't be known, so the next try starts it again
                throw DownloadError("The downloaded file was damaged. Try again.")
            }
            if (!part.renameTo(File(dir, file.name))) throw DownloadError("Couldn't save the model. Try again.")
            done += file.size
        }
    }

    /** Deletes the installed model and any partial download. */
    fun remove(context: Context) {
        dir(context).deleteRecursively()
        partial(context).deleteRecursively()
    }

    /** Streams [file] from [url] into [part], continuing from what [part] holds when the server allows. [onLength] gets [part]'s length. */
    internal fun fetch(url: String, file: ModelFile, part: File, onLength: (Long) -> Unit) {
        val have = resumeFrom(part.length(), file.size)
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Album-Android")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = connection.responseCode
            val from = when {
                code == HttpURLConnection.HTTP_OK -> 0L // the whole file, from the start
                code == HttpURLConnection.HTTP_PARTIAL && rangeStart(connection.getHeaderField("Content-Range")) == have -> have
                else -> {
                    if (code == HttpURLConnection.HTTP_PARTIAL || code == 416) part.delete() // what's kept doesn't fit what's there
                    throw DownloadError("GitHub returned HTTP $code. Try again later.")
                }
            }
            val coming = connection.contentLengthLong
            if (coming >= 0 && from + coming != file.size) {
                part.delete()
                throw DownloadError("GitHub has a different file than this version of Album expects.")
            }
            var length = from
            connection.inputStream.use { input ->
                FileOutputStream(part, from > 0).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (length < file.size) { // never more than the file should have; the hash catches the rest
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        length += n
                        onLength(length)
                    }
                    out.fd.sync() // on disk before it's checked and renamed
                }
            }
            if (length < file.size) throw DownloadError("The download stopped early. Try again to continue it.")
        } catch (e: DownloadError) {
            throw e
        } catch (e: IOException) {
            // A dropped connection keeps what arrived, so the next try continues from there
            CrashLog.note("describe download: $e")
            throw DownloadError("Couldn't download. Check your internet and try again.")
        } finally {
            connection.disconnect()
        }
    }
}

/** Where a download can continue from: what the partial file holds, unless that's nothing or not less than [size]. */
fun resumeFrom(partLength: Long, size: Long) = if (partLength in 1 until size) partLength else 0L

/** The first byte of a 206 response, from its Content-Range ("bytes 100-199/200"), or null if it can't be read. */
fun rangeStart(contentRange: String?): Long? =
    contentRange?.let { Regex("""^bytes (\d+)-\d+/(\d+|\*)$""").find(it.trim()) }?.groupValues?.get(1)?.toLongOrNull()

/** [file] is exactly [expected]: the same size and SHA-256. */
fun verified(file: File, expected: ModelFile): Boolean = file.length() == expected.size && sha256(file) == expected.sha256

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
