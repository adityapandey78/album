package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionPhotoTest {
    private fun xmp(description: String, body: String = "") = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
              $description>
              $body
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    private fun directory(vararg items: String) = items.joinToString("", "<Container:Directory><rdf:Seq>", "</rdf:Seq></Container:Directory>") {
        "<rdf:li rdf:parseType=\"Resource\"><Container:Item $it/></rdf:li>"
    }

    @Test fun motionPhotoVideoIsTheLastItem() {
        val pixel = xmp(
            """GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="968000"""",
            directory(
                """Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"""",
                """Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="2851698" Item:Padding="0"""",
            ),
        )
        assertEquals(3_000_000L - 2_851_698 until 3_000_000L, motionVideoRange(pixel, 3_000_000))
    }

    @Test fun itemsAndPaddingBeforeTheVideoDontMoveIt() {
        // Ultra HDR keeps a gain map between the still and the video; HEIC pads the primary item by the mpvd box header
        val ultraHdr = xmp(
            """Camera:MotionPhoto="1"""",
            directory(
                """Item:Semantic="Primary" Item:Mime="image/heic" Item:Padding="8"""",
                """Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="31270"""",
                """Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="500"""",
            ),
        )
        assertEquals(500L until 1000L, motionVideoRange(ultraHdr, 1000))
    }

    @Test fun legacyMicroVideoRunsToTheEnd() {
        val microVideo = xmp("""GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="1500" GCamera:MicroVideoPresentationTimestampUs="1966051"""")
        assertEquals(8500L until 10_000L, motionVideoRange(microVideo, 10_000))
    }

    @Test fun propertiesWrittenAsElementsCountToo() {
        val edited = xmp("", "<GCamera:MicroVideo>1</GCamera:MicroVideo><GCamera:MicroVideoOffset>1500</GCamera:MicroVideoOffset>")
        assertEquals(8500L until 10_000L, motionVideoRange(edited, 10_000))
    }

    @Test fun plainPhotosAndSwitchedOffMotionAreNotMotionPhotos() {
        assertNull(motionVideoRange(xmp("""xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="Snapseed""""), 10_000))
        val off = xmp("""GCamera:MotionPhoto="0"""", directory("""Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="500""""))
        assertNull(motionVideoRange(off, 10_000))
        assertNull(motionVideoRange("", 10_000))
    }

    @Test fun brokenOffsetsAreIgnored() {
        assertNull(motionVideoRange(xmp("""GCamera:MicroVideo="1" GCamera:MicroVideoOffset="20000""""), 10_000)) // past the start
        assertNull(motionVideoRange(xmp("""GCamera:MicroVideo="1" GCamera:MicroVideoOffset="0""""), 10_000))
        val noLength = xmp("""GCamera:MotionPhoto="1"""", directory("""Item:Semantic="MotionPhoto" Item:Mime="video/mp4""""))
        assertNull(motionVideoRange(noLength, 10_000))
    }
}
