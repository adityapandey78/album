package com.aditya.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Built-in albums shown above the folders. They open through the same album id as folders, and their ids sit
 * outside the Int range, so they can never clash with a folder's BUCKET_ID (a 32-bit path hash).
 */
enum class Special(val id: Long, val title: String, val emptyText: String) {
    Favourites(1L shl 32, "Favourites", "Tap the heart on a photo or video to add it here"),
    Videos(2L shl 32, "Videos", "No videos yet"),
    Trash(3L shl 32, "Recently deleted", "Nothing here. Photos and videos you delete stay here for 30 days, then Android removes them for good."),
}

/** Whether an item belongs in album [albumId]: a folder, or the Favourites or Videos special album. */
fun belongsTo(albumId: Long, bucketId: Long, isFavorite: Boolean, isVideo: Boolean): Boolean = when (albumId) {
    Special.Favourites.id -> isFavorite
    Special.Videos.id -> isVideo
    else -> bucketId == albumId // Trash matches nothing here: trashed items aren't in Gallery.media
}

fun Media.isIn(albumId: Long) = belongsTo(albumId, bucketId, isFavorite, isVideo)

/** Item count for each special album. [trashed] comes from [rememberTrash], since Gallery.media leaves trash out. */
fun specialCounts(media: List<Media>, trashed: Int): Map<Special, Int> =
    Special.entries.associateWith { s -> if (s == Special.Trash) trashed else media.count { it.isIn(s.id) } }

/** Folder albums, with the special albums in a row on top. [onOpen] gets the album id (a folder's or a [Special]'s). */
@Composable
fun AlbumsGrid(
    albums: List<Album>,
    specialCounts: Map<Special, Int>,
    state: LazyGridState,
    onOpen: (Long) -> Unit,
    contentPadding: PaddingValues,
) {
    val spec = LocalLook.current
    LazyVerticalGrid(
        GridCells.Fixed(3),
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "special", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Special.entries.forEach { s ->
                    Column(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(spec.cellCorner + 8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpen(s.id) }
                            .padding(12.dp),
                    ) {
                        Icon(
                            when (s) {
                                Special.Favourites -> Icons.Outlined.FavoriteBorder
                                Special.Videos -> Icons.Outlined.VideoLibrary
                                Special.Trash -> Icons.Outlined.Delete
                            },
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            s.title,
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            minLines = 2, // "Recently deleted" needs two lines in a third of the width; all tiles keep one height
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("${specialCounts[s] ?: 0}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(albums, key = { it.id }) { album ->
            Column(Modifier.clickable { onOpen(album.id) }) {
                AsyncImage(
                    Thumb(album.cover.uri),
                    contentDescription = null,
                    Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(spec.cellCorner + 8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
                Text(
                    album.name,
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${album.count}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
