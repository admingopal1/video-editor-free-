package com.app.videoeditor

import kotlin.math.abs
import kotlin.math.sqrt

/** CPU reference for the GPU mask. Coordinates are normalized to 0..1. */
fun clipMaskCoverage(shape: ClipMaskShape, x: Float, y: Float, centerX: Float, centerY: Float, width: Float, height: Float, feather: Float, invert: Boolean = false): Float {
    if (shape == ClipMaskShape.None) return 1f
    if (!listOf(x, y, centerX, centerY, width, height, feather).all { it.isFinite() } || width <= 0f || height <= 0f || feather < 0f) return 0f
    val halfW = width * 0.5f
    val halfH = height * 0.5f
    val dx = abs(x - centerX)
    val dy = abs(y - centerY)
    val distance = when (shape) {
        ClipMaskShape.None -> 1f
        ClipMaskShape.Rectangle -> minOf(halfW - dx, halfH - dy)
        ClipMaskShape.Ellipse -> (1f - sqrt((dx / halfW) * (dx / halfW) + (dy / halfH) * (dy / halfH))) * minOf(halfW, halfH)
    }
    val alpha = if (feather <= 0.0001f) if (distance >= 0f) 1f else 0f else {
        val t = ((distance + feather) / (2f * feather)).coerceIn(0f, 1f)
        t * t * (3f - 2f * t)
    }
    return if (invert) 1f - alpha else alpha
}
