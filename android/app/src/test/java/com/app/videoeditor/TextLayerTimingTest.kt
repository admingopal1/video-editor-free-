package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TextLayerTimingTest {
    private fun layer(start: Float = 0.2f, end: Float = 0.6f) =
        TextLayer(id = 1L, startFrac = start, endFrac = end)

    @Test fun movePreservesDurationAndClampsToClip() {
        val moved = moveTextLayerTiming(layer(), 10_000L, 1_000L)
        val timing = textLayerTiming(moved, 10_000L)
        assertEquals(3_000L, timing.startMs)
        assertEquals(7_000L, timing.endMs)
        val clamped = textLayerTiming(moveTextLayerTiming(layer(), 10_000L, Long.MAX_VALUE), 10_000L)
        assertEquals(6_000L, clamped.startMs)
        assertEquals(10_000L, clamped.endMs)
    }

    @Test fun trimStartKeepsEndFixedAndEnforcesMinimumDuration() {
        val trimmed = textLayerTiming(trimTextLayerStart(layer(), 10_000L, 1_500L), 10_000L)
        assertEquals(3_500L, trimmed.startMs)
        assertEquals(6_000L, trimmed.endMs)
        val minimum = textLayerTiming(trimTextLayerStart(layer(), 10_000L, Long.MAX_VALUE), 10_000L)
        assertEquals(5_900L, minimum.startMs)
        assertEquals(6_000L, minimum.endMs)
    }

    @Test fun trimEndKeepsStartFixedAndEnforcesMinimumDuration() {
        val trimmed = textLayerTiming(trimTextLayerEnd(layer(), 10_000L, -1_500L), 10_000L)
        assertEquals(2_000L, trimmed.startMs)
        assertEquals(4_500L, trimmed.endMs)
        val minimum = textLayerTiming(trimTextLayerEnd(layer(), 10_000L, Long.MIN_VALUE), 10_000L)
        assertEquals(2_000L, minimum.startMs)
        assertEquals(2_100L, minimum.endMs)
    }

    @Test fun invalidOrTinyClipDoesNotChangeLayer() {
        val original = layer()
        assertSame(original, moveTextLayerTiming(original, 50L, 100L))
        assertSame(original, trimTextLayerStart(original, 50L, 100L))
        assertSame(original, trimTextLayerEnd(original, 50L, 100L))
    }

    @Test fun malformedFractionsNormalizeWithoutCrashing() {
        val malformedStart = layer(start = Float.NaN, end = 0.6f)
        val startTiming = textLayerTiming(malformedStart, 10_000L)
        assertEquals(0L, startTiming.startMs)
        assertEquals(6_000L, startTiming.endMs)

        val malformedEnd = layer(start = 0.2f, end = Float.POSITIVE_INFINITY)
        val endTiming = textLayerTiming(malformedEnd, 10_000L)
        assertEquals(2_000L, endTiming.startMs)
        assertEquals(10_000L, endTiming.endMs)

        val bothMalformed = layer(start = Float.NEGATIVE_INFINITY, end = Float.NaN)
        val safeTiming = textLayerTiming(bothMalformed, 10_000L)
        assertEquals(0L, safeTiming.startMs)
        assertEquals(10_000L, safeTiming.endMs)
    }

    @Test fun malformedFullClipLayerRemainsBoundedWhenEdited() {
        val malformed = layer(start = Float.NaN, end = Float.NaN)
        val moved = textLayerTiming(moveTextLayerTiming(malformed, 10_000L, 500L), 10_000L)
        // The safe fallback spans the full clip, so it cannot move without shortening it.
        assertEquals(0L, moved.startMs)
        assertEquals(10_000L, moved.endMs)
    }

    @Test fun zeroDeltaIsNoOp() {
        val original = layer()
        assertSame(original, moveTextLayerTiming(original, 10_000L, 0L))
        assertSame(original, trimTextLayerStart(original, 10_000L, 0L))
        assertSame(original, trimTextLayerEnd(original, 10_000L, 0L))
    }
}
