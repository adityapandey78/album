package com.aditya.album

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache

class AlbumApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        getSharedPreferences("settings", MODE_PRIVATE) // starts the async load early so MainActivity never waits on disk
        if (isDebuggable()) {
            // Debug builds only: log slow disk/network work on the main thread and leaked resources.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        SmartIndex.start(this)
        Collections.start(this)
        People.start(this)
    }

    // WorkManager starts on first use (the smart index, off the main thread) instead of at every launch. See the manifest.
    override val workManagerConfiguration get() = Configuration.Builder().build()

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components {
            add(ThumbFetcher.Factory())
            add(ThumbKeyer())
        }
        // Coil's default is a fifth of the Java heap limit, about 80 grid thumbnails (3 screens), so flinging back up
        // reloaded almost every photo and cells flashed grey. Bitmap pixels live outside the Java heap anyway.
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.5).build() }
        .build()
}

fun Context.isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
