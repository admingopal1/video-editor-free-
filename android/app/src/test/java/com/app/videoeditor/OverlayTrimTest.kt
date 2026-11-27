package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OverlayTrimTest {
    private fun video() = OverlayClip(
        id = 7L,
        uriString = "content://video",
        name = "PIP",
        durationMs = 10_000L,
        width = 1920,
        height = 1080,
        startMs = 1_000L,
        endMs = 9_000L,
        timelineStartMs = 2_000L,
        speed = 2f,
        isImage = false
    )

    @Test
    fun trimmingStartMovesTimelineStartAndKeepsRightEdge() {
        val before = video()
        val after = trimOverlayStartByOutputDelta(before, 1_000L)

        assertEquals(3_000L, after.startMs)
        assertEquals(3_000L, after.timelineStartMs)
        assertEquals(before.timelineEndMs, after.timelineEndMs)
    }

    @Test
    fun trimmingEndDoesNotMoveTimelineStart() {
        val before = video()
        val after = trimOverlayEndByOutputDelta(before, 500L)

        assertEquals(10_000L, after.endMs)
        assertEquals(before.timelineStartMs, after.timelineStartMs)
        assertEquals(4_500L, after.outputMs)
    }

    @Test
    fun trimRespectsSourceBoundariesAndMinimumDuration() {
        val before = video()
        val startAtEnd = trimOverlayStartByOutputDelta(before, Long.MAX_VALUE)
        val endAtStart = trimOverlayEndByOutputDelta(before, Long.MIN_VALUE)

        assertEquals(8_900L, startAtEnd.startMs)
        assertEquals(100L, endAtStart.trimmedMs)
    }

    @Test
    fun photoLayersAreNotChangedByVideoTrimGestures() {
        val photo = video().copy(isImage = true)
        assertSame(photo, trimOverlayStartByOutputDelta(photo, 500L))
        assertSame(photo, trimOverlayEndByOutputDelta(photo, -500L))
    }
}
