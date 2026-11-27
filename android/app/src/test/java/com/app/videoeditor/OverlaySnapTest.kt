package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlaySnapTest {
    @Test
    fun snapsOverlayStartToNearbyBoundary() {
        assertEquals(
            OverlaySnapResult(2_000L, 2_000L),
            snapOverlayTimelineStart(2_080L, 1_000L, 10_000L, listOf(2_000L))
        )
    }

    @Test
    fun snapsOverlayEndToNearbyBoundary() {
        assertEquals(
            OverlaySnapResult(1_000L, 1_000L),
            snapOverlayTimelineStart(1_080L, 1_000L, 10_000L, listOf(2_000L))
        )
    }

    @Test
    fun doesNotSnapWhenOutsideThreshold() {
        assertEquals(
            OverlaySnapResult(2_200L, null),
            snapOverlayTimelineStart(2_200L, 1_000L, 10_000L, listOf(2_000L))
        )
    }

    @Test
    fun clampsOverlayInsideProjectDuration() {
        assertEquals(
            OverlaySnapResult(8_000L, null),
            snapOverlayTimelineStart(9_500L, 2_000L, 10_000L, emptyList())
        )
    }

    @Test
    fun choosesNearestCandidateAndBreaksTiesTowardEarlierPosition() {
        assertEquals(
            OverlaySnapResult(1_000L, 1_000L),
            snapOverlayTimelineStart(1_500L, 500L, 10_000L, listOf(1_000L, 2_000L), 500L)
        )
    }

    @Test
    fun negativeThresholdBehavesAsZero() {
        assertEquals(
            OverlaySnapResult(2_000L, 2_000L),
            snapOverlayTimelineStart(2_000L, 1_000L, 10_000L, listOf(2_000L), -1L)
        )
    }
}
