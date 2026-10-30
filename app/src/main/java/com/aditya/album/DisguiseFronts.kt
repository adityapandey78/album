package com.aditya.album

import android.app.usage.StorageStatsManager
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// The disguises' first screens besides the calculator (Calculator.kt). Each works on its own and offline: a real clock
// and stopwatch, a compass from the phone's sensors, real storage figures, real time-based codes. Each has one quiet way
// in, a long press on the thing that screen is about, which asks for the privacy PIN or fingerprint.

/** A long press runs [onHold], the disguise's way in. TalkBack offers it as this element's long-press action. */
private fun Modifier.holdToOpen(onHold: () -> Unit) =
    pointerInput(Unit) { detectTapGestures(onLongPress = { onHold() }) }.semantics { onLongClick { onHold(); true } }

/** The front for [disguise] (not the calculator); a long press on its main element runs [onHold]. */
@Composable
internal fun QuietFront(disguise: Disguise, onHold: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AlbumTheme.colors.canvas).systemBarsPadding()) {
        when (disguise) {
            Disguise.Clock -> ClockFront(onHold)
            Disguise.Compass -> CompassFront(onHold)
            Disguise.Notes -> {
                LargeTopBar("Notes", Modifier.holdToOpen(onHold))
                Centred { EmptyState(Sym.Description, "No notes yet", "Notes you write appear here.") }
            }
            Disguise.Files -> FilesFront(onHold)
            Disguise.Scanner -> ScannerFront(onHold)
            Disguise.Authenticator -> AuthenticatorFront(onHold)
            Disguise.Workspace -> WorkspaceFront(onHold)
            Disguise.Calculator -> {} // has a screen of its own
        }
    }
}

@Composable
private fun ColumnScope.Centred(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { content() }

// ---------- Clock ----------

/** A stopwatch reading: "01:05.42", hours only once there are some ("1:02:05.42"). */
fun stopwatchText(ms: Long): String {
    val hundredths = ms / 10 % 100
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d.%02d".format(s / 3600, s / 60 % 60, s % 60, hundredths) else "%02d:%02d.%02d".format(s / 60, s % 60, hundredths)
}

@Composable
private fun ColumnScope.ClockFront(onHold: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000 - System.currentTimeMillis() % 1_000) // on the second, so the minute turns when the phone's does
        }
    }
    val twentyFour = remember { DateFormat.is24HourFormat(context) }
    var running by rememberSaveable { mutableStateOf(false) }
    var banked by rememberSaveable { mutableLongStateOf(0L) } // ms run before the current start
    var since by rememberSaveable { mutableLongStateOf(0L) } // the boot clock at the current start, which a time change can't move
    var shown by remember { mutableLongStateOf(banked) }
    LaunchedEffect(running) {
        if (!running) shown = banked
        while (running) withFrameMillis { shown = banked + SystemClock.elapsedRealtime() - since }
    }

    Centred {
        Column(Modifier.holdToOpen(onHold), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                // Shrinks rather than wraps at large font sizes
                BasicText(
                    now.format(DateTimeFormatter.ofPattern(if (twentyFour) "HH:mm" else "h:mm")),
                    style = type.display.copy(color = c.ink, lineHeight = 72.sp),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 32.sp, maxFontSize = 72.sp),
                )
                if (!twentyFour) Text(now.format(DateTimeFormatter.ofPattern("a")), Modifier.padding(start = Space.s2, bottom = Space.s2), style = type.titleMd, color = c.inkMuted)
            }
            Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = type.body, color = c.inkMuted)
        }
    }
    Column(Modifier.fillMaxWidth().padding(Space.s4).padding(bottom = Space.s8), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Stopwatch", style = type.titleMd, color = c.ink)
        Text(stopwatchText(shown), Modifier.padding(vertical = Space.s4), style = type.titleXl.copy(fontFamily = GeistMono, fontFeatureSettings = "tnum"), color = c.ink)
        Row(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            AlbumButton("Reset", {
                banked = 0
                shown = 0
            }, Modifier.weight(1f), enabled = !running && banked > 0)
            AlbumButton(if (running) "Pause" else if (banked > 0) "Resume" else "Start", {
                if (running) banked += SystemClock.elapsedRealtime() - since else since = SystemClock.elapsedRealtime()
                running = !running
            }, Modifier.weight(1f), ButtonVariant.Primary)
        }
    }
}

// ---------- Compass ----------

/** Where the screen's top faces, in degrees from north: the sensor's [azimuth] plus the screen's [rotation] (0 to 3). */
fun facing(azimuth: Float, rotation: Int) = ((azimuth + 90 * rotation) % 360 + 360) % 360

