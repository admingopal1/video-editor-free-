package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioGainProcessorTest {
    @Test
    fun gainIsFiniteAndBoundedToSupportedRange() {
        assertEquals(1f, AudioGainProcessor.normalizeGain(1f), 0.0001f)
        assertEquals(0f, AudioGainProcessor.normalizeGain(Float.NaN), 0.0001f)
        assertEquals(0f, AudioGainProcessor.normalizeGain(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(0f, AudioGainProcessor.normalizeGain(-1f), 0.0001f)
        assertEquals(4f, AudioGainProcessor.normalizeGain(8f), 0.0001f)
    }

    @Test
    fun floatPcmGainSupportsBoostAndClipsSafely() {
        assertEquals(0.5f, AudioGainProcessor.applyPcmFloatGain(0.5f, 1f), 0.0001f)
        assertEquals(1f, AudioGainProcessor.applyPcmFloatGain(0.75f, 2f), 0.0001f)
        assertEquals(-1f, AudioGainProcessor.applyPcmFloatGain(-0.75f, 2f), 0.0001f)
        assertEquals(0f, AudioGainProcessor.applyPcmFloatGain(Float.NaN, 2f), 0.0001f)
    }

    @Test
    fun pcm16GainPreservesUnityAndSaturatesBoostedSamples() {
        assertEquals(12_345.toShort(), AudioGainProcessor.applyPcm16Gain(12_345, 1f))
        assertEquals(20_000.toShort(), AudioGainProcessor.applyPcm16Gain(10_000, 2f))
        assertEquals(Short.MAX_VALUE, AudioGainProcessor.applyPcm16Gain(20_000, 2f))
        assertEquals(Short.MIN_VALUE, AudioGainProcessor.applyPcm16Gain((-20_000).toShort(), 2f))
    }
}
