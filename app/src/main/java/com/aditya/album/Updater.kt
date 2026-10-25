package com.aditya.album

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * Update check against GitHub Releases. This is the only code that uses the network, and only when the user taps the button.
 * Album doesn't install updates itself: apps holding REQUEST_INSTALL_PACKAGES get blocked by Play Protect as "harmful"
 * (tested 2026-10-25), so the APK download is handed to the browser and Android installs it from there.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/adityapandey78/album/releases/latest"

    class Release(val version: String, val notes: String, val url: String)

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Newest published release, or null if it has no APK attached. Throws on network errors. Call off the main thread. */
    fun latest(): Release? {
        val connection = URI(LATEST).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Album-Android")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        if (connection.responseCode !in 200..299) error("GitHub returned HTTP ${connection.responseCode}")
        val json = JSONObject(connection.inputStream.use { it.readBytes().decodeToString() })
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        return Release(json.getString("tag_name").removePrefix("v"), json.optString("body"), apk.getString("browser_download_url"))
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
}
