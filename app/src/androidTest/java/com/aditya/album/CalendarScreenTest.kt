package com.aditya.album

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CalendarScreenTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.now().toEpochDay()
    private val media = List(3) { i -> // two photos today, one yesterday
        Media(
            i.toLong(), Uri.parse("content://test/$i"), false, taken = 0, day = today - i / 2, width = 0, height = 0,
            durationMs = 0, bucketId = 0, bucketName = "", isFavorite = false, size = 0, name = "", path = "",
        )
    }

    @Test fun announcesTodayWithItsCountAndReportsTheTappedDay() {
        var tapped: Long? = null
        compose.setContent { AlbumTheme(Look.Clean) { CalendarScreen(media, onBack = {}) { tapped = it } } }
        compose.onNodeWithContentDescription("Today, ${dayDescription(today, 2)}").performClick()
        compose.runOnIdle { assertEquals(today, tapped) }
    }
}
