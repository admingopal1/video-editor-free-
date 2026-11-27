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
            snapOverlayTimelineStart(1_500L, 500L, 10_000L, listOf(1_000L, 2_500L), 500L)
        )
    }

    @Test
    fun negativeThresholdBehavesAsZero() {
        assertEquals(
            OverlaySnapResult(2_000L, 2_000L),
            snapOverlayTimelineStart(2_000L, 1_000L, 10_000L, listOf(2_000L), -1L)
        )
    }

    @Test
    fun dragDeltasAccumulateFromLatestPosition() {
        val first = calculateOverlayDragPosition(1_000L, 250L, 10_000L)
        val second = calculateOverlayDragPosition(first, 250L, 10_000L)
        assertEquals(1_500L, second)
    }

    @Test
    fun dragPositionClampsAtTimelineEdges() {
        assertEquals(0L, calculateOverlayDragPosition(100L, -500L, 10_000L))
        assertEquals(10_000L, calculateOverlayDragPosition(9_900L, 500L, 10_000L))
    }

    @Test
    fun dragPositionHandlesLongOverflow() {
        assertEquals(Long.MAX_VALUE, calculateOverlayDragPosition(Long.MAX_VALUE - 2L, 10L, Long.MAX_VALUE))
        assertEquals(0L, calculateOverlayDragPosition(2L, Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun negativeTimelineDurationProducesZeroPosition() {
        assertEquals(0L, calculateOverlayDragPosition(5_000L, 100L, -1L))
    }
}
