package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayerTimelineLayoutTest {
    @Test fun overlappingTextAndStickerLayersUseSeparateLanes() {
        val placements = assignTextLayerLanes(listOf(
            TextLayerTimelineEntry(1, 11, 0, 2_000),
            TextLayerTimelineEntry(1, 12, 500, 1_500),
            TextLayerTimelineEntry(2, 21, 1_000, 3_000)
        ))
        assertEquals(3, placements.map { it.lane }.distinct().size)
        assertEquals(listOf(0, 1, 2), placements.map { it.lane })
    }

    @Test fun adjacentLayersReuseLane() {
        val placements = assignTextLayerLanes(listOf(
            TextLayerTimelineEntry(1, 11, 0, 1_000),
            TextLayerTimelineEntry(2, 21, 1_000, 2_000)
        ))
        assertEquals(listOf(0, 0), placements.map { it.lane })
    }

    @Test fun laneAssignmentIsDeterministic() {
        val entries = listOf(
            TextLayerTimelineEntry(2, 22, 500, 1_500),
            TextLayerTimelineEntry(1, 11, 0, 1_000),
            TextLayerTimelineEntry(3, 33, 0, 500)
        )
        assertEquals(assignTextLayerLanes(entries), assignTextLayerLanes(entries.reversed()))
    }

    @Test fun invalidIntervalsAreIgnored() {
        val placements = assignTextLayerLanes(listOf(
            TextLayerTimelineEntry(1, 11, 10, 10),
            TextLayerTimelineEntry(1, 12, -1, 100),
            TextLayerTimelineEntry(1, 13, 0, 100)
        ))
        assertEquals(1, placements.size)
        assertTrue(placements.all { it.layerId == 13L })
    }
}
