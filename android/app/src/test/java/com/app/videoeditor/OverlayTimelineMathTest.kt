package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayTimelineMathTest {
    private fun layer(
        speed: Float = 1f,
        isImage: Boolean = false
    ) = OverlayClip(
        id = 7L,
        uriString = "content://media/video/7",
        name = "PIP",
        durationMs = 12_000L,
        width = 1920,
        height = 1080,
        startMs = 1_000L,
        endMs = 9_000L,
        timelineStartMs = 2_500L,
        isImage = isImage,
        speed = speed
    )

    @Test
    fun sourceClockStartsAtTrimInWhenOverlayBecomesActive() {
        assertEquals(1_000L, overlaySourceTimeMs(layer(), 2_500L))
    }

    @Test
    fun sourceClockAppliesFastPlaybackOnlyAfterTimelineOffset() {
        val fast = layer(speed = 2f)

        assertNull(overlaySourceTimeMs(fast, 2_499L))
        assertEquals(2_000L, overlaySourceTimeMs(fast, 3_000L))
        assertEquals(8_998L, overlaySourceTimeMs(fast, 6_499L))
        assertNull(overlaySourceTimeMs(fast, 6_500L))
    }

    @Test
    fun sourceClockAppliesSlowPlaybackAndUsesFractionalMillisecondFlooring() {
        val slow = layer(speed = 0.5f)

        assertEquals(1_750L, overlaySourceTimeMs(slow, 4_000L))
        assertEquals(8_999L, overlaySourceTimeMs(slow, 18_499L))
        assertNull(overlaySourceTimeMs(slow, 18_500L))
    }

    @Test
    fun sourceClockNeverEscapesTrimmedSourceInterval() {
        val fast = layer(speed = 3f)

        assertEquals(8_998L, overlaySourceTimeMs(fast, 5_166L))
        assertNull(overlaySourceTimeMs(fast, fast.timelineEndMs))
    }

    @Test
    fun stillImagesDoNotExposeVideoSourceClock() {
        assertNull(overlaySourceTimeMs(layer(isImage = true), 3_000L))
    }

    @Test
    fun invalidLayerDoesNotProduceSourceTimestamp() {
        assertNull(overlaySourceTimeMs(layer().copy(speed = Float.NaN), 3_000L))
        assertNull(overlaySourceTimeMs(layer().copy(startMs = 9_000L), 3_000L))
    }
}
