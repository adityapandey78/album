package com.aditya.album.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing of the Photos grid. Run with ./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest (see README).
 * UseIfAvailable: measures with Album's Baseline Profile when the APK has one, else with only the libraries' profiles,
 * which is what a sideloaded install without one runs.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun fling() = measure { flingGrid() }

    /** A first scroll through photos MediaProvider has no thumbnail for yet, so it decodes each original meanwhile. */
    @Test
    fun flingColdThumbnails() = measure(coldThumbnails = true) { flingGrid() }

    @Test
    fun fastScroller() = measure { dragFastScroller() }

    // FrameTimingGfxInfoMetric adds Android's own jank count and slow bitmap uploads (dumpsys gfxinfo)
    @OptIn(ExperimentalMetricApi::class)
    private fun measure(coldThumbnails: Boolean = false, journey: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric(), FrameTimingGfxInfoMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        iterations = 5,
        setupBlock = {
            // MediaProvider's thumbnail cache, which rebuilds itself as photos are viewed
            if (coldThumbnails) device.executeShellCommand("rm -rf /sdcard/Pictures/.thumbnails")
            startAlbum()
        },
        measureBlock = journey,
    )
}
