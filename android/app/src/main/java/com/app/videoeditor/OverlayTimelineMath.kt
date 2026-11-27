package com.app.videoeditor

import kotlin.math.floor

/**
 * Maps project-timeline time to the source-media timestamp for a video overlay.
 *
 * Project time and source time are deliberately kept separate: the overlay is
 * inactive outside its half-open timeline interval, and playback speed is applied
 * only to elapsed time after the overlay's timeline start.
 *
 * Returns null for still images, invalid layers, or timestamps outside the active
 * interval. The returned source timestamp is always inside [startMs, endMs).
 */
fun overlaySourceTimeMs(layer: OverlayClip, projectTimeMs: Long): Long? {
    if (layer.isImage || !layer.isValidOverlayClip() || !layer.isVisibleAt(projectTimeMs)) {
        return null
    }

    val elapsedProjectMs = projectTimeMs - layer.timelineStartMs
    val sourceOffset = floor(elapsedProjectMs.toDouble() * layer.speed.toDouble())
        .toLong()
        .coerceIn(0L, (layer.trimmedMs - 1L).coerceAtLeast(0L))

    return layer.startMs + sourceOffset
}
