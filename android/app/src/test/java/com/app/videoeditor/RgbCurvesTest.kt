package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbCurvesTest {
    @Test fun neutralCurvesPreserveIdentity() {
        val out = RgbCurves().sample(0.2f, 0.5f, 0.8f)
        assertEquals(0.2f, out[0], 0.00001f); assertEquals(0.5f, out[1], 0.00001f); assertEquals(0.8f, out[2], 0.00001f)
    }
    @Test fun redCurveChangesOnlyRedChannel() {
        val out = RgbCurves(red = ToneCurve(midtones = 0.15f)).sample(0.5f, 0.5f, 0.5f)
        assertTrue(out[0] > 0.5f); assertEquals(0.5f, out[1], 0.00001f); assertEquals(0.5f, out[2], 0.00001f)
    }
    @Test fun greenAndBlueCurvesAreIndependent() {
        val out = RgbCurves(green = ToneCurve(shadows = 0.1f), blue = ToneCurve(highlights = -0.1f)).sample(0.2f, 0.2f, 0.9f)
        assertEquals(0.2f, out[0], 0.00001f); assertTrue(out[1] > 0.2f); assertTrue(out[2] < 0.9f)
    }
    @Test fun cubePreservesIdentityCornersAndSize() {
        val cube = RgbCurves().toMedia3ArgbCube(5)
        assertEquals(5, cube.size); assertEquals(0xFF000000.toInt(), cube[0][0][0]); assertEquals(0xFFFFFFFF.toInt(), cube[4][4][4])
    }
    @Test fun curveOutputStaysInRange() {
        val curves = RgbCurves(ToneCurve(0.2f, -0.2f, 0.2f), ToneCurve(-0.2f, 0.2f, -0.2f), ToneCurve(0.2f, 0.2f, 0.2f))
        for (i in 0..100) {
            val v = i / 100f
            assertTrue(curves.sample(v, v, v).all { it in 0f..1f })
        }
    }
}
