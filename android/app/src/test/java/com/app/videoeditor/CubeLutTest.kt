package com.app.videoeditor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CubeLutTest {
    private fun identityCube(size: Int = 2): String = buildString {
        appendLine("TITLE \"Identity\"")
        appendLine("LUT_3D_SIZE $size")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val d = (size - 1).toFloat()
            appendLine("${r / d} ${g / d} ${b / d}")
        }
    }

    @Test fun parsesIdentityCubeAndSamplesCorners() {
        val lut = CubeLut.parse(identityCube())
        assertEquals("Identity", lut.title)
        assertEquals(2, lut.size)
        assertArrayEquals(floatArrayOf(1f, 0f, 1f), lut.sample(1f, 0f, 1f), 0.0001f)
    }

    @Test fun trilinearSamplingInterpolatesCenter() {
        val lut = CubeLut.parse(identityCube())
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), lut.sample(0.5f, 0.5f, 0.5f), 0.0001f)
    }

    @Test fun clampsInputsToDeclaredDomain() {
        val text = identityCube().replace("LUT_3D_SIZE 2", "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\nLUT_3D_SIZE 2")
        val lut = CubeLut.parse(text)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), lut.sample(-2f, 3f, 0f), 0.0001f)
    }

    @Test fun media3CubeUsesRedGreenBlueAxisOrderAndArgb8888() {
        val cube = CubeLut.parse(identityCube()).toMedia3ArgbCube()
        assertEquals(0xFFFF00FF.toInt(), cube[1][0][1])
        assertEquals(0xFF00FFFF.toInt(), cube[0][1][1])
        assertEquals(0xFFFFFFFF.toInt(), cube[1][1][1])
        assertEquals(0xFF000000.toInt(), cube[0][0][0])
    }

    @Test fun media3CubeBakesDeclaredDomainAndClampsOutput() {
        val text = identityCube().replace(
            "LUT_3D_SIZE 2",
            "DOMAIN_MIN 0.25 0.25 0.25\nDOMAIN_MAX 0.75 0.75 0.75\nLUT_3D_SIZE 2"
        )
        val cube = CubeLut.parse(text).toMedia3ArgbCube()
        assertEquals(0xFF000000.toInt(), cube[0][0][0])
        assertEquals(0xFFFFFFFF.toInt(), cube[1][1][1])
    }

    @Test fun zeroIntensityProducesIdentityCube() {
        val invert = """
            LUT_3D_SIZE 2
            1 1 1
            0 1 1
            1 0 1
            0 0 1
            1 1 0
            0 1 0
            1 0 0
            0 0 0
        """.trimIndent()
        val cube = CubeLut.parse(invert).toMedia3ArgbCube(0f)
        assertEquals(0xFF000000.toInt(), cube[0][0][0])
        assertEquals(0xFFFFFFFF.toInt(), cube[1][1][1])
    }

    @Test fun partialIntensityBlendsOriginalAndLutOutput() {
        val invert = """
            LUT_3D_SIZE 2
            1 1 1
            0 1 1
            1 0 1
            0 0 1
            1 1 0
            0 1 0
            1 0 0
            0 0 0
        """.trimIndent()
        val cube = CubeLut.parse(invert).toMedia3ArgbCube(0.5f)
        assertEquals(0xFF7F7F7F.toInt(), cube[0][0][0])
        assertEquals(0xFF7F7F7F.toInt(), cube[1][1][1])
    }

    @Test fun rejectsNonFiniteOrOutOfRangeIntensity() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f).forEach { amount ->
            try {
                CubeLut.parse(identityCube()).toMedia3ArgbCube(amount)
                fail("Expected invalid intensity to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }
    @Test fun rejectsMissingOrIncorrectEntryCount() {
        assertInvalid("TITLE missing size")
        assertInvalid("LUT_3D_SIZE 2\n0 0 0")
    }

    @Test fun rejectsMalformedAndUnsupportedData() {
        assertInvalid("LUT_3D_SIZE 2\n0 0 NaN")
        assertInvalid("LUT_3D_SIZE 2\nLUT_1D_SIZE 2")
        assertInvalid("LUT_3D_SIZE 65")
        assertInvalid("LUT_3D_SIZE 2\nDOMAIN_MIN 1 0 0\nDOMAIN_MAX 1 1 1")
        assertInvalid("LUT_3D_SIZE 2\n0 0 0 1")
    }

    private fun assertInvalid(text: String) {
        try {
            CubeLut.parse(text)
            fail("Expected invalid LUT to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }
}
