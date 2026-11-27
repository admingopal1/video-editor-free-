package com.app.videoeditor

/** Result of aligning a moved overlay to a nearby timeline boundary. */
data class OverlaySnapResult(val positionMs: Long, val snappedToMs: Long?)

/**
 * Snaps an overlay's left or right edge to timeline boundaries while preserving
 * its duration. The returned position is always inside the project timeline.
 */
fun snapOverlayTimelineStart(
    requestedStartMs: Long,
    durationMs: Long,
    totalDurationMs: Long,
    boundariesMs: List<Long>,
    thresholdMs: Long = 120L
): OverlaySnapResult {
    val total = totalDurationMs.coerceAtLeast(0L)
    val duration = durationMs.coerceAtLeast(0L)
    val maxStart = if (duration >= total) 0L else total - duration
    val requested = requestedStartMs.coerceIn(0L, maxStart)
    val threshold = thresholdMs.coerceAtLeast(0L)
    val candidates = boundariesMs.asSequence()
        .filter { it >= 0L && it <= total }
        .flatMap { boundary ->
            sequenceOf(
                boundary.coerceIn(0L, maxStart) to boundary,
                (boundary - duration).coerceIn(0L, maxStart) to boundary
            )
        }
        .distinct()
        .map { (candidateStart, boundary) ->
            Triple(candidateStart, boundary, kotlin.math.abs(candidateStart - requested))
        }
        .filter { (_, _, distance) -> distance <= threshold }
        .sortedWith(compareBy<Triple<Long, Long, Long>> { it.third }.thenBy { it.first }.thenBy { it.second })
        .firstOrNull()

    return if (candidates == null) {
        OverlaySnapResult(requested, null)
    } else {
        // The guide marks the matched boundary, which can be the overlay's right edge.
        OverlaySnapResult(candidates.first, candidates.second)
    }
}

/** Applies one drag delta to the latest position without overflowing Long. */
fun calculateOverlayDragPosition(
    currentStartMs: Long,
    deltaMs: Long,
    totalDurationMs: Long
): Long {
    val maxStart = totalDurationMs.coerceAtLeast(0L)
    val current = currentStartMs.coerceIn(0L, maxStart)
    val requested = when {
        deltaMs > 0L && current > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
        deltaMs < 0L && current < Long.MIN_VALUE - deltaMs -> Long.MIN_VALUE
        else -> current + deltaMs
    }
    return requested.coerceIn(0L, maxStart)
}
