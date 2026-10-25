package com.aditya.album

import androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL
import androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL
import androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
import androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
import androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
import androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
import androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE
import androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE
import androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED
import org.junit.Assert.assertEquals
import org.junit.Test

class RotateTest {
    private fun turns(start: Int, clockwise: Boolean) = generateSequence(start) { rotatedOrientation(it, clockwise) }.take(5).toList()

    @Test fun fourQuarterTurnsComeBackAround() {
        assertEquals(
            listOf(ORIENTATION_NORMAL, ORIENTATION_ROTATE_90, ORIENTATION_ROTATE_180, ORIENTATION_ROTATE_270, ORIENTATION_NORMAL),
            turns(ORIENTATION_NORMAL, clockwise = true),
        )
        assertEquals(
            listOf(ORIENTATION_NORMAL, ORIENTATION_ROTATE_270, ORIENTATION_ROTATE_180, ORIENTATION_ROTATE_90, ORIENTATION_NORMAL),
            turns(ORIENTATION_NORMAL, clockwise = false),
        )
    }

    @Test fun mirroredPhotosStayMirrored() {
        // flip, then turn clockwise: a horizontal flip turned a quarter is a flip across the other diagonal (transverse)
        assertEquals(
            listOf(ORIENTATION_FLIP_HORIZONTAL, ORIENTATION_TRANSVERSE, ORIENTATION_FLIP_VERTICAL, ORIENTATION_TRANSPOSE, ORIENTATION_FLIP_HORIZONTAL),
            turns(ORIENTATION_FLIP_HORIZONTAL, clockwise = true),
        )
    }

    @Test fun leftUndoesRight() {
        for (tag in 1..8) assertEquals(tag, rotatedOrientation(rotatedOrientation(tag, clockwise = true), clockwise = false))
    }

    @Test fun missingOrBrokenTagsCountAsUpright() {
        assertEquals(ORIENTATION_ROTATE_90, rotatedOrientation(ORIENTATION_UNDEFINED, clockwise = true))
        assertEquals(ORIENTATION_ROTATE_270, rotatedOrientation(42, clockwise = false))
    }
}

class OrientationBytesTest {
    @Test fun keepsBigEndianOrder() = org.junit.Assert.assertArrayEquals(byteArrayOf(0, 6), orientationBytes(byteArrayOf(0, 1), 6))

    @Test fun keepsLittleEndianOrder() = org.junit.Assert.assertArrayEquals(byteArrayOf(8, 0), orientationBytes(byteArrayOf(3, 0), 8))

    @Test fun refusesAnythingThatIsNotAValidOrientationShort() {
        org.junit.Assert.assertNull(orientationBytes(byteArrayOf(0, 0), 6)) // undefined: byte order unknowable
        org.junit.Assert.assertNull(orientationBytes(byteArrayOf(0, 9), 6)) // not an orientation
        org.junit.Assert.assertNull(orientationBytes(byteArrayOf(0, 0, 0, 1), 6)) // stored as a long
        org.junit.Assert.assertNull(orientationBytes(byteArrayOf(0, 1), 0)) // invalid target
    }
}
