package com.aditya.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanoramaTest {
    @Test fun panoramasByShape() {
        assertEquals(Pano.Wide, panoramaOf(8000, 2000, screenshot = false))
        assertEquals(Pano.Wide, panoramaOf(4096, 2048, screenshot = false)) // a 360° photo is 2:1
        assertEquals(Pano.Tall, panoramaOf(1500, 6000, screenshot = false))
        assertNull(panoramaOf(4000, 3000, screenshot = false))
        assertNull(panoramaOf(1080, 2400, screenshot = false)) // a phone screen's shape, from WhatsApp
        assertNull(panoramaOf(1080, 2640, screenshot = false)) // a folding phone's cover screen
        assertNull(panoramaOf(1080, 6000, screenshot = true)) // a long screenshot
        assertNull(panoramaOf(0, 0, screenshot = false))
    }

    @Test fun photoSpheresComeFromTheirXmp() {
        val whole = """<rdf:Description GPano:ProjectionType="equirectangular" GPano:UsePanoramaViewer="True"/>"""
        assertEquals(Sphere(0f, 0f, 1f, 1f), sphereOf(whole))
        // Taken part of the way round, in the element form some cameras write
        val part = """
            <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
            <GPano:FullPanoWidthPixels>8000</GPano:FullPanoWidthPixels>
            <GPano:FullPanoHeightPixels>4000</GPano:FullPanoHeightPixels>
            <GPano:CroppedAreaLeftPixels>2000</GPano:CroppedAreaLeftPixels>
            <GPano:CroppedAreaTopPixels>1000</GPano:CroppedAreaTopPixels>
            <GPano:CroppedAreaImageWidthPixels>4000</GPano:CroppedAreaImageWidthPixels>
            <GPano:CroppedAreaImageHeightPixels>2000</GPano:CroppedAreaImageHeightPixels>
        """
        assertEquals(Sphere(0.25f, 0.25f, 0.5f, 0.5f), sphereOf(part))
        // Nonsense numbers fall back to the whole sphere rather than drawing garbage
        assertEquals(Sphere(0f, 0f, 1f, 1f), sphereOf(part.replace(">1000<", ">3500<")))
        assertNull(sphereOf("""<rdf:Description GPano:ProjectionType="cylindrical"/>"""))
        assertNull(sphereOf("""<rdf:Description GCamera:MotionPhoto="1"/>"""))
    }
}
