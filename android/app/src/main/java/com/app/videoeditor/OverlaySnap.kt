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
    val maxStart = (totalDurationMs - durationMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    val requested = requestedStartMs.coerceIn(0L, maxStart)
    val threshold = thresholdMs.coerceAtLeast(0L)
    val candidates = boundariesMs.asSequence()
        .filter { it >= 0L }
        .flatMap { boundary ->
            sequenceOf(
                boundary.coerceIn(0L, maxStart),
                (boundary - durationMs.coerceAtLeast(0L)).coerceIn(0L, maxStart)
            )
        }
        .distinct()
        .map { candidate -> candidate to kotlin.math.abs(candidate - requested) }
        .filter { (_, distance) -> distance <= threshold }
        .sortedWith(compareBy<Pair<Long, Long>> { it.second }.thenBy { it.first })
        .firstOrNull()

    return if (candidates == null) {
        OverlaySnapResult(requested, null)
    } else {
        OverlaySnapResult(candidates.first, candidates.first)
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
