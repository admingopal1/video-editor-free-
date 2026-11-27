package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEqualizerProcessorTest {
    @Test
    fun gainsAreNormalizedToFiveFiniteBandsAndTwelveDbRange() {
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), AudioEqualizerProcessor.normalizeGains(emptyList()))
        assertEquals(listOf(12f, -12f, 0f, 3f, 0f), AudioEqualizerProcessor.normalizeGains(
            listOf(20f, -20f, Float.NaN, 3f, Float.POSITIVE_INFINITY, 9f)
        ))
    }

    @Test
    fun zeroDbPeakingFilterIsUnity() {
        val filter = PeakingBiquad.create(48_000, 1_000f, 1f, 0f)
        val samples = listOf(0.25f, -0.5f, 0.75f, -0.125f)
        samples.forEach { sample ->
            assertTrue(kotlin.math.abs(filter.process(sample) - sample) < 0.00001f)
        }
    }

    @Test
    fun positiveAndNegativeGainChangeSignalAndRemainFinite() {
        val boost = PeakingBiquad.create(48_000, 910f, 1f, 9f)
        val cut = PeakingBiquad.create(48_000, 910f, 1f, -9f)
        var boostEnergy = 0f
        var cutEnergy = 0f
        repeat(4_800) { index ->
            val sample = kotlin.math.sin(2.0 * Math.PI * 910.0 * index / 48_000.0).toFloat() * 0.2f
            val boosted = boost.process(sample)
            val reduced = cut.process(sample)
            assertTrue(boosted.isFinite() && reduced.isFinite())
            boostEnergy += boosted * boosted
            cutEnergy += reduced * reduced
        }
        assertTrue(boostEnergy > cutEnergy * 2f)
    }

    @Test
    fun highFrequencyBandIsClampedBelowNyquistAndStable() {
        val filter = PeakingBiquad.create(8_000, 14_000f, 1f, 12f)
        repeat(20_000) { index ->
            val sample = if (index % 2 == 0) 0.1f else -0.1f
            assertTrue(filter.process(sample).isFinite())
        }
    }

    @Test
    fun presetsHaveFiveBoundedBandsAndFlatResetsAllBands() {
        listOf("Bass Boost", "Vocal", "Podcast", "Treble").forEach { preset ->
            val gains = AudioEqualizerProcessor.presetGains(preset)
            assertEquals(AudioEqualizerProcessor.BAND_COUNT, gains.size)
            assertTrue(gains.all { it in -12f..12f })
        }
        assertEquals(List(5) { 0f }, AudioEqualizerProcessor.presetGains("Flat"))
    }
}
