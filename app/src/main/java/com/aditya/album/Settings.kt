package com.aditya.album

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(look: Look, onLook: (Look) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(emptyList<Pair<File, Long>>()) }
    var openLog by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { logs = withContext(Dispatchers.IO) { CrashLog.files().map { it to it.length() } } }

    openLog?.let {
        LogViewer(it, onBack = { openLog = null })
        return
    }
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Header("Settings", onBack)

        SectionTitle("Design")
        Look.entries.forEach { l ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = l == look, onClick = { onLook(l) }, role = Role.RadioButton)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = l == look, onClick = null)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(l.label, style = MaterialTheme.typography.titleMedium)
                    Text(l.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionTitle("Confirmations")
        ManageMediaRow()

        SectionTitle("Updates")
        UpdateSection()

        SectionTitle("Diagnostics")
        Text(
            if (logs.isEmpty()) "No crash logs on this phone." else "${logs.size} crash logs on this phone (newest first)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        logs.forEach { (file, size) ->
            Row(Modifier.fillMaxWidth().clickable { openLog = file }.padding(vertical = 12.dp)) {
                Text(file.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${(size + 1023) / 1024} KB", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    val name = withContext(Dispatchers.IO) { CrashLog.exportToDownloads(context) }
                    Toast.makeText(context, name?.let { "Saved to Downloads/$it" } ?: "No logs to export", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Export to Downloads") }
            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { CrashLog.clear() }
                    logs = emptyList()
                }
            }) { Text("Clear") }
        }
        if (context.isDebuggable()) {
            TextButton(onClick = { error("Test crash from Settings") }) { Text("Test crash (debug builds only)") }
        }

        SectionTitle("About")
        // GeoNames' CC BY 4.0 licence asks for credit people can see, not only in the repo
        Text(
            "Place names from GeoNames (geonames.org), CC BY 4.0. Fonts under the SIL Open Font License. " +
                "Everything Album learns about your photos stays on this phone.",
            Modifier.padding(bottom = 32.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = remember { Updater.currentVersion(context) }
    var status by remember { mutableStateOf("You have version $current") }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    release?.notes?.takeIf { it.isNotBlank() }?.let {
        Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
    }

    val available = release
    OutlinedButton(
        modifier = Modifier.padding(top = 12.dp),
        enabled = !busy,
        onClick = {
            if (available != null) {
                // The browser downloads the APK; tapping it in the download notification opens Android's installer.
                CrashLog.note("update to ${available.version}")
                context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(available.url)))
                status = "Downloading ${available.version} in your browser. When it finishes, tap Open, then Update."
                return@OutlinedButton
            }
            scope.launch {
                busy = true
                CrashLog.note("check for updates")
                status = "Checking…"
                status = runCatching { withContext(Dispatchers.IO) { Updater.latest() } }.fold(
                    onSuccess = { latest ->
                        when {
                            latest == null -> "No downloadable update found"
                            Updater.isNewer(latest.version, current) -> {
                                release = latest
                                "Version ${latest.version} is available (you have $current)"
                            }
                            else -> "You're on the latest version ($current)"
                        }
                    },
                    onFailure = { "Couldn't reach GitHub. Check your internet and try again." },
                )
                busy = false
            }
        },
    ) { Text(if (available == null) "Check for updates" else "Update to ${available.version}") }
}

@Composable
private fun LogViewer(file: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val text by produceState("Loading…", file) { value = withContext(Dispatchers.IO) { file.readText() } }
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Header(file.name, onBack)
        SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(top = 24.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
