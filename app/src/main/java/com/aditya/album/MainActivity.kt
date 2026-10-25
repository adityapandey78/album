package com.aditya.album

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            var look by remember { mutableStateOf(Look.entries.find { it.name == prefs.getString("look", null) } ?: Look.Clean) }
            AlbumTheme(look) {
                AlbumShell(look, onLook = {
                    look = it
                    prefs.edit { putString("look", it.name) }
                    CrashLog.note("look ${it.name}")
                })
            }
        }
    }
}

private enum class Tab { Photos, Albums, Explore }

@Composable
private fun AlbumShell(look: Look, onLook: (Look) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Photos) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    if (settingsOpen) {
        SettingsScreen(look, onLook, onBack = { settingsOpen = false })
        return
    }
    val spec = LocalLook.current
    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tab.name, Modifier.weight(1f), fontSize = spec.titleSize, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = {}) { Icon(spec.icons.search, "Search") }
                IconButton(onClick = {
                    settingsOpen = true
                    CrashLog.note("open settings")
                }) { Icon(spec.icons.settings, "Settings") }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val icon = when (t) {
                        Tab.Photos -> spec.icons.photos
                        Tab.Albums -> spec.icons.albums
                        Tab.Explore -> spec.icons.explore
                    }
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            tab = t
                            CrashLog.note("tab ${t.name}")
                        },
                        icon = { Icon(icon, null) },
                        label = { Text(t.name) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.Photos -> PreviewGrid(Modifier.padding(padding))
            Tab.Albums, Tab.Explore -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Coming in Phase ${if (tab == Tab.Albums) 1 else 2}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val swatches = listOf(0xFFB5D4F4, 0xFF9FE1CB, 0xFFF5C4B3, 0xFFFAC775, 0xFFCECBF6, 0xFFC0DD97, 0xFFF4C0D1, 0xFF85B7EB)

// ponytail: coloured tiles only, to compare the 3 looks. Phase 1 replaces this with your real photos.
@Composable
private fun PreviewGrid(modifier: Modifier) {
    val spec = LocalLook.current
    LazyVerticalGrid(
        GridCells.Fixed(4),
        modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(spec.gridGap),
        verticalArrangement = Arrangement.spacedBy(spec.gridGap),
    ) {
        listOf("Today" to 7, "Yesterday" to 14, "Fri, 23 Oct" to 30).forEach { (day, count) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(day, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall)
            }
            items(List(count) { it }) { i ->
                Box(
                    Modifier.aspectRatio(1f)
                        .clip(RoundedCornerShape(spec.cellCorner))
                        .background(Color(swatches[(i * 5 + count) % swatches.size])),
                )
            }
        }
    }
}
