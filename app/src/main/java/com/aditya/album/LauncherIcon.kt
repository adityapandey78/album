package com.aditya.album

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ComponentEnabledSetting

/**
 * The home-screen icon's sun follows the accent, unless Album is disguised. The manifest has one launcher entry per
 * accent (activity-alias ".Icon<Accent>") and one per disguise (".Disguise<Name>", with its own name), icons from
 * tools/app_icon.py, and exactly one is enabled. Switching entries makes Android close the task the old one opened, so
 * Album only switches once it is out of sight ([MainActivity.onStop]).
 */
object LauncherIcon {
    private val namespace = MainActivity::class.java.name.substringBeforeLast('.')

    /** Every launcher entry's alias name, accents first. */
    val entries = Accent.entries.map { "Icon${it.name}" } + Disguise.entries.map { "Disguise${it.name}" }

    /** The one entry to enable: the disguise's when there is one, else the accent's. */
    fun entryFor(accent: Accent, disguise: Disguise?) = if (disguise != null) "Disguise${disguise.name}" else "Icon${accent.name}"

    /**
     * Enables the entry for [accent], or for the disguise ([DisguisePrefs]), and disables the others, unless that's how
     * they already are. A disguise also switches off what would show Album's name or photos outside it: "Open with
     * Album" for other apps' files, and the Memories widget (Android removes placed ones).
     */
    fun sync(context: Context, accent: Accent) {
        val pm = context.packageManager
        fun component(name: String) = ComponentName(context.packageName, "$namespace.$name")
        val disguise = DisguisePrefs.active(context)
        val want = entryFor(accent, disguise)
        val outside = listOf(ComponentName(context, ExternalViewerActivity::class.java), ComponentName(context, WidgetConfigureActivity::class.java)) +
            widgetReceivers.map { ComponentName(context, it) }
        val outsideState = if (disguise != null) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        runCatching {
            fun enabled(name: String) = when (pm.getComponentEnabledSetting(component(name))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == "IconLime" // the manifest's choice
                else -> false
            }
            if (entries.all { enabled(it) == (it == want) } && outside.all { pm.getComponentEnabledSetting(it) == outsideState }) return
            CrashLog.note("launcher icon: $want")
            // One call, so there's never a moment with no entry or two
            pm.setComponentEnabledSettings(
                entries.map {
                    val state = if (it == want) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    ComponentEnabledSetting(component(it), state, PackageManager.DONT_KILL_APP)
                } + outside.map { ComponentEnabledSetting(it, outsideState, PackageManager.DONT_KILL_APP) },
            )
        }.onFailure { CrashLog.note("launcher icon: $it") }
    }
}
