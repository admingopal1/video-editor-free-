package com.app.videoeditor

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Three-way tonal color grading. Each wheel has a temperature (blue/amber) and tint
 * (green/magenta) axis. Gaussian luminance weights keep the controls focused on their
 * named tonal ranges while smoothly blending the transitions between ranges.
 */
data class ColorWheels(
    val shadowsTemperature: Float = 0f,
    val shadowsTint: Float = 0f,
    val midtonesTemperature: Float = 0f,
    val midtonesTint: Float = 0f,
    val highlightsTemperature: Float = 0f,
    val highlightsTint: Float = 0f
) {
    private fun weight(luma: Float, center: Float): Float {
        val distance = (luma - center) / 0.28f
        return exp(-0.5f * distance * distance)
    }

    fun sample(red: Float, green: Float, blue: Float): FloatArray {
        val r = red.coerceIn(0f, 1f)
        val g = green.coerceIn(0f, 1f)
        val b = blue.coerceIn(0f, 1f)
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val ws = weight(luma, 0.18f)
        val wm = weight(luma, 0.50f)
        val wh = weight(luma, 0.82f)
        val total = (ws + wm + wh).coerceAtLeast(0.0001f)
        val shadowWeight = ws / total
        val midWeight = wm / total
        val highlightWeight = wh / total

        val temperature =
            shadowsTemperature * shadowWeight +
            midtonesTemperature * midWeight +
            highlightsTemperature * highlightWeight
        val tint =
            shadowsTint * shadowWeight +
            midtonesTint * midWeight +
            highlightsTint * highlightWeight

        val redOut = (r + 0.12f * temperature + 0.08f * tint).coerceIn(0f, 1f)
        val greenOut = (g - 0.12f * tint).coerceIn(0f, 1f)
        val blueOut = (b - 0.12f * temperature + 0.08f * tint).coerceIn(0f, 1f)
        return floatArrayOf(redOut, greenOut, blueOut)
    }

    /** Media3 cube ordering is red outer, green middle, blue inner. */
    fun toMedia3ArgbCube(size: Int = 17): Array<Array<IntArray>> {
        require(size in 2..33) { "Color wheel LUT size must be 2..33" }
        val denominator = (size - 1).toFloat()
        return Array(size) { ri ->
            Array(size) { gi ->
                IntArray(size) { bi ->
                    val rgb = sample(ri / denominator, gi / denominator, bi / denominator)
                    val r = (rgb[0] * 255f).roundToInt()
                    val g = (rgb[1] * 255f).roundToInt()
                    val b = (rgb[2] * 255f).roundToInt()
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }
}
