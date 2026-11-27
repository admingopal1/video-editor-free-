package com.app.videoeditor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGradeTest {
    @Test
    fun neutralChannelGainsAreValidAndBackwardCompatible() {
        assertTrue(isValidColorChannelGain(1f, 1f, 1f))
        assertTrue(isValidProjectClipMetadata(
            id = 1L, uriString = "content://media/1", durationMs = 10_000L,
            width = 1920, height = 1080, startMs = 0L, endMs = 10_000L,
            speed = 1f, volume = 1f, zoom = 1f, adjustments = emptyList()
        ))
    }

    @Test
    fun channelGainsAcceptSupportedRange() {
        assertTrue(isValidColorChannelGain(0.5f, 1f, 1.5f))
    }

    @Test
    fun channelGainsRejectNonFiniteAndOutOfRangeValues() {
        assertFalse(isValidColorChannelGain(Float.NaN, 1f, 1f))
        assertFalse(isValidColorChannelGain(1f, Float.POSITIVE_INFINITY, 1f))
        assertFalse(isValidColorChannelGain(0.49f, 1f, 1f))
        assertFalse(isValidColorChannelGain(1f, 1.51f, 1f))
        assertFalse(isValidColorChannelGain(1f, 1f, -Float.MAX_VALUE))
    }

    @Test
    fun projectClipValidationRejectsInvalidColorBalance() {
        assertFalse(isValidProjectClipMetadata(
            id = 1L, uriString = "content://media/1", durationMs = 10_000L,
            width = 1920, height = 1080, startMs = 0L, endMs = 10_000L,
            speed = 1f, volume = 1f, zoom = 1f, adjustments = emptyList(),
            redGain = 1.6f
        ))
    }
}
