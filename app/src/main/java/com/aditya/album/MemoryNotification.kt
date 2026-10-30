package com.aditya.album

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Size
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// On this day as a notification: off until it's turned on in Settings, then once a day at the hour picked, and only on
// days when photos from this date in earlier years make a memory. Worked out on the phone like For you's; screenshots,
// documents, Hidden items and the Private safe never show up in it. WorkManager runs the daily check, so it outlives
// restarts without the exact-alarm permission, and lands within minutes of the hour unless the phone is asleep deep.

private const val CHANNEL = "on-this-day"
private const val NOTIFICATION_ID = 1002 // 1001 is the organising notification (LongPass.kt)
private const val WORK = "on-this-day"
private const val DUE_DAY = "due_day" // the epoch day a check is for
private const val PICTURE_PX = 1024 // shown about as wide as the screen; Android scales it down to its own limit
private const val MIN_PHOTOS = 3 // fewer on a day is mostly a receipt or a whiteboard, not an occasion
private const val INDEX_MS = 10_000L // the smart index's documents; without them the check goes ahead
private const val DEFAULT_HOUR = 9

/** The hours the notification can come at: morning, midday and evening. */
val MEMORY_HOURS = listOf(7, 9, 12, 18, 21)

/**
 * The memory to notify about on [today]: photos from this date in the latest earlier year with at least [MIN_PHOTOS]
 * [worthy] ones (not screenshots, documents or videos), titled as For you's On this day. Null on days with nothing.
 * [photos] newest first.
 */
fun <T> memoryOfTheDay(photos: List<T>, today: LocalDate, dayOf: (T) -> Long, worthy: (T) -> Boolean): Memory<T>? {
    val kept = photos.filter(worthy)
    val busy = kept.groupingBy(dayOf).eachCount().filterValues { it >= MIN_PHOTOS }.keys
    return onThisDay(kept.filter { dayOf(it) in busy }, today, dayOf)
}

/** "3 years ago today · Goa", or without the place when the smart index doesn't know it. */
fun memoryLine(title: String, place: String?) = listOfNotNull("$title today", place).joinToString(" · ")

/** "9 am", "12 pm", "9 pm", or "09:00" and "21:00" on a phone set to the 24-hour clock. */
fun hourLabel(hour: Int, clock24: Boolean) = when {
    clock24 -> "%02d:00".format(hour)
    hour == 0 -> "12 am"
    hour < 12 -> "$hour am"
    hour == 12 -> "12 pm"
    else -> "${hour - 12} pm"
}

/** When to check next after [now], for a notification at [hour]: today at that hour if it's still to come, else tomorrow. */
fun nextCheck(now: LocalDateTime, hour: Int): LocalDateTime {
    val today = now.toLocalDate().atTime(hour, 0)
    return if (now < today) today else today.plusDays(1)
}

/**
 * Whether a check meant for epoch day [due] looks for a memory at [now]: on that day, from [hour] on, and not on a day
 * already notified ([lastDay]). One that runs into the next day (the phone was off) skips it rather than notify at an odd
 * hour; one that runs early (the clock or time zone changed) waits for the hour.
 */
fun checksNow(now: LocalDateTime, due: Long, hour: Int, lastDay: Long): Boolean {
    val today = now.toLocalDate().toEpochDay()
    return today == due && today != lastDay && now.hour >= hour
}

/** The On this day notification: its setting (in the "settings" prefs), its daily check and the notification itself. */
object MemoryNotification {
    private const val ON = "memory_notification"
    private const val HOUR = "memory_notification_hour"
    private const val ASKED = "memory_notification_asked" // Android's permission prompt has been shown before
    private const val LAST_DAY = "memory_notification_last" // the epoch day last notified, so a new hour never repeats it
    private lateinit var prefs: SharedPreferences
    private val onState = MutableStateFlow(false)
    private val hourState = MutableStateFlow(DEFAULT_HOUR)
    val on = onState.asStateFlow()
    val hour = hourState.asStateFlow()

    /** Safe to call repeatedly; the "settings" prefs are already loading from AlbumApp. */
    fun load(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        onState.value = prefs.getBoolean(ON, false)
        hourState.value = prefs.getInt(HOUR, DEFAULT_HOUR)
    }

