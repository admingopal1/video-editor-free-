package com.app.videoeditor

import kotlin.math.roundToLong

/**
 * Editing operations for a TextLayer's clip-relative output interval.
 * Fractions are stored for backwards compatibility; calculations use milliseconds
 * so drag/trim behavior can be precise and deterministic.
 */
private const val TEXT_LAYER_MIN_DURATION_MS = 100L

data class TextLayerTiming(
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

fun textLayerTiming(layer: TextLayer, clipOutputDurationMs: Long): TextLayerTiming {
    val total = clipOutputDurationMs.coerceAtLeast(0L)
    val start = (layer.startFrac.coerceIn(0f, 1f).toDouble() * total.toDouble()).roundToLong().coerceIn(0L, total)
    val end = (layer.endFrac.coerceIn(0f, 1f).toDouble() * total.toDouble()).roundToLong().coerceIn(start, total)
    return TextLayerTiming(start, end)
}

/** Moves a layer while preserving its duration and keeping it inside the clip. */
fun moveTextLayerTiming(layer: TextLayer, clipOutputDurationMs: Long, deltaMs: Long): TextLayer {
    val total = clipOutputDurationMs.coerceAtLeast(0L)
    if (total < TEXT_LAYER_MIN_DURATION_MS || deltaMs == 0L) return layer
    val current = textLayerTiming(layer, total)
    val duration = current.durationMs.coerceIn(TEXT_LAYER_MIN_DURATION_MS, total)
    val latestStart = total - duration
    val desired = when {
        deltaMs > 0L && current.startMs > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
        deltaMs < 0L && deltaMs == Long.MIN_VALUE -> Long.MIN_VALUE
        else -> current.startMs + deltaMs
    }
    val start = desired.coerceIn(0L, latestStart)
    return layer.copy(startFrac = start.toFloat() / total, endFrac = (start + duration).toFloat() / total)
}

/** Trims the leading edge; the trailing edge remains fixed. */
fun trimTextLayerStart(layer: TextLayer, clipOutputDurationMs: Long, deltaMs: Long): TextLayer {
    val total = clipOutputDurationMs.coerceAtLeast(0L)
    if (total < TEXT_LAYER_MIN_DURATION_MS || deltaMs == 0L) return layer
    val current = textLayerTiming(layer, total)
    val latestStart = (current.endMs - TEXT_LAYER_MIN_DURATION_MS).coerceAtLeast(0L)
    val desired = when {
        deltaMs > 0L && current.startMs > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
        deltaMs < 0L && deltaMs == Long.MIN_VALUE -> Long.MIN_VALUE
        else -> current.startMs + deltaMs
    }
    val start = desired.coerceIn(0L, latestStart)
    return layer.copy(startFrac = start.toFloat() / total)
}

/** Trims the trailing edge; the leading edge remains fixed. */
fun trimTextLayerEnd(layer: TextLayer, clipOutputDurationMs: Long, deltaMs: Long): TextLayer {
    val total = clipOutputDurationMs.coerceAtLeast(0L)
    if (total < TEXT_LAYER_MIN_DURATION_MS || deltaMs == 0L) return layer
    val current = textLayerTiming(layer, total)
    val earliestEnd = (current.startMs + TEXT_LAYER_MIN_DURATION_MS).coerceAtMost(total)
    val desired = when {
        deltaMs > 0L && current.endMs > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
        deltaMs < 0L && deltaMs == Long.MIN_VALUE -> Long.MIN_VALUE
        else -> current.endMs + deltaMs
    }
    val end = desired.coerceIn(earliestEnd, total)
    return layer.copy(endFrac = end.toFloat() / total)
}
