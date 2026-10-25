package com.aditya.album

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Open with Album" from other apps (Files, chats): shows that one photo or video in the normal viewer. */
class ExternalViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // content:// only: an explicit intent could pass https:// (the player would go online) or file:// (sharing crashes)
        val uri = intent.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } ?: return finish()
        val type = intent.type
        AppearancePrefs.load(this)
        CrashLog.note("open external")
        setContent {
            AlbumTheme {
                // The sending app's provider can be slow (cloud files), so its details are read off the main thread.
                val media by produceState<Media?>(null) { value = withContext(Dispatchers.IO) { externalMedia(contentResolver, uri, type) } }
                media?.let { Viewer(listOf(it), it.id, onClose = ::finish, external = true) }
            }
        }
    }
}

/** A viewer item for a URI from another app. Name and size come from OpenableColumns when the provider has them. */
private fun externalMedia(resolver: ContentResolver, uri: Uri, type: String?): Media {
    var name = uri.lastPathSegment.orEmpty()
    var size = 0L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            val nameColumn = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameColumn >= 0) c.getString(nameColumn)?.let { name = it }
                if (sizeColumn >= 0 && !c.isNull(sizeColumn)) size = c.getLong(sizeColumn)
            }
        }
    }.onFailure { CrashLog.note("external details failed: $it") } // still viewable, just without name and size
    val isVideo = (type ?: resolver.getType(uri)).orEmpty().startsWith("video/")
    return Media(
        id = 0, uri = uri, isVideo = isVideo, taken = 0, day = 0, width = 0, height = 0, durationMs = 0,
        bucketId = 0, bucketName = "", isFavorite = false, size = size, name = name, path = "",
    )
}
