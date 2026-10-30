package com.aditya.album

import androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL
import androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
import androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
import androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
import androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTest {
    @Test fun dngIsRaw() {
        assertTrue(isRaw("PXL_20241024_140512.RAW-01.MP.COVER.dng"))
        assertTrue(isRaw("IMG_1.DNG"))
        assertFalse(isRaw("IMG_1.jpg"))
    }

    @Test fun androidsDecodeIsTurnedByTheDngsOrientation() {
        // A portrait photo from a sensor that's landscape: 4000 x 3000 raw, orientation 90, decoded on its side
        assertEquals(false to 90, rawTurn(ORIENTATION_ROTATE_90, 4000, 3000, 4000, 3000))
        assertEquals(false to 270, rawTurn(ORIENTATION_ROTATE_270, 2000, 1500, 4000, 3000))
        assertEquals(false to 180, rawTurn(ORIENTATION_ROTATE_180, 4000, 3000, 4000, 3000))
        assertEquals(true to 0, rawTurn(ORIENTATION_FLIP_HORIZONTAL, 4000, 3000, 4000, 3000))
        assertEquals(false to 0, rawTurn(ORIENTATION_NORMAL, 4000, 3000, 4000, 3000))
    }

    @Test fun anAlreadyUprightPictureIsLeftAlone() {
        // An embedded preview that was stored upright already has the turned shape
        assertEquals(false to 0, rawTurn(ORIENTATION_ROTATE_90, 1500, 2000, 4000, 3000))
        // Android's thumbnail from the embedded JPEG one is turned by Android itself
        assertEquals(false to 0, rawTurn(ORIENTATION_ROTATE_180, 256, 192, 4000, 3000, turned = true))
        // Raw size unknown: trust the orientation
        assertEquals(false to 90, rawTurn(ORIENTATION_ROTATE_90, 1500, 2000, 0, 0))
    }
}
