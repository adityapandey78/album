package com.aditya.album.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

const val PACKAGE = "com.aditya.album"

// Compose test tags, exposed as resource ids by MediaGrid (Timeline.kt) and FastScroller.kt
private const val GRID = "media_grid"
private const val THUMB = "fast_scroller"

/** Opens Photos from scratch: no process, so no thumbnails in memory. Benchmark installs start without photo access. */
fun MacrobenchmarkScope.startAlbum() {
    listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "ACCESS_MEDIA_LOCATION").forEach {
        device.executeShellCommand("pm grant $packageName android.permission.$it")
    }
    killProcess()
    startActivityAndWait()
    checkNotNull(device.wait(Until.findObject(By.res(GRID)), 10_000)) { "Photos grid didn't appear: no photos on the device?" }
}

private fun MacrobenchmarkScope.grid(): UiObject2 = device.findObject(By.res(GRID))

/** Three hard flings down the timeline, then three back up through photos that were just on screen. */
fun MacrobenchmarkScope.flingGrid() {
    val grid = grid()
    grid.setGestureMargin(device.displayWidth / 5) // keep the finger off the fast scroller and the bars
    repeat(3) { grid.fling(Direction.DOWN) }
    repeat(3) { grid.fling(Direction.UP) }
    device.waitForIdle()
}

/** Drags the fast scroller thumb from the top to the bottom of the timeline and back, about 1.5 s each way. */
fun MacrobenchmarkScope.dragFastScroller() {
    grid().scroll(Direction.DOWN, 0.2f) // the thumb only shows while the grid scrolls
    val thumb = checkNotNull(device.wait(Until.findObject(By.res(THUMB)), 2_000)) { "No fast scroller: library too small?" }
    val x = thumb.visibleCenter.x
    device.swipe(x, thumb.visibleCenter.y, x, device.displayHeight * 9 / 10, 300)
    device.swipe(x, device.findObject(By.res(THUMB)).visibleCenter.y, x, device.displayHeight / 10, 300)
    device.waitForIdle()
}

/** Opens the first photo (fly-in), swipes to the next two and goes back to the grid (fly-back). */
fun MacrobenchmarkScope.openAndClosePhoto() {
    grid().findObject(By.res("media_tile")).click() // not just any clickable: the title bar's buttons come first
    device.waitForIdle()
    val y = device.displayHeight / 2
    repeat(2) {
        device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 10)
        device.waitForIdle()
    }
    device.pressBack()
    device.waitForIdle()
}
