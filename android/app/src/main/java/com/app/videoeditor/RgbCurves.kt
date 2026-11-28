package com.app.videoeditor

import kotlin.math.roundToInt

/** Independent masterless RGB channel curves baked into one 3D LUT. */
data class RgbCurves(
    val red: ToneCurve = ToneCurve(),
    val green: ToneCurve = ToneCurve(),
    val blue: ToneCurve = ToneCurve()
) {
    fun sample(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        red.sample(r), green.sample(g), blue.sample(b)
    )

    /** Media3 cube ordering: red outer, green middle, blue inner. */
    fun toMedia3ArgbCube(size: Int = 17): Array<Array<IntArray>> {
        require(size in 2..33) { "RGB curve LUT size must be 2..33" }
        val denominator = (size - 1).toFloat()
        return Array(size) { ri -> Array(size) { gi -> IntArray(size) { bi ->
            val rgb = sample(ri / denominator, gi / denominator, bi / denominator)
            val rr = (rgb[0] * 255f).roundToInt()
            val gg = (rgb[1] * 255f).roundToInt()
            val bb = (rgb[2] * 255f).roundToInt()
            (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        } } }
    }
}
