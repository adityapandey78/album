package com.aditya.album

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Motion photos are a still with a short MP4 appended to the same file. The XMP near the start of the file says
// how far from the end the video starts. Known formats: Google's Motion Photo 1.0 and its older MicroVideo form.

/**
 * Bytes of a [fileSize]-byte file that hold its motion photo video, going by the file's [xmp];
 * null when the XMP describes no motion photo format Album knows.
 */
fun motionVideoRange(xmp: String, fileSize: Long): LongRange? =
    // Another camera's format goes here as one more `?:`, once a sample file shows how it marks the video
    (containerVideo(xmp, fileSize) ?: microVideo(xmp, fileSize))?.takeIf { !it.isEmpty() && it.first >= 0 && it.last < fileSize }

/**
 * Motion Photo 1.0 (developer.android.com/media/platform/motion-photo-format): Camera:MotionPhoto="1", and a
 * Container:Directory whose MotionPhoto item gives the video's length. The spec puts the video last in the file.
 */
private fun containerVideo(xmp: String, fileSize: Long): LongRange? {
    if (xmpValue(xmp, "MotionPhoto") != "1") return null
    val directory = Regex("""<([\w-]+):Directory\b.*?</\1:Directory>""", RegexOption.DOT_MATCHES_ALL).find(xmp)?.value ?: return null
    val video = directory.split(Regex("""(?=<[\w-]+:Item\b)""")).firstOrNull { xmpValue(it, "Semantic") == "MotionPhoto" } ?: return null
    val length = xmpValue(video, "Length")?.toLongOrNull() ?: return null
    return fileSize - length until fileSize
}

/** Older Google cameras: GCamera:MicroVideo="1", and the video runs from MicroVideoOffset bytes before the end to the end. */
private fun microVideo(xmp: String, fileSize: Long): LongRange? {
    if (xmpValue(xmp, "MicroVideo") != "1") return null
    val fromEnd = xmpValue(xmp, "MicroVideoOffset")?.toLongOrNull() ?: return null
    return fileSize - fromEnd until fileSize
}

/** A property in either XMP spelling, prefix:Name="value" or <prefix:Name>value<. Prefixes differ between cameras. */
private fun xmpValue(xmp: String, name: String): String? =
    Regex("""[\w-]+:$name(?:\s*=\s*"([^"]*)"|>([^<]*)<)""").find(xmp)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()

/** Pixel and older Google cameras write the XMP into the first segments of the file, well within this. */
private const val XMP_SEARCH_BYTES = 64 * 1024

// Keyed by size as well: an in-place edit (rotate) can change the size, and with it where the video starts.
// ponytail: one small entry per photo viewed, never trimmed; bound it if a session ever views tens of thousands.
private val checked = mutableStateMapOf<Pair<Uri, Long>, LongRange?>()

/** The video inside motion photo [m] (bytes of its file); null for other photos, and until the first check of [m] finishes. */
@Composable
fun rememberMotionVideo(m: Media): LongRange? {
    val context = LocalContext.current
    val key = m.uri to m.size
    LaunchedEffect(key) {
        if (m.isVideo || key in checked) return@LaunchedEffect
        checked[key] = withContext(Dispatchers.IO) {
            runCatching { readMotionVideo(context.contentResolver, m.uri) }.onFailure { CrashLog.note("motion check failed: $it") }.getOrNull()
        }
    }
    return checked[key]
}

private fun readMotionVideo(resolver: ContentResolver, uri: Uri): LongRange? {
    val file = resolver.openFileDescriptor(uri, "r") ?: return null
    val size = file.statSize
    val head = ParcelFileDescriptor.AutoCloseInputStream(file).use { it.readNBytes(XMP_SEARCH_BYTES) }
    return motionVideoRange(String(head, Charsets.ISO_8859_1), size) // one char per byte: binary data can't break the decode
}

/** "Motion" badge: tapping it plays the video. While it plays, a second button turns the sound on or off. */
@Composable
fun MotionChip(playing: Boolean, muted: Boolean, onPlay: () -> Unit, onMute: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClickLabel = "Play", onClick = onPlay)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.MotionPhotosOn, null, Modifier.padding(end = 6.dp).size(18.dp), tint = Color.White)
            Text("Motion", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        if (playing) {
            IconButton(onClick = onMute) {
                Icon(
                    if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    if (muted) "Turn sound on" else "Turn sound off",
                    Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).padding(6.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

/** Plays bytes [video] of [uri] over the still, then calls [onEnd] so the still shows again. Muted unless [muted] is false. */
@Composable
fun MotionPlayer(uri: Uri, video: LongRange, muted: Boolean, onEnd: () -> Unit) {
    val context = LocalContext.current
    val end by rememberUpdatedState(onEnd)
    val player = remember(uri, video) {
        val source = ProgressiveMediaSource.Factory(DataSource.Factory { SliceDataSource(ContentDataSource(context), video) })
            .createMediaSource(MediaItem.fromUri(uri))
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) end()
                }

                override fun onPlayerError(error: PlaybackException) {
                    CrashLog.note("motion error ${error.errorCodeName}: ${error.cause?.message}")
                    end()
                }
            })
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }
    SideEffect { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT) // the still stays visible until the first frame
                this.player = player
            }
        },
        update = { it.player = player }, // a new player (e.g. after a rotate) replaces the released one
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Reads only bytes [range] of a file, as if they were the whole file: the MP4 inside a motion photo. */
private class SliceDataSource(private val file: DataSource, private val range: LongRange) : DataSource by file {
    override fun open(dataSpec: DataSpec): Long {
        val left = range.last + 1 - range.first - dataSpec.position
        return file.open(dataSpec.subrange(range.first, if (dataSpec.length == C.LENGTH_UNSET.toLong()) left else minOf(dataSpec.length, left)))
    }
}
