package com.app.videoeditor

/**
 * Adjust a PIP video's source trim by a delta measured in project-timeline milliseconds.
 * Moving the left edge also moves the clip's timeline start, keeping the visible
 * right edge fixed. A 100 ms minimum source duration avoids zero-length clips.
 */
private fun saturatingAdd(a: Long, b: Long): Long =
    if (b > 0L && a > Long.MAX_VALUE - b) Long.MAX_VALUE
    else if (b < 0L && a < Long.MIN_VALUE - b) Long.MIN_VALUE
    else a + b

fun trimOverlayStartByOutputDelta(layer: OverlayClip, deltaOutputMs: Long): OverlayClip {
    if (layer.isImage || deltaOutputMs == 0L || layer.speed <= 0f || !layer.speed.isFinite()) return layer
    val minSourceMs = 100L
    val maxStart = (layer.endMs - minSourceMs).coerceAtLeast(0L)
    val requested = (deltaOutputMs.toDouble() * layer.speed).toLong()
    val newStart = saturatingAdd(layer.startMs, requested).coerceIn(0L, maxStart)
    val appliedSource = newStart - layer.startMs
    val appliedOutput = (appliedSource.toDouble() / layer.speed).toLong()
    val newTimelineStart = saturatingAdd(layer.timelineStartMs, appliedOutput).coerceAtLeast(0L)
    return layer.copy(startMs = newStart, timelineStartMs = newTimelineStart)
}

/** Adjusts the right edge without moving the clip's timeline start. */
fun trimOverlayEndByOutputDelta(layer: OverlayClip, deltaOutputMs: Long): OverlayClip {
    if (layer.isImage || deltaOutputMs == 0L || layer.speed <= 0f || !layer.speed.isFinite()) return layer
    val minSourceMs = 100L
    val minEnd = saturatingAdd(layer.startMs, minSourceMs).coerceAtMost(layer.durationMs)
    val requested = (deltaOutputMs.toDouble() * layer.speed).toLong()
    val newEnd = saturatingAdd(layer.endMs, requested).coerceIn(minEnd, layer.durationMs)
    return layer.copy(endMs = newEnd)
}
