package com.aditya.album

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy")
private val timeFormat = DateTimeFormatter.ofPattern("EEE, h:mm a")

/** Full-screen swipeable viewer. [items] is the list it was opened from (all photos, or one album). */
@Composable
fun Viewer(items: List<Media>, startId: Long, onClose: () -> Unit) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() } // last photo was deleted
        return
    }
    val context = LocalContext.current
    val start = remember { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    val current = items[pager.currentPage.coerceAtMost(items.lastIndex)]

    BackHandler(onBack = onClose)
    ImmersiveBars(visible = chrome)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { items[it].id }) { page ->
            val m = items[page]
            if (m.isVideo) {
                VideoPage(m, active = pager.settledPage == page, onControls = { chrome = it })
            } else {
                ZoomableAsyncImage(m.uri, m.name, Modifier.fillMaxSize(), onClick = { chrome = !chrome })
            }
        }

        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
                val time = Instant.ofEpochMilli(current.taken).atZone(ZoneId.systemDefault())
                Column {
                    Text(time.format(dateFormat), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(time.format(timeFormat), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).navigationBarsPadding().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Action(Icons.Outlined.Share, "Share") {
                    CrashLog.note("share 1")
                    context.startActivity(shareIntent(listOf(current)))
                }
                Action(if (current.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favourite") {
                    CrashLog.note("favourite ${!current.isFavorite}")
                    confirm.launch(IntentSenderRequest.Builder(favoriteRequest(context.contentResolver, listOf(current), !current.isFavorite)).build())
                }
                Action(Icons.Outlined.Delete, "Delete") {
                    CrashLog.note("trash 1")
                    confirm.launch(IntentSenderRequest.Builder(trashRequest(context.contentResolver, listOf(current))).build())
                }
                Action(Icons.Outlined.Info, "Details") { showInfo = true }
            }
        }
    }
    if (showInfo) InfoSheet(current) { showInfo = false }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
}

/** Plays only while it's the settled page; the player is released as soon as you swipe away. */
@Composable
private fun VideoPage(m: Media, active: Boolean, onControls: (Boolean) -> Unit) {
    if (!active) {
        AsyncImage(Thumb(m.uri), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        return
    }
    val context = LocalContext.current
    // Hardware decoders occasionally choke on odd files; if that happens, retry once with the software decoder.
    var softwareOnly by remember(m.id) { mutableStateOf(false) }
    val player = remember(m.id, softwareOnly) {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        if (softwareOnly) {
            renderers.setMediaCodecSelector { mime, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { it.softwareOnly }
            }
        }
        ExoPlayer.Builder(context, renderers).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    CrashLog.note("video error ${error.errorCodeName} (software=$softwareOnly): ${error.cause?.message}")
                    val decoderProblem = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                    if (decoderProblem && !softwareOnly) softwareOnly = true
                }
            })
            setMediaItem(MediaItem.fromUri(m.uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                setErrorMessageProvider { android.util.Pair(0, "Can't play this video") }
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> onControls(v == View.VISIBLE) })
            }
        },
        update = { it.player = player }, // picks up the software-decoder retry
        // keep the player's timeline above Album's action bar (56 dp) instead of underneath it
        modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 56.dp),
    )
}

/** Hides the status and navigation bars while the viewer's controls are hidden; restores them on exit. */
@Composable
private fun ImmersiveBars(visible: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val dark = isSystemInDarkTheme()
    DisposableEffect(window, visible) {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.isAppearanceLightStatusBars = false // white icons on the black viewer
        if (visible) bars.show(WindowInsetsCompat.Type.systemBars()) else bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars.show(WindowInsetsCompat.Type.systemBars())
            bars.isAppearanceLightStatusBars = !dark
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(m: Media, onDismiss: () -> Unit) {
    val time = Instant.ofEpochMilli(m.taken).atZone(ZoneId.systemDefault())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            InfoRow("Name", m.name)
            InfoRow("Taken", "${time.format(dateFormat)}, ${time.format(timeFormat)}")
            if (m.width > 0) InfoRow("Resolution", "${m.width} × ${m.height}  (${"%.1f".format(m.width * m.height / 1_000_000f)} MP)")
            if (m.isVideo) InfoRow("Length", duration(m.durationMs))
            InfoRow("Size", "%.1f MB".format(m.size / 1_048_576f))
            InfoRow("Folder", m.path.ifEmpty { m.bucketName })
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
