package com.aditya.album

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.edit
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/** Writes crash, ANR and native-crash reports to files/crash on the phone. Never uploads anything. */
object CrashLog {
    private const val MAX_FILES = 50
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val MAX_TRACE = 256 * 1024
    private val KEEP = setOf(
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )
    private val fileTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())
    private val readableTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private val trail = ArrayDeque<String>()
    private lateinit var dir: File

    fun install(context: Context) {
        dir = File(context.filesDir, "crash").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(context, "crash", "Thread: ${t.name}\n\n${e.stackTraceToString()}") }
            previous?.uncaughtException(t, e) // let Android show its normal crash behaviour
        }
        thread(name = "exit-reasons", priority = Thread.MIN_PRIORITY) {
            runCatching { saveExitReasons(context) }
        }
    }

    /** Breadcrumb: the last 50 user actions are added to every crash report. */
    fun note(event: String) = synchronized(trail) {
        if (trail.size == 50) trail.removeFirst()
        trail.addLast("${readableTime.format(Instant.now())}  $event")
    }

    /** Newest first. Call off the main thread. */
    fun files(): List<File> = dir.listFiles()?.sortedDescending().orEmpty()

    fun clear() = dir.listFiles()?.forEach { it.delete() }

    /** Zips all logs into the phone's Downloads folder. Returns the file name, or null if there were none. */
    fun exportToDownloads(context: Context): String? {
        val logs = files().ifEmpty { return null }
        val name = "album-logs-${fileTime.format(Instant.now())}.zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out).use { zip ->
                logs.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return name
    }

    // Android itself records ANRs, native crashes and low-memory kills. Copy any new ones on the next launch.
    private fun saveExitReasons(context: Context) {
        val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val seen = prefs.getLong("lastExit", 0)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, 10)
            .filter { it.timestamp > seen }
        exits.filter { it.reason in KEEP }.forEach { e ->
            val trace = e.traceInputStream?.use { String(it.readNBytes(MAX_TRACE)) }.orEmpty()
            val body = "Reason: ${e.description}\nMemory (PSS): ${e.pss / 1024} MB\n\n$trace"
            write(context, kind(e.reason), body, e.timestamp, withTrail = false)
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit { putLong("lastExit", it) } }
    }

    private fun kind(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "lowmem"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "resource"
        else -> "init"
    }

    private fun write(
        context: Context,
        kind: String,
        body: String,
        time: Long = System.currentTimeMillis(),
        withTrail: Boolean = true,
    ) {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val rt = Runtime.getRuntime()
        val report = buildString {
            appendLine("Type: $kind")
            appendLine("Time: ${readableTime.format(Instant.ofEpochMilli(time))}")
            appendLine("App: ${pkg.versionName} (${pkg.longVersionCode})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Heap: ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB used of ${rt.maxMemory() shr 20} MB")
            if (withTrail) {
                appendLine("\nLast actions:")
                synchronized(trail) { trail.forEach(::appendLine) }
            }
            appendLine()
            append(body)
        }
        File(dir, "${fileTime.format(Instant.ofEpochMilli(time))}-$kind.txt").writeText(report)
        prune(dir)
    }

    /** Keeps the newest files within the count and size caps. File names start with a timestamp, so name order is age order. */
    internal fun prune(dir: File, maxFiles: Int = MAX_FILES, maxBytes: Long = MAX_BYTES) {
        var total = 0L
        dir.listFiles()?.sortedDescending()?.forEachIndexed { i, f ->
            total += f.length()
            if (i > 0 && (i >= maxFiles || total > maxBytes)) f.delete() // never delete the newest report
        }
    }
}
