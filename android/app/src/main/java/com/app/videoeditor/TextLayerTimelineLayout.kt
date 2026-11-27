package com.app.videoeditor

/**
 * Timeline interval for a text/sticker layer, expressed in project output milliseconds.
 * Clip and layer IDs together identify a layer because legacy project IDs may overlap.
 */
data class TextLayerTimelineEntry(
    val clipId: Long,
    val layerId: Long,
    val startMs: Long,
    val endMs: Long
)

data class TextLayerLanePlacement(
    val clipId: Long,
    val layerId: Long,
    val lane: Int
)

/**
 * Greedy interval coloring. Layers that overlap in project time never share a lane;
 * adjacent intervals may reuse a lane. Invalid/empty intervals are ignored.
 */
fun assignTextLayerLanes(entries: List<TextLayerTimelineEntry>): List<TextLayerLanePlacement> {
    val laneEnds = mutableListOf<Long>()
    val result = mutableListOf<TextLayerLanePlacement>()
    val ordered = entries
        .filter { it.startMs >= 0L && it.endMs > it.startMs }
        .sortedWith(compareBy<TextLayerTimelineEntry> { it.startMs }.thenBy { it.endMs }
            .thenBy { it.clipId }.thenBy { it.layerId })

    ordered.forEach { entry ->
        val lane = laneEnds.indexOfFirst { it <= entry.startMs }.let { if (it >= 0) it else laneEnds.size }
        if (lane == laneEnds.size) laneEnds.add(entry.endMs) else laneEnds[lane] = entry.endMs
        result += TextLayerLanePlacement(entry.clipId, entry.layerId, lane)
    }
    return result
}
