package com.aditya.album.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records the code Album runs at startup, in the grid, the fast scroller and the viewer. ./gradlew :app:generateBaselineProfile */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(PACKAGE) {
        startAlbum()
        flingGrid()
        // The thumb only shows mid-scroll and UiAutomator sometimes misses it; the profile is still worth having without it
        runCatching { dragFastScroller() }
        openAndClosePhoto()
    }
}
