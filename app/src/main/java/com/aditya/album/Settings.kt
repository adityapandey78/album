package com.aditya.album

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Settings: Appearance, Privacy, Behaviour and Motion, then rows that open Updates, Diagnostics and About as pages of
// their own. Every choice applies the moment it's made (AppearancePrefs.update saves it too), so there is no Save button.

private val Columns = listOf(3, 4, 5, 7)

/**
 * The Settings page, pushed over the tabs with its own top bar and insets. [onOpenTrash] opens Recently deleted, which
 * stays reachable here even when Collections hides it. Appearance, Updates, Diagnostics and About open pages of their own,
 * so this one stays a short page of choices.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    onOpenTrash: () -> Unit,
    onHiddenItems: () -> Unit,
    onOpenSafe: () -> Unit,
    onDescribe: () -> Unit,
    onUpdates: () -> Unit,
    onDiagnostics: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val a = AlbumTheme.spec.appearance
    val logs by produceState<Int?>(null) { value = withContext(Dispatchers.IO) { CrashLog.files().size } }
    val describe by Embeddings.state.collectAsState()

    @Composable
    fun Title(text: String, top: Dp = Space.s8) = Text(text, Modifier.padding(top = top), style = type.titleLg, color = c.ink)

    @Composable
    fun Subhead(text: String) = SectionHead(text, Modifier.bleed(Space.s4))

    SettingsPage("Settings", onBack) {
        // Appearance has a page of its own: its choices are many and visual, and this page stays a short list
        MenuRow(Sym.Palette, "Appearance", onAppearance, Modifier.bleed(Space.s1), sub = appearanceSummary(a))

        Title("Privacy")
        PrivacyRows(onHiddenItems, Modifier.bleed(Space.s1), onOpenSafe)
        SwitchRow(
            "Hide private places", "Leaves Recently deleted, Private safe and Hidden items out of Collections. Open them from here.", a.hidePrivate,
            { v -> AppearancePrefs.update { it.copy(hidePrivate = v) } }, icon = Sym.VisibilityOff,
        )
        MenuRow(Sym.RestoreFromTrash, "Recently deleted", onOpenTrash, Modifier.bleed(Space.s1))

        Title("Behaviour")
        MediaManagementRow()
        EditingAppRow(Modifier.bleed(Space.s1))
        RotateSaveRow(Modifier.bleed(Space.s1))
        SwitchRow(
            "Haptic feedback", "Ticks on select, pinch and swipe.", a.haptics,
            { v -> AppearancePrefs.update { it.copy(haptics = v) } }, icon = Sym.Vibration,
        )

        Title("Search")
        MenuRow(Sym.Search, "Describe-it search", onDescribe, Modifier.bleed(Space.s1), sub = describeSummary(describe))

        // A section's gap without a title: these rows are pages, not choices
        Spacer(Modifier.height(Space.s4))
        MenuRow(Sym.SystemUpdate, "Updates", onUpdates, Modifier.bleed(Space.s1), sub = "Version ${remember { Updater.currentVersion(context) }}")
        MenuRow(Sym.BugReport, "Diagnostics", onDiagnostics, Modifier.bleed(Space.s1), sub = logs?.let(::crashLogs))
        MenuRow(Sym.Info, "About", onAbout, Modifier.bleed(Space.s1), sub = "Developer and open-source licences")
    }
}

private fun appearanceSummary(a: Appearance) = "${a.theme.label} theme, ${a.accent.label} accent, ${a.font.label} font"

/**
 * Appearance, pushed from Settings or the Library's menu: theme, accent, font, look, gallery style and columns, then
 * motion. Every choice applies the moment it's made. [columns] is the Library grid's live column count.
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit, columns: MutableIntState) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val a = AlbumTheme.spec.appearance

    @Composable
    fun Subhead(text: String) = SectionHead(text, Modifier.bleed(Space.s4))

    SettingsPage("Appearance", onBack, top = 0.dp) {
        Subhead("Theme")
        ChoiceGroup(
            ThemeMode.entries.map { mode ->
                Choice(mode, mode.label) {
                    if (mode == ThemeMode.System) {
                        // Half light, half dark: System follows the phone
                        Box {
                            StyleSwatch(theme = ThemeMode.Light)
                            StyleSwatch(Modifier.drawWithContent { clipRect(left = size.width / 2) { this@drawWithContent.drawContent() } }, theme = ThemeMode.Dark)
                        }
                    } else StyleSwatch(theme = mode)
                }
            },
            a.theme,
        ) { v -> AppearancePrefs.update { it.copy(theme = v) } }
        Subhead("Accent")
        AccentPicker(a.accent) { v -> AppearancePrefs.update { it.copy(accent = v) } }
        Subhead("Font")
        ChoiceGroup(
            AppFont.entries.map { f ->
                Choice(f, f.label, f.blurb) {
                    // The font's own letters on a swatch's ground
                    val style = remember(f) { AlbumType(edge = false, f).titleLg }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                        Text("Aa", style = style, color = c.ink)
                    }
                }
            },
            a.font,
        ) { v -> AppearancePrefs.update { it.copy(font = v) } }
        Subhead("Look")
        ChoiceGroup(Look.entries.map { Choice(it, it.label, it.blurb) { StyleSwatch(look = it) } }, a.look) { v ->
            AppearancePrefs.update { it.copy(look = v) }
        }
        Subhead("Gallery style")
        ChoiceGroup(GalleryStyle.entries.map { Choice(it, it.label, it.blurb) { StyleSwatch(layout = it) } }, a.gallery) { v ->
            AppearancePrefs.update { it.copy(gallery = v) }
        }
        Subhead("Columns")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            Columns.forEach { n -> Chip("$n", { columns.intValue = n }, selected = columns.intValue == n) }
        }
        Text("Or pinch the grid.", style = type.caption, color = c.inkMuted)

        Text("Motion", Modifier.padding(top = Space.s8), style = type.titleLg, color = c.ink)
        SwitchRow(
            "Remove animations",
            "Screens swap with a quick fade; nothing slides, zooms or bounces. " +
                if (a.still == null) "Following your phone's setting." else "Set here, overriding your phone.",
            AlbumTheme.spec.still, // what's in effect, the phone's setting included
            { v -> AppearancePrefs.update { it.copy(still = v) } },
            icon = Sym.MotionPhotosOn,
        )
        if (a.still != null) {
            AlbumButton(
                "Follow the phone again", { AppearancePrefs.update { it.copy(still = null) } },
                Modifier.offset(x = -Space.s3), variant = ButtonVariant.Quiet, // the label lines up with the text above
            )
        }
        Subhead("Tab transition")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            TabMotion.entries.forEach { m -> Chip(m.label, { AppearancePrefs.update { it.copy(tabMotion = m) } }, selected = a.tabMotion == m) }
        }
    }
}

/** A page of Settings, or one pushed from it: the collapsed top bar over a scrolling column at the screen's margin. */
@Composable
private fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    scroll: ScrollState = rememberScrollState(),
    top: Dp = Space.s4, // 0 when the page opens on a section head, which has its own space above
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(AlbumTheme.colors.canvas).systemBarsPadding()) {
        CollapsedTopBar(title, onBack = onBack)
        Column(
            Modifier.verticalScroll(scroll).padding(start = Space.s4, end = Space.s4, top = top, bottom = Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
            content = content,
        )
    }
}

