package com.aditya.album

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import kotlinx.coroutines.CancellationException

/** A pass with at least this many items to go keeps running after Album leaves the screen (see [runInForeground]). */
internal const val LONG_PASS = 200

private const val CHANNEL = "organising"
private const val NOTIFICATION_ID = 1001 // one notification shared by every job that's organising

/**
 * Asks Android to keep this pass running after Album leaves the screen, as a foreground job with a quiet notification
 * ("Organising your photos"). Ordinary background work gets about 10 minutes a turn and, for an app not in use, few
 * turns, so a big library was organised a slice per visit and the card showed up on every open. Android allows this
 * only while Album is on screen; when it refuses, the pass carries on as ordinary background work, as before.
 */
internal suspend fun CoroutineWorker.runInForeground(): Boolean = try {
    setForeground(organising(applicationContext))
    true
} catch (e: CancellationException) {
    throw e // the pass is being stopped: let it stop
} catch (e: Exception) {
    false // started in the background (Android refuses then): tried again later, see [LongPassGate]
}

/**
 * Per worker: tries [runInForeground] whenever a pass has a lot left, at most once a minute, until it works. So a pass
 * that began in the background moves to the foreground soon after Album is opened.
 */
internal class LongPassGate(private val worker: CoroutineWorker) {
    private var promoted = false
    private var nextTry = 0L

    suspend fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (promoted || now < nextTry) return
        nextTry = now + 60_000
        promoted = worker.runInForeground()
    }
}

private fun organising(context: Context): ForegroundInfo {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Organising photos", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Album finds people, places and things in your photos, on this phone"
            setShowBadge(false)
        },
    )
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Organising your photos")
        .setContentText("Finding people, places and things on this phone")
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(open)
        .build()
    // Media processing is the type made for this (Android 15+); data sync before that
    val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    return ForegroundInfo(NOTIFICATION_ID, notification, type)
}
