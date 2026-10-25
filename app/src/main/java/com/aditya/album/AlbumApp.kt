package com.aditya.album

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode

class AlbumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        getSharedPreferences("settings", MODE_PRIVATE) // starts the async load early so MainActivity never waits on disk
        if (isDebuggable()) {
            // Debug builds only: log slow disk/network work on the main thread and leaked resources.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
    }
}

fun Context.isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