/** [shown] eased a fifth of the way to [target] the short way round: 350° to 10° turns 20°, not back through 180°. */
fun easeTurn(shown: Float, target: Float): Float {
    val delta = ((target - shown) % 360 + 540) % 360 - 180
    return shown + delta * 0.2f
}

/** "N", "NE" ... "NW" for a heading in degrees. */
fun compassPoint(degrees: Float) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((degrees % 360 + 360) % 360 + 22.5f) / 45).toInt() % 8]

@Composable
private fun ColumnScope.CompassFront(onHold: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val sensors = remember { context.getSystemService(SensorManager::class.java) }
    val sensor = remember { sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    val rotation = LocalView.current.display?.rotation ?: 0
    var heading by remember { mutableFloatStateOf(Float.NaN) } // unwrapped, so the dial never spins the long way round
    LifecycleResumeEffect(sensor, rotation) {
        val matrix = FloatArray(9)
        val angles = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, e.values)
                SensorManager.getOrientation(matrix, angles)
                val target = facing(Math.toDegrees(angles[0].toDouble()).toFloat(), rotation)
                heading = if (heading.isNaN()) target else easeTurn(heading, target)
            }

            override fun onAccuracyChanged(s: Sensor, accuracy: Int) {}
        }
        // Only while on screen: the sensors cost battery
        sensor?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onPauseOrDispose { sensors?.unregisterListener(listener) }
    }
    if (sensor == null) {
        LargeTopBar("Compass", Modifier.holdToOpen(onHold))
        Centred { EmptyState(Sym.Public, "No compass on this phone", "This phone doesn't have the sensor a compass needs.") }
        return
    }
    val measurer = rememberTextMeasurer()
    val letter = type.titleMd
    val north = c.flashInk
    Centred {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s6)) {
            val h = if (heading.isNaN()) 0f else (heading % 360 + 360) % 360
            Text(if (heading.isNaN()) " " else "${h.roundToInt() % 360}° ${compassPoint(h)}", style = type.display, color = c.ink)
            Canvas(Modifier.widthIn(max = 320.dp).fillMaxWidth(0.8f).aspectRatio(1f).holdToOpen(onHold).semantics { contentDescription = "Compass" }) {
                val r = size.minDimension / 2
                val centre = center
                // The fixed mark at the top: where the phone points
                drawPath(Path().apply {
                    moveTo(centre.x - 8.dp.toPx(), 0f)
                    lineTo(centre.x + 8.dp.toPx(), 0f)
                    lineTo(centre.x, 12.dp.toPx())
                    close()
                }, c.ink)
                rotate(if (heading.isNaN()) 0f else -heading, centre) {
                    for (i in 0 until 72) {
                        val a = Math.toRadians(i * 5.0 - 90)
                        val long = i % 6 == 0
                        val inner = r - (if (long) 16 else 8).dp.toPx()
                        val outer = r - 2.dp.toPx()
                        drawLine(
                            if (i == 0) north else if (long) c.ink else c.lineStrong,
                            Offset(centre.x + inner * cos(a).toFloat(), centre.y + inner * sin(a).toFloat()),
                            Offset(centre.x + outer * cos(a).toFloat(), centre.y + outer * sin(a).toFloat()),
                            strokeWidth = (if (i == 0) 3 else if (long) 2 else 1).dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    listOf("N", "E", "S", "W").forEachIndexed { i, text ->
                        val a = Math.toRadians(i * 90.0 - 90)
                        val at = r - 36.dp.toPx()
                        val laid = measurer.measure(text, letter.copy(color = if (i == 0) north else c.ink))
                        drawText(laid, topLeft = Offset(
                            centre.x + at * cos(a).toFloat() - laid.size.width / 2,
                            centre.y + at * sin(a).toFloat() - laid.size.height / 2,
                        ))
                    }
                }
            }
            Text("Hold your phone flat, away from metal and magnets.", style = type.caption, color = c.inkMuted)
        }
    }
}

// ---------- Files ----------

@Composable
private fun ColumnScope.FilesFront(onHold: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    // Android's own figures (the phone's nominal size, as Settings shows it); no permission needed for these two
    val space by produceState<Pair<Long, Long>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val stats = context.getSystemService(StorageStatsManager::class.java)
                stats.getTotalBytes(StorageManager.UUID_DEFAULT) to stats.getFreeBytes(StorageManager.UUID_DEFAULT)
            }.getOrNull()
        }
    }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    LargeTopBar("Files")
    Column(
        Modifier.padding(horizontal = Space.s4).fillMaxWidth().clip(RoundedCornerShape(Space.radiusMd)).background(c.surface)
            .holdToOpen(onHold).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
            Icon(Sym.PhoneAndroid, null, tint = c.inkMuted)
            Column(Modifier.weight(1f)) {
                Text("Internal storage", style = type.body, color = c.ink)
                space?.let { (total, free) -> Text("${size(total - free)} used of ${size(total)}", style = type.caption, color = c.inkMuted) }
            }
        }
        space?.let { (total, free) ->
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.surfaceHigh)) {
                Box(Modifier.fillMaxWidth(((total - free).toFloat() / total).coerceIn(0f, 1f)).fillMaxHeight().background(c.flash, CircleShape))
            }
            Text("${size(free)} free", style = type.caption, color = c.inkMuted)
        }
    }
    Centred { EmptyState(Sym.Folder, "No recent files", "Files you open appear here.") }
}

