package com.aditya.album

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** In-app updates from GitHub Releases. This is the only code that uses the network, and only when the user taps the button. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/adityapandey78/album/releases/latest"

    class Release(val version: String, val notes: String, val url: String, val size: Long, val sha256: String?)

    /** Result of the last install attempt, set by [InstallResultReceiver] and shown in Settings. */
    var installMessage by mutableStateOf<String?>(null)

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Newest published release, or null if it has no APK attached. Throws on network errors. Call off the main thread. */
    fun latest(): Release? {
        val json = JSONObject(open(LATEST).inputStream.use { it.readBytes().decodeToString() })
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        return Release(
            version = json.getString("tag_name").removePrefix("v"),
            notes = json.optString("body"),
            url = apk.getString("browser_download_url"),
            size = apk.getLong("size"),
            sha256 = apk.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
        )
    }

    /** True if [latest] (like "0.3.0") is a higher version than [current] (like "0.2.0"). */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Streams the APK straight into Android's installer (no temp file), checks its size and SHA-256,
     * then hands over to the system install screen. Android itself refuses an APK not signed with our key.
     * Call off the main thread.
     */
    fun downloadAndInstall(context: Context, release: Release, onProgress: (Float) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED) // silent when Android allows it
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                open(release.url).inputStream.use { input ->
                    session.openWrite("album.apk", 0, release.size).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            onProgress(done.toFloat() / release.size)
                        }
                        session.fsync(out)
                    }
                }
                check(done == release.size) { "download incomplete ($done of ${release.size} bytes)" }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(release.sha256 == null || hash == release.sha256) { "downloaded file is damaged (checksum mismatch)" }
                val result = Intent(context, InstallResultReceiver::class.java)
                val pending = PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

    private fun open(url: String) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Album-Android")
        if (url == LATEST) setRequestProperty("Accept", "application/vnd.github+json")
        if (responseCode !in 200..299) error("GitHub returned HTTP $responseCode")
    }
}

/** Android reports the update install result here. Not exported, so only the system (via our PendingIntent) can call it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) // the system "Update this app?" screen
                }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android replaces the app; reopen it to use the new version
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Update failed: not enough free storage"
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Update failed: it's signed with a different key. Uninstall Album, then install the new version from GitHub once."
                    else -> "Update failed: $detail"
                }
                CrashLog.note("update result: $detail")
                Updater.installMessage = message
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
