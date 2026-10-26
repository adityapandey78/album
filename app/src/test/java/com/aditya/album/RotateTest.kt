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
import org.junit.Assert.assertTrue
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

class UprightTurnTest {
    @Test fun eachOrientationIsDrawnUprightAsExifSays() {
        // mirror left to right first, then turn clockwise
        assertEquals(false to 0, uprightTurn(ORIENTATION_NORMAL, 0))
        assertEquals(true to 0, uprightTurn(ORIENTATION_FLIP_HORIZONTAL, 0))
        assertEquals(false to 180, uprightTurn(ORIENTATION_ROTATE_180, 0))
        assertEquals(true to 180, uprightTurn(ORIENTATION_FLIP_VERTICAL, 0))
        assertEquals(true to 270, uprightTurn(ORIENTATION_TRANSPOSE, 0))
        assertEquals(false to 90, uprightTurn(ORIENTATION_ROTATE_90, 0))
        assertEquals(true to 90, uprightTurn(ORIENTATION_TRANSVERSE, 0))
        assertEquals(false to 270, uprightTurn(ORIENTATION_ROTATE_270, 0))
    }

    @Test fun aResavedCopyTurnsTheSameAsAPatchedTag() {
        // The two ways of turning a photo must agree, mirrored ones included, or a copy would come out another way round
        for (orientation in 1..8) for (quarters in 0..3) {
            val patched = (1..quarters).fold(orientation) { tag, _ -> rotatedOrientation(tag, clockwise = true) }
            assertEquals("orientation $orientation, $quarters turns", uprightTurn(patched, 0), uprightTurn(orientation, quarters))
        }
    }

    @Test fun leftIsThreeQuartersRightAndBrokenTagsCountAsUpright() {
        assertEquals(false to 270, uprightTurn(ORIENTATION_NORMAL, -1))
        assertEquals(false to 90, uprightTurn(ORIENTATION_UNDEFINED, 1))
        assertEquals(false to 0, uprightTurn(42, 4))
    }
}

class SaveChoiceTest {
    @Test fun askAndCopyDoWhatTheySay() {
        for (inPlace in listOf(true, false)) {
            assertEquals(RotateSave.Ask, saveTarget(RotateSave.Ask, inPlace))
            assertEquals(RotateSave.Copy, saveTarget(RotateSave.Copy, inPlace))
        }
    }

    @Test fun updateOriginalOnlyWhereItLosesNothing() {
        assertEquals(RotateSave.Original, saveTarget(RotateSave.Original, inPlace = true))
        assertEquals(RotateSave.Copy, saveTarget(RotateSave.Original, inPlace = false)) // a PNG, a WhatsApp JPEG: a copy instead
    }
}

class LosslessTest {
    @Test fun onlyJpegAndWebpWithAnOrientationValueTurnInPlace() {
        assertEquals(CopyWay.Patch, copyWay("image/jpeg", patchable = true))
        assertEquals(CopyWay.Patch, copyWay("image/webp", patchable = true))
    }

    @Test fun jpegAndWebpWithoutOneStillKeepEveryPixelInACopy() {
        assertEquals(CopyWay.Retag, copyWay("image/jpeg", patchable = false)) // WhatsApp images
        assertEquals(CopyWay.Retag, copyWay("image/webp", patchable = false))
    }

    @Test fun everythingElseIsResaved() {
        for (mime in listOf("image/png", "image/heic", "image/heif", "image/avif", "image/gif", "image/bmp", null)) {
            assertEquals(mime, CopyWay.Resave, copyWay(mime, patchable = false))
            assertEquals(mime, CopyWay.Resave, copyWay(mime, patchable = true))
        }
    }
}

class RotatedCopyTest {
    @Test fun copiesAreNamedForWhatTheyAre() {
        assertEquals("IMG_20241024_140512_rotated.jpg", rotatedName("IMG_20241024_140512.jpg", "jpg"))
        assertEquals("Screenshot_20241024-140512_rotated.png", rotatedName("Screenshot_20241024-140512.png", "png"))
        assertEquals("IMG_1234_rotated.jpg", rotatedName("IMG_1234.HEIC", "jpg")) // re-saved as JPEG
        assertEquals("IMG-20240315-WA0012_rotated.jpg", rotatedName("IMG-20240315-WA0012", "jpg")) // no extension
        assertEquals("Photo_rotated.jpg", rotatedName(".jpg", "jpg"))
    }

    @Test fun aCopyOfACopyDoesNotPileUpAndClashesGetANumber() {
        assertEquals("IMG_1_rotated.jpg", rotatedName("IMG_1_rotated.jpg", "jpg"))
        assertEquals("IMG_1_rotated.jpg", rotatedName("IMG_1_rotated (2).jpg", "jpg"))
        assertEquals(listOf("IMG_1_rotated (1).jpg"), freeNames(listOf(rotatedName("IMG_1.jpg", "jpg")), listOf("IMG_1.jpg", "IMG_1_rotated.jpg")))
    }

    @Test fun aWhatsAppCopyKeepsTheDateInItsName() = assertTrue(isWhatsAppName(rotatedName("IMG-20240315-WA0012.jpg", "jpg")))

    @Test fun copiesGoNextToTheOriginalWhereAlbumCanAddPhotos() {
        assertEquals("DCIM/Camera/", copyFolder("DCIM/Camera/"))
        assertEquals("Pictures/Screenshots/", copyFolder("Pictures/Screenshots/"))
        assertEquals("Pictures/Rotated/", copyFolder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals("Pictures/Rotated/", copyFolder("Download/"))
        assertEquals("Pictures/Rotated/", copyFolder(""))
    }

    @Test fun hugePhotosAreDecodedSmallerOnlyWhenTheyMustBe() {
        assertEquals(1, sampleSize(8160, 6144, 100_000_000)) // 50 MP fits whole
        assertEquals(2, sampleSize(16320, 12240, 100_000_000)) // 200 MP halves to 50 MP
        assertEquals(8, sampleSize(16320, 12240, 12_000_000)) // a quarter is still 12.5 MP
        assertEquals(1, sampleSize(1, 1, 12_000_000))
    }

    @Test fun memoryBudgetStaysSensible() {
        val gb = 1L shl 30
        assertEquals(100_000_000L, pixelBudget(3 * gb)) // never over 100 MP
        assertEquals(gb / 32, pixelBudget(gb)) // two 4-byte copies in a quarter of it
        assertEquals(12_000_000L, pixelBudget(100L shl 20)) // short of memory: still a 12 MP photo
    }
}

class RotateWordsTest {
    @Test fun talkBackHearsHowThePhotoIsTurned() {
        assertEquals("Turned right", turnWords(1))
        assertEquals("Upside down", turnWords(2))
        assertEquals("Turned left", turnWords(-1))
        assertEquals("Not turned", turnWords(4))
        assertEquals("Upside down", turnWords(-6))
    }

    @Test fun snackbarsSayWhatHappened() {
        assertEquals("Rotated right", rotatedMessage(1))
        assertEquals("Rotated left", rotatedMessage(-1))
        assertEquals("Rotated 180°", rotatedMessage(2))
        assertEquals("Saved a rotated copy to Camera", copyMessage("Camera"))
        assertEquals("Saved a 50 MP rotated copy to Rotated", copyMessage("Rotated", megapixels = 50))
        assertEquals("Saved a rotated copy to Camera. The original can't be updated", copyMessage("Camera", note = "The original can't be updated"))
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
