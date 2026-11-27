package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayTimelineLayoutTest {
    private fun layer(id: Long, start: Long, duration: Long) = OverlayClip(
        id = id,
        uriString = "content://$id",
        name = "Layer $id",
        durationMs = duration,
        width = 1280,
        height = 720,
        timelineStartMs = start,
        isImage = true
    )

    @Test
    fun overlappingLayersUseSeparateLanes() {
        val placements = assignOverlayLanes(
            listOf(layer(1L, 0L, 5_000L), layer(2L, 1_000L, 5_000L), layer(3L, 2_000L, 5_000L))
        ).associate { it.overlayId to it.lane }

        assertEquals(0, placements[1L])
        assertEquals(1, placements[2L])
        assertEquals(2, placements[3L])
    }

    @Test
    fun adjacentLayersCanReuseLane() {
        val placements = assignOverlayLanes(
            listOf(layer(1L, 0L, 1_000L), layer(2L, 1_000L, 1_000L), layer(3L, 2_000L, 1_000L))
        )

        assertEquals(listOf(0, 0, 0), placements.sortedBy { it.overlayId }.map { it.lane })
    }

    @Test
    fun inputOrderDoesNotChangeLaneAssignment() {
        val layers = listOf(layer(3L, 2_000L, 5_000L), layer(1L, 0L, 5_000L), layer(2L, 1_000L, 5_000L))
        val placements = assignOverlayLanes(layers)

        assertEquals(listOf(1L, 2L, 3L), placements.map { it.overlayId })
        assertEquals(listOf(0, 1, 2), placements.map { it.lane })
    }
}
