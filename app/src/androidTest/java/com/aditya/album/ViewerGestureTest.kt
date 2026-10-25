package com.aditya.album

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerGestureTest {
    @get:Rule val compose = createComposeRule()

    private val media = List(3) { i ->
        Media(
            i.toLong(), Uri.parse("content://test/$i"), false, taken = 1, day = 0, width = 0, height = 0,
            durationMs = 0, bucketId = 0, bucketName = "", isFavorite = false, size = 0, name = "", path = "",
        )
    }
    private var closed = false

    private fun showViewer() = compose.setContent {
        AlbumTheme(Look.Clean) { Viewer(media, startId = 1, onClose = { closed = true }) }
    }

    @Test fun swipeDownCloses() {
        showViewer()
        compose.onRoot().performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun swipeUpOpensDetails() {
        showViewer()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onNodeWithText("Details").assertExists()
        compose.runOnIdle { assertFalse(closed) }
    }

    @Test fun sidewaysSwipePagesInsteadOfClosing() {
        showViewer()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.runOnIdle { assertFalse(closed) }
    }
}