    /** Turns the notification on at [hour], or off. Either way the check moves: a new hour applies from today. */
    fun set(context: Context, on: Boolean, hour: Int = hourState.value) {
        load(context)
        CrashLog.note("on this day ${if (on) "at $hour" else "off"}")
        onState.value = on
        hourState.value = hour
        prefs.edit {
            putBoolean(ON, on)
            putInt(HOUR, hour)
        }
        if (!on) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            return
        }
        channel(context) // so Android's settings can show it before the first one comes
        schedule(context, ExistingWorkPolicy.REPLACE)
    }

    internal var asked: Boolean
        get() = prefs.getBoolean(ASKED, false)
        set(value) = prefs.edit { putBoolean(ASKED, value) }

    /** The next check, at the hour; [policy] REPLACE for a new setting, APPEND_OR_REPLACE from the check itself. */
    internal fun schedule(context: Context, policy: ExistingWorkPolicy) {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val next = nextCheck(now, hourState.value)
        val request = OneTimeWorkRequestBuilder<MemoryNotificationWorker>()
            .setInitialDelay(Duration.between(now.atZone(zone), next.atZone(zone)))
            .setInputData(workDataOf(DUE_DAY to next.toLocalDate().toEpochDay()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }

    /** Whether a check meant for epoch day [due] looks for a memory [now]; see [checksNow]. */
    internal fun isTime(now: LocalDateTime, due: Long) = checksNow(now, due, hourState.value, prefs.getLong(LAST_DAY, -1))

    /** Whether Android lets Album post it: notification permission, Album's notifications and this channel all on. */
    fun canPost(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Android's page for turning it back on: this channel's when only the channel is off, else Album's notifications. */
    fun settingsIntent(context: Context): Intent {
        val channelOnly = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        return Intent(if (channelOnly) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
    }

    private fun channel(context: Context) = context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        // Low: no sound and no pop-up, it waits in the shade
        NotificationChannel(CHANNEL, "On this day", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A photo from this date in earlier years, at most once a day"
            setShowBadge(false)
        },
    )

    /** Looks for [today]'s memory and, when there's one, posts it. Blocking: reads the Library and the smart index. */
    internal suspend fun notifyFor(context: Context, today: LocalDate) {
        if (!canPost(context)) return
        if (DisguisePrefs.isChosen(context)) return // a notification names the app, which a disguise hides
        val photos = visibleMedia(context)?.inPhotos(AlbumPrefs.hidden.value) ?: return
        // Receipts, tickets and ID cards are photos too, but nobody wants one back as a memory
        val documents = withTimeoutOrNull(INDEX_MS) { SmartIndex.mediaIdsFor(Category.Documents).first() }.orEmpty()
        val memory = memoryOfTheDay(photos, today, Media::day) { !it.isVideo && !isScreenshot(it.path, it.name) && it.id !in documents } ?: return
        val placeOf = placesNow()
        val place = tripNames(memory.items.mapNotNull { placeOf[it.id] }).firstOrNull()
        val photo = memory.items.firstOrNull(Media::isFavorite) ?: memory.items.first()
        val picture = context.contentResolver.loadThumbnail(photo.uri, Size(PICTURE_PX, PICTURE_PX), null)
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, playMemoryIntent(context, memory), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        channel(context)
        val locked = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("On this day").build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(memoryLine(memory.title, place))
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).showBigPictureWhenCollapsed(true))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            // A locked phone shows only that there's a memory; the photo waits until it's unlocked
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(locked)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        prefs.edit { putLong(LAST_DAY, today.toEpochDay()) }
        CrashLog.note("on this day posted")
    }
}

/** The daily check: notifies when it's time and there's a memory, then books the next check. */
class MemoryNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        MemoryNotification.load(app)
        if (!MemoryNotification.on.value) return Result.success() // turned off since: the checks end here
        val now = LocalDateTime.now()
        try {
            if (MemoryNotification.isTime(now, inputData.getLong(DUE_DAY, -1))) MemoryNotification.notifyFor(app, now.toLocalDate())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLog.note("on this day failed: $e") // a bad day never stops tomorrow's
        }
        // Appended, so it waits for this one to finish; REPLACE would cancel this one on the way out
        MemoryNotification.schedule(app, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

/**
 * Settings' On this day row: Off, or the hour it comes at. It opens a sheet with the switch and, while on, the hours.
 * Turning it on asks for Android's notification permission first; once Android stops asking, it opens Album's
 * notification settings instead and turns on when you come back with them allowed. [modifier] goes on the row, a MenuRow.
 */
@Composable
fun MemoryNotificationRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    MemoryNotification.load(context)
    val on by MemoryNotification.on.collectAsState()
    val hour by MemoryNotification.hour.collectAsState()
    var allowed by remember { mutableStateOf(MemoryNotification.canPost(context)) }
    var open by remember { mutableStateOf(false) }
    var waiting by rememberSaveable { mutableStateOf(false) } // gone to Android's settings to allow notifications
    LifecycleResumeEffect(Unit) {
        allowed = MemoryNotification.canPost(context) // the user may have changed it in Android's settings
        if (waiting) {
            waiting = false
            if (allowed) MemoryNotification.set(context, true)
        }
        onPauseOrDispose {}
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = MemoryNotification.canPost(context)
        if (allowed) MemoryNotification.set(context, true)
    }

    fun turnOn() {
        // Android shows its prompt twice at most; after a second "Don't allow" only its settings can change it
        val prompt = context.checkSelfPermission(POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!MemoryNotification.asked || activity?.shouldShowRequestPermissionRationale(POST_NOTIFICATIONS) == true)
        when {
            allowed -> MemoryNotification.set(context, true)
            prompt -> {
                MemoryNotification.asked = true
                ask.launch(POST_NOTIFICATIONS)
            }
            else -> {
                waiting = true
                context.startSafely(MemoryNotification.settingsIntent(context))
            }
        }
    }

    val shown = on && allowed
    val clock24 = DateFormat.is24HourFormat(context)
    MenuRow(Sym.Today, "On this day", { open = true }, modifier, sub = if (shown) "At ${hourLabel(hour, clock24)}, on days with a memory" else "Off")
    if (!open) return
    val c = AlbumTheme.colors
    Sheet({ open = false }, "On this day") {
        Text(
            "A quiet notification with a photo from this date in earlier years, only on days that have one. Never from Hidden items or the Private safe.",
            Modifier.padding(bottom = Space.s2), style = AlbumTheme.type.caption, color = c.inkMuted,
        )
        SwitchRow(
            "Show a notification",
            if (on && !allowed) "Notifications for Album are off in Android settings. Tap to turn them on." else null,
            shown,
            { if (it) turnOn() else MemoryNotification.set(context, false) },
        )
        if (shown) {
            FlowRow(
                Modifier.padding(top = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                MEMORY_HOURS.forEach { h ->
                    val picked = h == hour
                    // A button that says it's selected: picking the chosen hour again changes nothing, so it isn't a toggle
                    Chip(hourLabel(h, clock24), { MemoryNotification.set(context, true, h) }, Modifier.semantics { selected = picked }, selected = picked, toggle = false)
                }
            }
        }
    }
}
