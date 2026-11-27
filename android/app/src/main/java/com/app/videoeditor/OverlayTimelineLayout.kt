package com.app.videoeditor

/** Timeline lane assignment for secondary visual clips. Overlapping clips never share a lane. */
data class OverlayLanePlacement(val overlayId: Long, val lane: Int)

/**
 * Greedy interval partitioning: place clips in the first lane whose previous clip
 * has ended. Adjacent clips may share a lane. Stable ordering makes layout repeatable.
 */
fun assignOverlayLanes(layers: List<OverlayClip>): List<OverlayLanePlacement> {
    val laneEnds = mutableListOf<Long>()
    val placements = mutableListOf<OverlayLanePlacement>()
    layers.sortedWith(compareBy<OverlayClip> { it.timelineStartMs }.thenBy { it.timelineEndMs }.thenBy { it.id })
        .forEach { layer ->
            val lane = laneEnds.indexOfFirst { it <= layer.timelineStartMs }
                .takeIf { it >= 0 } ?: laneEnds.size
            if (lane == laneEnds.size) laneEnds += layer.timelineEndMs
            else laneEnds[lane] = layer.timelineEndMs
            placements += OverlayLanePlacement(layer.id, lane)
        }
    return placements
}
