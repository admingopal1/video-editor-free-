package com.app.videoeditor

import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Pure timeline / animation maths shared by the live preview and the exporter, so both use the
 * same formulas for clip order, fades, motion, keyframes and text timing.
 */

data class TimelinePos(val index: Int, val localMs: Long)

object TimelineMath {
    /** Start time of every clip on the timeline (output time, cumulative). */
    fun starts(durations: List<Long>): List<Long> {
        var acc = 0L
        return durations.map { val s = acc; acc += it.coerceAtLeast(0L); s }
    }

    fun total(durations: List<Long>): Long = durations.sumOf { it.coerceAtLeast(0L) }

    /**
     * Clip index and local time for a project time. The time is clamped to the timeline. On an exact
     * clip boundary the later clip wins; the very end maps to the end of the last clip.
     */
    fun locate(durations: List<Long>, timeMs: Long): TimelinePos? {
        if (durations.isEmpty()) return null
        val t = timeMs.coerceIn(0L, total(durations))
        var acc = 0L
        for ((i, raw) in durations.withIndex()) {
            val d = raw.coerceAtLeast(0L)
            if (t < acc + d) return TimelinePos(i, t - acc)
            acc += d
        }
        val last = durations.lastIndex
        return TimelinePos(last, durations[last].coerceAtLeast(0L))
    }
}

object Motion {
    /** 0..1 brightness factor of a clip's fade-in / fade-out at time tUs. */
    fun fade(fadeInUs: Long, fadeOutUs: Long, totalUs: Long, tUs: Long): Float {
        var f = 1f
        if (fadeInUs > 0 && tUs < fadeInUs) f = tUs.toFloat() / fadeInUs
        if (fadeOutUs > 0 && tUs > totalUs - fadeOutUs) f = minOf(f, (totalUs - tUs).toFloat() / fadeOutUs)
        return f.coerceIn(0f, 1f)
    }

    data class AnimXf(val scale: Float, val dx: Float)

    /** Scale and horizontal shift (frame units, -1..1) of the clip motion presets. */
    fun anim(a: Anim, tUs: Long, totalUs: Long): AnimXf {
        val f = (tUs.toFloat() / totalUs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        return when (a) {
            Anim.None -> AnimXf(1f, 0f)
            Anim.ZoomIn -> AnimXf(1f + 0.25f * f, 0f)
            Anim.ZoomOut -> AnimXf(1.25f - 0.25f * f, 0f)
            Anim.PanLeft -> AnimXf(1.2f, 0.18f * (1f - 2f * f))
            Anim.PanRight -> AnimXf(1.2f, -0.18f * (1f - 2f * f))
            Anim.Pulse -> AnimXf(1f + 0.05f * sin(2.0 * PI * (tUs / 1_000_000f)).toFloat(), 0f)
        }
    }
}

const val TEXT_ANIM_US = 400_000L

data class TextRender(val visible: Boolean, val alpha: Float, val scale: Float, val y: Float)

/** Whether a text/sticker layer is visible at tUs (clip output time) and how it is animated. */
fun textRender(layer: TextLayer, totalUs: Long, tUs: Long): TextRender {
    val total = totalUs.coerceAtLeast(1L)
    // Use the same millisecond timing normalization as timeline editing and export overlay selection.
    // This also makes NaN/Infinity values from older or damaged projects deterministic.
    val timing = textLayerTiming(layer, (total / 1_000L).coerceAtLeast(0L))
    val startUs = (timing.startMs.toDouble() * 1_000.0).roundToLong().coerceIn(0L, total)
    val endUs = (timing.endMs.toDouble() * 1_000.0).roundToLong().coerceIn(startUs, total)
    if (tUs < startUs || tUs > endUs) return TextRender(false, 0f, layer.scale, layer.y)
    val elapsedUs = (tUs - startUs).coerceAtLeast(0L)
    val a = (elapsedUs.toFloat() / TEXT_ANIM_US).coerceIn(0f, 1f)
    return when (layer.anim) {
        TextAnim.None -> TextRender(true, 1f, layer.scale, layer.y)
        TextAnim.Fade -> TextRender(true, a, layer.scale, layer.y)
        TextAnim.Pop -> TextRender(true, 1f, layer.scale * (0.5f + 0.5f * a), layer.y)
        TextAnim.SlideUp -> TextRender(true, 1f, layer.scale, layer.y - 0.25f * (1f - a))
    }
}

/** True when export must evaluate this layer against the frame clock instead of using a static overlay. */
fun requiresTimedTextOverlay(layer: TextLayer, clipOutputDurationMs: Long): Boolean {
    val total = clipOutputDurationMs.coerceAtLeast(0L)
    val timing = textLayerTiming(layer, total)
    return layer.anim != TextAnim.None || timing.startMs > 0L || timing.endMs < total
}

/** Aspect ratio of a clip's frame after crop and rotation (what the exporter sees before fitting). */
fun frameAspectOf(width: Int, height: Int, cropL: Float, cropR: Float, cropT: Float, cropB: Float, rotation: Int): Float {
    var w = width * (1f - cropL - cropR)
    var h = height * (1f - cropT - cropB)
    if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
    return (w / h.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
}

fun Clip.frameAspect(): Float = frameAspectOf(width, height, cropL, cropR, cropT, cropB, rotation)

/** Size of a frame with [aspect] placed in the canvas: contained (black bars) or covering (cropped). */
fun fitFrame(aspect: Float, canvasW: Float, canvasH: Float, fill: Boolean): Pair<Float, Float> {
    val canvasAspect = canvasW / canvasH.coerceAtLeast(0.0001f)
    val widthLimited = if (fill) aspect < canvasAspect else aspect >= canvasAspect
    return if (widthLimited) canvasW to canvasW / aspect else canvasH * aspect to canvasH
}

/** The clip properties that drive motion; kept free of Android types so they can be unit tested. */
data class ClipLook(
    val rotation: Int,
    val flipH: Boolean,
    val flipV: Boolean,
    val zoom: Float,
    val anim: Anim,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val outputMs: Long,
    val keyframes: List<Keyframe>
)

fun Clip.look() = ClipLook(rotation, flipH, flipV, zoom, anim, fadeInMs, fadeOutMs, outputMs, keyframes)

/** Everything the preview needs to draw a clip at one moment. Angles are counter-clockwise degrees. */
data class ClipTransform(
    val srScaleX: Float,
    val srScaleY: Float,
    val srRotation: Float,
    val animScale: Float,
    val animDx: Float,
    val kfX: Float,
    val kfY: Float,
    val kfScale: Float,
    val kfRotation: Float,
    val alpha: Float
)

fun clipTransform(l: ClipLook, tUs: Long): ClipTransform {
    val total = (l.outputMs * 1000L).coerceAtLeast(1L)
    val t = tUs.coerceIn(0L, total)
    val sx = if (l.flipH) -l.zoom else l.zoom
    val sy = if (l.flipV) -l.zoom else l.zoom
    val an = Motion.anim(l.anim, t, total)
    val k = if (l.keyframes.isEmpty()) Keyframe(0f) else interpKeyframes(l.keyframes.sortedBy { it.t }, t.toFloat() / total)
    val fade = Motion.fade(l.fadeInMs * 1000L, l.fadeOutMs * 1000L, total, t)
    return ClipTransform(sx, sy, l.rotation.toFloat(), an.scale, an.dx, k.x, k.y, k.scale, k.rotation, fade * k.opacity.coerceIn(0f, 1f))
}
