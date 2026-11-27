package com.app.videoeditor

/**
 * Parsed 3D .cube LUT. Values follow the .cube ordering: red changes fastest,
 * then green, then blue. Sampling is trilinear and clamps to the declared domain.
 *
 * This parser deliberately handles 3D LUT files only; it does not silently
 * reinterpret 1D LUTs or vendor-specific directives.
 */
class CubeLut private constructor(
    val title: String?,
    val size: Int,
    val domainMin: List<Float>,
    val domainMax: List<Float>,
    private val rgb: FloatArray
) {
    /** Returns a newly allocated RGB triplet in the LUT's normalized output space. */
    fun sample(red: Float, green: Float, blue: Float): FloatArray {
        val p = floatArrayOf(red, green, blue)
        val lo = IntArray(3)
        val hi = IntArray(3)
        val f = FloatArray(3)
        for (axis in 0..2) {
            val span = domainMax[axis] - domainMin[axis]
            val normalized = ((p[axis] - domainMin[axis]) / span).coerceIn(0f, 1f)
            val coordinate = normalized * (size - 1)
            lo[axis] = kotlin.math.floor(coordinate).toInt().coerceIn(0, size - 1)
            hi[axis] = (lo[axis] + 1).coerceAtMost(size - 1)
            f[axis] = coordinate - lo[axis]
        }

        fun channel(r: Int, g: Int, b: Int, c: Int): Float =
            rgb[((b * size + g) * size + r) * 3 + c]
        fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

        val out = FloatArray(3)
        for (c in 0..2) {
            val c000 = channel(lo[0], lo[1], lo[2], c)
            val c100 = channel(hi[0], lo[1], lo[2], c)
            val c010 = channel(lo[0], hi[1], lo[2], c)
            val c110 = channel(hi[0], hi[1], lo[2], c)
            val c001 = channel(lo[0], lo[1], hi[2], c)
            val c101 = channel(hi[0], lo[1], hi[2], c)
            val c011 = channel(lo[0], hi[1], hi[2], c)
            val c111 = channel(hi[0], hi[1], hi[2], c)
            val z0 = mix(mix(c000, c100, f[0]), mix(c010, c110, f[0]), f[1])
            val z1 = mix(mix(c001, c101, f[0]), mix(c011, c111, f[0]), f[1])
            out[c] = mix(z0, z1, f[2])
        }
        return out
    }

    /**
     * Converts this LUT to Media3's cube[R][G][B] ARGB_8888 layout.
     * Sampling the normalized input grid also bakes DOMAIN_MIN/MAX into the table,
     * so the GPU effect can consume ordinary normalized video RGB values.
     */
    fun toMedia3ArgbCube(): Array<Array<IntArray>> =
        Array(size) { r ->
            Array(size) { g ->
                IntArray(size) { b ->
                    val d = (size - 1).toFloat()
                    val out = sample(r / d, g / d, b / d)
                    val red = (out[0].coerceIn(0f, 1f) * 255f).toInt()
                    val green = (out[1].coerceIn(0f, 1f) * 255f).toInt()
                    val blue = (out[2].coerceIn(0f, 1f) * 255f).toInt()
                    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }

    companion object {
        const val MIN_SIZE = 2
        const val MAX_SIZE = 64

        /** Parses a standard 3D .cube text file or throws IllegalArgumentException. */
        fun parse(text: String): CubeLut {
            require(text.isNotBlank()) { "LUT file is empty" }
            var title: String? = null
            var size: Int? = null
            var min = listOf(0f, 0f, 0f)
            var max = listOf(1f, 1f, 1f)
            var minSeen = false
            var maxSeen = false
            val rows = ArrayList<FloatArray>()

            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                val directive = parts[0].uppercase()
                fun fail(reason: String): Nothing =
                    throw IllegalArgumentException("Line ${index + 1}: $reason")

                when (directive) {
                    "TITLE" -> {
                        if (parts.size < 2) fail("TITLE requires a value")
                        title = line.substringAfter(parts[0]).trim().removeSurrounding("\"", "\"")
                    }
                    "LUT_3D_SIZE" -> {
                        if (size != null || rows.isNotEmpty() || parts.size != 2) fail("Invalid or duplicate LUT_3D_SIZE")
                        val parsed = parts[1].toIntOrNull() ?: fail("Invalid LUT_3D_SIZE")
                        if (parsed !in MIN_SIZE..MAX_SIZE) fail("LUT_3D_SIZE must be $MIN_SIZE..$MAX_SIZE")
                        size = parsed
                    }
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        if (rows.isNotEmpty() || parts.size != 4) fail("$directive requires three values before LUT data")
                        val values = parts.drop(1).map { it.toFloatOrNull() ?: fail("Invalid $directive value") }
                        if (values.any { !it.isFinite() }) fail("$directive values must be finite")
                        if (directive == "DOMAIN_MIN") {
                            if (minSeen) fail("Duplicate DOMAIN_MIN")
                            min = values; minSeen = true
                        } else {
                            if (maxSeen) fail("Duplicate DOMAIN_MAX")
                            max = values; maxSeen = true
                        }
                    }
                    "LUT_1D_SIZE" -> fail("1D LUT files are not supported; use a 3D .cube LUT")
                    else -> {
                        if (size == null) fail("LUT_3D_SIZE must appear before RGB data")
                        if (parts.size != 3) fail("Expected an RGB triplet")
                        val values = parts.map { it.toFloatOrNull() ?: fail("Invalid RGB value") }
                        if (values.any { !it.isFinite() }) fail("RGB values must be finite")
                        rows += floatArrayOf(values[0], values[1], values[2])
                        if (rows.size > size!! * size!! * size!!) fail("Too many LUT entries")
                    }
                }
            }

            val n = size ?: throw IllegalArgumentException("Missing LUT_3D_SIZE")
            for (axis in 0..2) require(min[axis] < max[axis]) { "DOMAIN_MIN must be less than DOMAIN_MAX on every axis" }
            val expected = n * n * n
            require(rows.size == expected) { "Expected $expected RGB entries, found ${rows.size}" }
            val values = FloatArray(expected * 3)
            rows.forEachIndexed { i, row ->
                values[i * 3] = row[0]
                values[i * 3 + 1] = row[1]
                values[i * 3 + 2] = row[2]
            }
            return CubeLut(title, n, min, max, values)
        }
    }
}
