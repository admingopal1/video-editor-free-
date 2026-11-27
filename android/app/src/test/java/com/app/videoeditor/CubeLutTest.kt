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
