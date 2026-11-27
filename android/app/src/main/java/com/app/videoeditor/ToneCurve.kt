package com.app.videoeditor

/**
 * Lightweight master RGB tone curve represented by shadow, midtone and highlight offsets.
 * The curve is baked into a 3D LUT so preview and export use the same GPU operation.
 */
data class ToneCurve(
    val shadows: Float = 0f,
    val midtones: Float = 0f,
    val highlights: Float = 0f
) {
    fun sample(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        val shadowWeight = (1f - 2f * x).coerceIn(0f, 1f) * (1f - x)
        val midWeight = kotlin.math.sin(Math.PI.toDouble() * x).toFloat()
        val highlightWeight = x * x
        return (x + shadows * shadowWeight + midtones * midWeight + highlights * highlightWeight)
            .coerceIn(0f, 1f)
    }

    fun toMedia3ArgbCube(size: Int = 17): Array<Array<IntArray>> {
        require(size in 2..33) { "Tone curve LUT size must be 2..33" }
        return Array(size) { r ->
            Array(size) { g ->
                IntArray(size) { b ->
                    val d = (size - 1).toFloat()
                    val red = (sample(r / d) * 255f).roundToInt()
                    val green = (sample(g / d) * 255f).toInt()
                    val blue = (sample(b / d) * 255f).toInt()
                    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
    }
}