/**
 * Lets a full-width component that pads its own sides (SectionHead, MenuRow) line up with this screen's margin instead of
 * adding to it: it's measured [x] wider on each side and drawn that far out.
 */
private fun Modifier.bleed(x: Dp) = layout { measurable, constraints ->
    val extra = (x * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

// ---------- Appearance controls ----------

private class Choice<T>(val value: T, val label: String, val sublabel: String? = null, val preview: @Composable () -> Unit)

/**
 * A row of visual choices where exactly one is picked: theme, look and gallery style. The picked one gets a 2dp flash-ink
 * outline and a flash check, so the choice never rests on colour alone. Choosing applies at once.
 */
@Composable
private fun <T> ChoiceGroup(options: List<Choice<T>>, value: T, onChange: (T) -> Unit) {
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val shape = RoundedCornerShape(Space.radiusMd)
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        options.forEach { o ->
            val on = o.value == value
            Column(
                Modifier.weight(1f).clip(shape).selectable(on, role = Role.RadioButton, onClick = { onChange(o.value) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.padding(bottom = Space.s1).fillMaxWidth().clip(shape).border(if (on) 2.dp else 1.dp, if (on) c.flashInk else c.line, shape)) {
                    o.preview()
                    if (on) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(Space.s1).size(Space.check)
                                .background(c.flash, CircleShape).border(2.dp, c.onFlash, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Sym.Check, null, size = Space.iconSm, tint = c.onFlash) }
                    }
                }
                Text(o.label, style = type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                o.sublabel?.let { Text(it, style = type.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * The accent discs. The picked one gets a ring and a check; Wallpaper (Android's dynamic colours) shows as a disc of three
 * accents since its real colours depend on the wallpaper.
 */
@Composable
private fun AccentPicker(value: Accent, onChange: (Accent) -> Unit) {
    val c = AlbumTheme.colors
    val mode = resolvedTheme(AlbumTheme.spec.appearance.theme)
    fun fill(accent: Accent) = albumColors(mode, accent).flash
    val wallpaper = Brush.sweepGradient(
        0f to fill(Accent.Coral), 1 / 3f to fill(Accent.Coral),
        1 / 3f to fill(Accent.Sky), 2 / 3f to fill(Accent.Sky),
        2 / 3f to fill(Accent.Amber), 1f to fill(Accent.Amber),
    )
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
        Accent.entries.forEach { accent ->
            val on = accent == value
            Column(
                Modifier.widthIn(min = Space.target).clip(RoundedCornerShape(Space.radiusMd))
                    .selectable(on, role = Role.RadioButton, onClick = { onChange(accent) }).padding(vertical = Space.s1),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s1),
            ) {
                // A 40dp disc inside a 2dp ring with a 2dp gap
                Box(
                    Modifier.size(Space.target).border(2.dp, if (on) c.ink else Color.Transparent, CircleShape).padding(Space.s1)
                        .background(if (accent == Accent.Wallpaper) wallpaper else SolidColor(fill(accent)), CircleShape)
                        .border(1.dp, c.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // Wallpaper isn't a real accent here, so albumColors gives it lime's on-colour, which reads on all three
                    if (on) Icon(Sym.Check, null, size = Space.iconSm, tint = albumColors(mode, accent).onFlash)
                }
                Text(accent.label, style = AlbumTheme.type.caption, color = c.ink)
            }
        }
    }
}

// Stand-ins for photos in the swatches: the design's sample skies. They are pictures, not UI, so they don't follow the theme.
private val SamplePhotos = listOf(0xFFF2B279, 0xFF6B3B2A, 0xFFFFFFFF, 0xFFE9C9A0, 0xFFE86A3A, 0xFF9DB8A0, 0xFFD9C3B0, 0xFFA7A9AC, 0xFFCFDCE6)
    .map { Color(it) }

// Justified rows: each photo's share of its row's width, as its aspect ratio would give it
private val JustifiedRows = listOf(listOf(1.5f, 0.75f, 1f), listOf(1f, 1.4f), listOf(0.75f, 1.5f, 0.8f))

/**
 * A small live thumbnail of the app (title bar, a three-column grid, the bottom bar's flash pill) in a [theme], [look] and
 * gallery [layout]; anything left null follows the app. It's drawn inside an [AlbumTheme] of its own, so its colours,
 * gutter and tile corners are the real tokens for that appearance. The bars' sizes are the design's, scaled to a miniature.
 */
@Composable
private fun StyleSwatch(modifier: Modifier = Modifier, theme: ThemeMode? = null, look: Look? = null, layout: GalleryStyle? = null) {
    val a = AlbumTheme.spec.appearance
    AlbumTheme(a.copy(theme = theme ?: a.theme, look = look ?: a.look)) {
        val s = AlbumTheme.spec
        val c = s.colors
        val g = s.gutter
        fun Modifier.photo(i: Int) = clip(RoundedCornerShape(s.tileCorner)).background(SamplePhotos[i % SamplePhotos.size])
        Column(
            modifier.fillMaxWidth().aspectRatio(3f / 4f).background(c.canvas).padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.fillMaxWidth(0.45f).height(6.dp).background(c.ink.copy(alpha = 0.85f), CircleShape))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                val cell = (maxWidth - g * 2) / 3
                // Square cells placed on a three-column grid; whatever doesn't fit the swatch's height is cut off
                fun Modifier.at(col: Int, row: Int, span: Int = 1) =
                    offset(x = (cell + g) * col, y = (cell + g) * row).size(cell * span + g * (span - 1))
                when (layout ?: a.gallery) {
                    GalleryStyle.Grid -> repeat(9) { Box(Modifier.at(it % 3, it / 3).photo(it)) }
                    // The day's best shot at 2x2, then the rest around it
                    GalleryStyle.Mosaic -> listOf(Triple(0, 0, 2), Triple(2, 0, 1), Triple(2, 1, 1), Triple(0, 2, 1), Triple(1, 2, 1), Triple(2, 2, 1))
                        .forEachIndexed { i, (col, row, span) -> Box(Modifier.at(col, row, span).photo(i)) }
                    GalleryStyle.Justified -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(g)) {
                        var i = 0
                        JustifiedRows.forEach { row ->
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(g)) {
                                row.forEach { share -> Box(Modifier.weight(share).fillMaxHeight().photo(i++)) }
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().background(c.surfaceHigh, CircleShape).padding(2.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(0.4f).height(6.dp).background(c.flash, CircleShape))
            }
        }
    }
}

// ---------- Behaviour ----------

/**
 * Android's Media management access. With it, MediaStore's trash, restore and favourite requests complete without a
 * system pop-up: that's why moving to trash offers Undo, and why Delete forever asks inside Album. The switch only shows
 * the state; Android's own settings page changes it.
 */
@Composable
private fun MediaManagementRow() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(MediaStore.canManageMedia(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = MediaStore.canManageMedia(context) // the user changes it in system settings
        onPauseOrDispose {}
    }
    SwitchRow(
        "Delete and favourite without pop-ups",
        "Uses Android's Media management access. Moving to trash still offers Undo, and Delete forever still asks first.",
        allowed,
        {
            CrashLog.note("open media management")
            context.startSafely(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}")))
        },
        icon = Sym.Bolt,
    )
}

// ---------- Search ----------

private fun describeSummary(state: DescribeState) = when (state) {
    DescribeState.NotInstalled -> "Find photos by describing them. One-time download"
    is DescribeState.Downloading -> "Downloading, ${(state.fraction * 100).toInt()}%"
    is DescribeState.Indexing -> "Getting your photos ready"
    DescribeState.Ready -> "On"
    is DescribeState.Failed -> "The download didn't finish"
}

/** Describe-it search, pushed from Settings: what it does, the one-time download and its progress, or Remove. */
@Composable
fun DescribeScreen(onBack: () -> Unit) = SettingsPage("Describe-it search", onBack) { DescribeSearchRow() }

// ---------- Updates ----------

/** Updates, pushed from Settings: the installed version and the check for a newer one. */
@Composable
fun UpdatesScreen(onBack: () -> Unit) = SettingsPage("Updates", onBack) { UpdateSection() }

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    val current = remember { Updater.currentVersion(context) }
    var status by remember { mutableStateOf("You have version $current") }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Text(Updater.installMessage ?: status, style = type.body, color = c.inkMuted)
    release?.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.caption, color = c.ink) }
    // Download progress is information, not decoration, so it moves even with Remove animations on
    progress?.let { p ->
        Box(Modifier.fillMaxWidth().height(Space.s1).clip(CircleShape).background(c.surfaceHigh)) {
            Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(c.flash))
        }
    }

    val available = release
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        AlbumButton(
            if (available == null) "Check for updates" else "Update to ${available.version}",
            onClick = {
                scope.launch {
                    busy = true
                    failed = false
                    Updater.installMessage = null
                    if (available == null) {
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
                    } else if (!context.packageManager.canRequestPackageInstalls()) {
                        // One-time Android permission: "Allow from this source" for Album
                        context.startSafely(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        status = "Turn on \"Allow from this source\", come back, and tap Update again"
                    } else {
                        CrashLog.note("update to ${available.version}")
                        status = "Downloading ${available.version}…"
                        progress = 0f
                        status = runCatching {
                            withContext(Dispatchers.IO) { Updater.downloadAndInstall(context, available) { progress = it } }
                        }.fold(
                            onSuccess = { "Confirm on the next screen. Album will close and update." },
                            onFailure = {
                                failed = true
                                "Update failed: ${it.message}"
                            },
                        )
                        progress = null
                    }
                    busy = false
                }
            },
            // The one primary button on this page, and only once there is an update to install
            variant = if (available == null) ButtonVariant.Tonal else ButtonVariant.Primary,
            icon = if (available == null) null else Sym.SystemUpdate,
            enabled = !busy,
        )
        // Only when the in-app install didn't work: the release page, to download it another way
        if (available != null && (failed || Updater.installMessage != null)) {
            AlbumButton(
                "Download page", { context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(available.page))) },
                variant = ButtonVariant.Quiet, icon = Sym.OpenInNew,
            )
        }
    }
}

// ---------- About ----------

private const val LINKEDIN = "adityapandey78"
private const val EMAIL = "adityapandey.2402@gmail.com"

/** About, pushed from Settings: the version, what Album promises, who made it and how to reach them, and the licences. */
@Composable
fun AboutScreen(onBack: () -> Unit, onLicences: () -> Unit) {
    val context = LocalContext.current
    val c = AlbumTheme.colors
    val type = AlbumTheme.type
    SettingsPage("About", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            LogoMark(size = Space.s12)
            Column {
                Text("Album", style = type.titleLg, color = c.ink)
                Text("Version ${remember { Updater.currentVersion(context) }}", style = type.meta, color = c.inkMuted)
            }
        }
        Text(
            "Everything runs on this phone. Nothing is uploaded; the network is used only to check for updates and, when you " +
                "ask, to download describe-it search.",
            style = type.body,
            color = c.inkMuted,
        )
        SectionHead("Developed by Aditya Pandey", Modifier.bleed(Space.s4))
        // Both open another app (browser, mail); Album itself still goes online only for updates
        MenuRow(
            Sym.Link, "LinkedIn", { context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/in/$LINKEDIN"))) },
            Modifier.bleed(Space.s1), sub = LINKEDIN, trailing = Sym.OpenInNew,
        )
        MenuRow(
            Sym.Mail, "Email", { context.startSafely(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL"))) },
            Modifier.bleed(Space.s1), sub = EMAIL, trailing = Sym.OpenInNew,
        )
        Spacer(Modifier.height(Space.s4))
        MenuRow(Sym.Description, "Open-source licences", onLicences, Modifier.bleed(Space.s1))
    }
}

private class Licence(val what: String, val from: String, val licence: String, val file: String, val icon: Sym)

// What Album ships or downloads, who made it, under which licence, and that licence's text (licenses/ in the repo, in the
// app's assets). GeoNames' CC BY 4.0 licence asks for credit people can see, not only in the repo.
private val Licences = listOf(
    Licence("Bricolage Grotesque font", "Bricolage Grotesque Project Authors", "SIL Open Font License 1.1", "bricolage-grotesque-OFL.txt", Sym.TextFields),
    Licence("Figtree font", "Figtree Project Authors", "SIL Open Font License 1.1", "figtree-OFL.txt", Sym.TextFields),
    Licence("Geist Mono font", "Geist Project Authors", "SIL Open Font License 1.1", "geist-mono-OFL.txt", Sym.TextFields),
    Licence("Inter font", "Inter Project Authors", "SIL Open Font License 1.1", "inter-OFL.txt", Sym.TextFields),
    Licence("Nunito font", "Nunito Project Authors", "SIL Open Font License 1.1", "nunito-OFL.txt", Sym.TextFields),
    Licence("Lora font", "Lora Project Authors", "SIL Open Font License 1.1", "lora-OFL.txt", Sym.TextFields),
    Licence("Material Symbols icons", "Google", "Apache License 2.0", "material-symbols-Apache-2.0.txt", Sym.Category),
    Licence("Face detection (YuNet)", "OpenCV Zoo", "MIT License", "yunet-MIT.txt", Sym.Face),
    Licence("Face recognition (EdgeFace-S)", "Idiap Research Institute", "CC BY-NC-SA 4.0, non-commercial", "edgeface-CC-BY-NC-SA-4.0.txt", Sym.Face),
    Licence("Place names", "GeoNames, geonames.org", "CC BY 4.0", "geonames-CC-BY-4.0.txt", Sym.LocationOn),
    Licence("Photo hashing (PDQ)", "Meta ThreatExchange", "BSD License", "pdq-BSD.txt", Sym.ContentCopy),
    Licence("Describe-it search model", "Meta Perception Encoder, downloaded on request", "Apache License 2.0", "perception-encoder-Apache-2.0.txt", Sym.Search),
    Licence("Describe-it search vocabulary", "OpenAI CLIP", "MIT License", "clip-bpe-vocabulary-MIT.txt", Sym.MenuBook),
)

/** The open-source licences, pushed from About. Each opens its full text. */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<Licence?>(null) }
    open?.let { l ->
        TextPage(l.what, onBack = { open = null }) { context.assets.open(l.file).bufferedReader().use { it.readText() } }
        return
    }
    SettingsPage("Open-source licences", onBack) {
        Licences.forEach { l -> MenuRow(l.icon, l.what, { open = l }, Modifier.bleed(Space.s1), sub = "${l.from} · ${l.licence}") }
    }
}

// ---------- Diagnostics ----------

private fun crashLogs(count: Int) = when (count) {
    0 -> "No crash logs"
    1 -> "1 crash log"
    else -> "$count crash logs"
}

/** Diagnostics, pushed from Settings: the crash logs kept on this phone, to read, export or clear. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = AlbumTheme.colors
    val scroll = rememberScrollState() // above the log viewer's early return, so closing a log comes back to the same place
    var logs by remember { mutableStateOf<List<Pair<File, Long>>?>(null) } // null until read, so "No crash logs" never flashes
    var openLog by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { logs = withContext(Dispatchers.IO) { CrashLog.files().map { it to it.length() } } }

    openLog?.let { file ->
        TextPage(file.name, onBack = { openLog = null }) { file.readText() }
        return
    }
    SettingsPage("Diagnostics", onBack, scroll) {
        logs?.let { list ->
            Text(
                crashLogs(list.size) + " on this phone" + if (list.size > 1) ", newest first" else "",
                style = AlbumTheme.type.body,
                color = c.inkMuted,
            )
            list.forEach { (file, size) ->
                MenuRow(Sym.BugReport, file.name, { openLog = file }, Modifier.bleed(Space.s1), sub = "${(size + 1023) / 1024} KB")
            }
            if (list.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    AlbumButton("Export to Downloads", {
                        scope.launch {
                            val name = withContext(Dispatchers.IO) { CrashLog.exportToDownloads(context) }
                            Toast.makeText(context, name?.let { "Saved to Downloads/$it" } ?: "No logs to export", Toast.LENGTH_SHORT).show()
                        }
                    }, icon = Sym.Download)
                    AlbumButton("Clear logs", {
                        scope.launch {
                            withContext(Dispatchers.IO) { CrashLog.clear() }
                            logs = emptyList()
                        }
                    })
                }
            }
        }
        if (context.isDebuggable()) {
            AlbumButton("Test crash (debug builds only)", { error("Test crash from Settings") }, Modifier.offset(x = -Space.s3), variant = ButtonVariant.Quiet)
        }
    }
}

/** A page of plain, selectable text: a crash log or a licence. [read] runs off the main thread. */
@Composable
private fun TextPage(title: String, onBack: () -> Unit, read: () -> String) {
    val text by produceState("Loading…", title) {
        value = withContext(Dispatchers.IO) { runCatching(read).getOrElse { "Couldn't read this: ${it.message}" } }
    }
    SettingsPage(title, onBack) {
        SelectionContainer { Text(text, style = AlbumTheme.type.meta, color = AlbumTheme.colors.ink) }
    }
}
