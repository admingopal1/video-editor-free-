package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HslMixerTest {
    @Test fun neutralMixerPreservesRgb() {
        val result = HslMixer().sample(0.8f, 0.2f, 0.1f)
        assertEquals(0.8f, result[0], 0.0001f); assertEquals(0.2f, result[1], 0.0001f); assertEquals(0.1f, result[2], 0.0001f)
    }
    @Test fun redBandRaisesSaturationWithoutChangingValue() {
        val output = HslMixer(listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)).sample(0.8f, 0.4f, 0.4f)
        assertTrue(output[1] < 0.4f); assertTrue(output[2] < 0.4f); assertEquals(0.8f, output.maxOrNull()!!, 0.0001f)
    }
    @Test fun redHueWrapsAcrossZeroDegrees() {
        val output = HslMixer(listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)).sample(0.9f, 0.1f, 0.11f)
        assertTrue(output[0] > output[1]); assertTrue(output[0] > output[2])
    }
    @Test fun grayscaleRemainsUnchanged() {
        val output = HslMixer(List(8) { 1f }).sample(0.4f, 0.4f, 0.4f)
        assertEquals(0.4f, output[0], 0.0001f); assertEquals(0.4f, output[1], 0.0001f); assertEquals(0.4f, output[2], 0.0001f)
    }
    @Test fun cubeHasExpectedDimensionsAndOpaqueAlpha() {
        val cube = HslMixer().toMedia3ArgbCube(5)
        assertEquals(5, cube.size); assertEquals(5, cube[0].size); assertEquals(5, cube[0][0].size)
        assertEquals(0xFF000000.toInt(), cube[0][0][0]); assertEquals(0xFFFFFFFF.toInt(), cube[4][4][4])
    }
    @Test fun rejectsMalformedAdjustmentVectors() {
        assertTrue(!isValidHslMixer(List(7) { 0f })); assertTrue(!isValidHslMixer(List(8) { Float.NaN }))
        assertTrue(!isValidHslMixer(List(8) { 1.1f }))
    }
}
