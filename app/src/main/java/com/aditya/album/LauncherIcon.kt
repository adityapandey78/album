package com.aditya.album

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ComponentEnabledSetting

/**
 * The home-screen icon's sun follows the accent. The manifest has one launcher entry per accent (activity-alias
 * ".Icon<Accent>", icons from tools/app_icon.py) and exactly one is enabled. Switching entries makes Android close the
 * task the old one opened, so Album only switches once it is out of sight ([MainActivity.onStop]).
 */
object LauncherIcon {
    private val namespace = MainActivity::class.java.name.substringBeforeLast('.')

    /** Enables the entry for [accent] and disables the others, unless that's how they already are. */
    fun sync(context: Context, accent: Accent) {
        val pm = context.packageManager
        fun entry(a: Accent) = ComponentName(context.packageName, "$namespace.Icon${a.name}")
        runCatching {
            fun enabled(a: Accent) = when (pm.getComponentEnabledSetting(entry(a))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> a == Accent.Lime // the manifest's choice
                else -> false
            }
            if (Accent.entries.all { enabled(it) == (it == accent) }) return
            CrashLog.note("launcher icon: ${accent.name}")
            // One call, so there's never a moment with no entry or two
            pm.setComponentEnabledSettings(
                Accent.entries.map {
                    val state = if (it == accent) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    ComponentEnabledSetting(entry(it), state, PackageManager.DONT_KILL_APP)
                },
            )
        }.onFailure { CrashLog.note("launcher icon: $it") }
    }
}
