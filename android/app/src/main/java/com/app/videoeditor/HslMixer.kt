package com.app.videoeditor

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Eight-band HSL saturation mixer. -1 removes saturation, 0 is neutral, +1 doubles it. */
data class HslMixer(val adjustments: List<Float> = List(8) { 0f }) {
    init { require(isValidHslMixer(adjustments)) { "HSL mixer requires eight finite adjustments in -1..1" } }
    private val centers = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 330f)
    private fun hueDistance(a: Float, b: Float): Float {
        val raw = abs(a - b) % 360f
        return min(raw, 360f - raw)
    }
    private fun smoothWeight(distance: Float): Float {
        val t = (distance / 42f).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }
    fun sample(red: Float, green: Float, blue: Float): FloatArray {
        val r = red.coerceIn(0f, 1f); val g = green.coerceIn(0f, 1f); val b = blue.coerceIn(0f, 1f)
        val maxChannel = max(r, max(g, b)); val minChannel = min(r, min(g, b)); val delta = maxChannel - minChannel
        if (delta <= 0.000001f || maxChannel <= 0.000001f) return floatArrayOf(r, g, b)
        var hue = when (maxChannel) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f
        val saturation = delta / maxChannel
        var weighted = 0f; var total = 0f
        for (i in centers.indices) {
            val weight = smoothWeight(hueDistance(hue, centers[i]))
            weighted += adjustments[i] * weight; total += weight
        }
        val adjustment = if (total > 0f) weighted / total else 0f
        val chroma = maxChannel * (saturation * (1f + adjustment)).coerceIn(0f, 1f)
        val h = hue / 60f; val x = chroma * (1f - abs((h % 2f) - 1f)); val m = maxChannel - chroma
        val (rr, gg, bb) = when (floor(h).toInt()) {
            0 -> Triple(chroma, x, 0f); 1 -> Triple(x, chroma, 0f); 2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma); 4 -> Triple(x, 0f, chroma); else -> Triple(chroma, 0f, x)
        }
        return floatArrayOf((rr + m).coerceIn(0f, 1f), (gg + m).coerceIn(0f, 1f), (bb + m).coerceIn(0f, 1f))
    }
    /** Media3 cube ordering: red outer, green middle, blue inner. */
    fun toMedia3ArgbCube(size: Int = 17): Array<Array<IntArray>> {
        require(size in 2..33) { "HSL mixer LUT size must be 2..33" }
        val denominator = (size - 1).toFloat()
        return Array(size) { ri -> Array(size) { gi -> IntArray(size) { bi ->
            val rgb = sample(ri / denominator, gi / denominator, bi / denominator)
            val rr = (rgb[0] * 255f).roundToInt(); val gg = (rgb[1] * 255f).roundToInt(); val bb = (rgb[2] * 255f).roundToInt()
            (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        } } }
    }
}
