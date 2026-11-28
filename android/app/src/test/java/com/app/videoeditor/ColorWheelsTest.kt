package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorWheelsTest {
    @Test
    fun neutralWheelsPreserveInput() {
        val rgb = ColorWheels().sample(0.2f, 0.5f, 0.8f)
        assertEquals(0.2f, rgb[0], 0.00001f)
        assertEquals(0.5f, rgb[1], 0.00001f)
        assertEquals(0.8f, rgb[2], 0.00001f)
    }

    @Test
    fun temperatureWarmsRedAndCoolsBlue() {
        val warm = ColorWheels(midtonesTemperature = 1f).sample(0.5f, 0.5f, 0.5f)
        assertTrue(warm[0] > 0.5f)
        assertTrue(warm[2] < 0.5f)
    }

    @Test
    fun magentaTintRaisesRedBlueAndReducesGreen() {
        val magenta = ColorWheels(midtonesTint = 1f).sample(0.5f, 0.5f, 0.5f)
        assertTrue(magenta[0] > 0.5f)
        assertTrue(magenta[1] < 0.5f)
        assertTrue(magenta[2] > 0.5f)
    }

    @Test
    fun outputCubeHasOpaqueAlphaAndExpectedDimensions() {
        val cube = ColorWheels().toMedia3ArgbCube(5)
        assertEquals(5, cube.size)
        assertEquals(5, cube[0].size)
        assertEquals(5, cube[0][0].size)
        assertEquals(0xFF000000.toInt(), cube[0][0][0])
        assertEquals(0xFFFFFFFF.toInt(), cube[4][4][4])
    }
}
