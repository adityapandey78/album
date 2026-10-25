package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlideshowTest {
    private fun next(videos: String, from: Int) = nextPhoto(videos.length, from) { videos[it] == 'v' } // p = photo, v = video

    @Test fun skipsVideos() {
        assertEquals(0, next("pvvp", 0))
        assertEquals(3, next("pvvp", 1))
    }

    @Test fun loopsBackToTheStart() {
        assertEquals(0, next("pvv", 1))
        assertEquals(0, next("pvv", 3)) // one past the end, after the last photo
    }

    @Test fun nothingToShowWithoutPhotos() {
        assertNull(next("vv", 0))
        assertNull(next("", 0))
    }
}