// ---------- Scanner ----------

@Composable
private fun ColumnScope.ScannerFront(onHold: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    var note by remember { mutableStateOf<String?>(null) }
    LargeTopBar("Scanner")
    Centred { EmptyState(Sym.Description, "Scan a document", "Place the page on a flat surface in good light, then tap Scan.") }
    Column(Modifier.fillMaxWidth().padding(bottom = Space.s8), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(note.orEmpty(), Modifier.heightIn(min = 16.dp), style = type.caption, color = c.ink, textAlign = TextAlign.Center)
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(c.flash).combinedClickable(
                role = Role.Button,
                onClickLabel = "Scan",
                // Opens the phone's camera, as a scanner would. Album leaves the screen, so the disguise is back after.
                // Not startSafely: its toast carries Album's real icon on Android 12 and later
                onClick = { runCatching { context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }.onFailure { note = "No camera app on this phone" } },
                onLongClick = onHold,
            ),
            contentAlignment = Alignment.Center,
        ) { Icon(Sym.PhotoCamera, null, size = 32.dp, tint = c.onFlash) }
        Text("Scan", style = type.label, color = c.ink)
    }
}

// ---------- Authenticator ----------

/**
 * A six-digit sign-in code for [secret] at [seconds] since 1970, changing every 30 seconds: RFC 6238 (HMAC-SHA1), as
 * authenticator apps make them. The secret is this phone's own random one, so the codes are real but sign in to nothing.
 */
fun signInCode(secret: ByteArray, seconds: Long): String {
    val hash = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }.doFinal(ByteBuffer.allocate(8).putLong(seconds / 30).array())
    val at = hash.last().toInt() and 0xf
    val code = (ByteBuffer.wrap(hash, at, 4).int and 0x7fffffff) % 1_000_000
    return "%06d".format(code)
}

@Composable
private fun ColumnScope.AuthenticatorFront(onHold: () -> Unit) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val secret = remember { DisguisePrefs.codeSecret() }
    val seconds by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            value = System.currentTimeMillis() / 1000
            delay(1_000 - System.currentTimeMillis() % 1_000)
        }
    }
    val code = signInCode(secret, seconds)
    val left = 30 - seconds % 30
    LargeTopBar("Authenticator")
    Column(
        Modifier.padding(horizontal = Space.s4).fillMaxWidth().clip(RoundedCornerShape(Space.radiusMd)).background(c.surface).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Text("Work account", style = type.label, color = c.ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${code.take(3)} ${code.drop(3)}",
                Modifier.weight(1f).holdToOpen(onHold),
                style = type.titleXl.copy(fontFamily = GeistMono, fontFeatureSettings = "tnum"),
                color = c.flashInk,
            )
            // What's left of the 30 seconds, as a shrinking disc
            Canvas(Modifier.size(Space.s6).semantics { contentDescription = "Changes in $left seconds" }) {
                drawCircle(c.surfaceHigh)
                drawArc(c.flash, -90f, 360f * left / 30, useCenter = true)
            }
        }
        Text("Sign-in code", style = type.caption, color = c.inkMuted)
    }
    Spacer(Modifier.weight(1f))
    Text(
        "Codes change every 30 seconds and work without a connection.",
        Modifier.fillMaxWidth().padding(Space.s4).padding(bottom = Space.s4),
        style = type.caption, color = c.inkMuted, textAlign = TextAlign.Center,
    )
}

// ---------- Workspace ----------

@Composable
private fun ColumnScope.WorkspaceFront(onHold: () -> Unit) {
    val c = AlbumTheme.colors
    Centred {
        Column(Modifier.padding(horizontal = Space.s6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            DisguiseIcon(Disguise.Workspace, 88.dp, Modifier.padding(bottom = Space.s4).holdToOpen(onHold).semantics { contentDescription = "Workspace" })
            Text("Your workspace is ready", style = AlbumTheme.type.titleLg, color = c.ink, textAlign = TextAlign.Center)
            Text("Apps and files your organisation shares with you appear here.", style = AlbumTheme.type.body, color = c.inkMuted, textAlign = TextAlign.Center)
        }
    }
}
