package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneCurveTest {
    @Test fun neutralCurveIsIdentity() {
        val curve = ToneCurve()
        for (i in 0..100) {
            val x = i / 100f
            assertEquals(x, curve.sample(x), 0.00001f)
        }
    }

    @Test fun curveOutputRemainsInNormalizedRange() {
        val curve = ToneCurve(shadows = 0.2f, midtones = -0.2f, highlights = 0.2f)
        for (i in 0..100) assertTrue(curve.sample(i / 100f) in 0f..1f)
    }

    @Test fun toneCurveLutHasArgbIdentityCorners() {
        val cube = ToneCurve().toMedia3ArgbCube()
        assertEquals(0xFF000000.toInt(), cube[0][0][0])
        assertEquals(0xFFFFFFFF.toInt(), cube[16][16][16])
        assertEquals(0xFF808080.toInt(), cube[8][8][8])
    }

    @Test fun curveControlsChangeExpectedTonalRegions() {
        val lift = ToneCurve(shadows = 0.15f)
        val shift = ToneCurve(midtones = 0.15f)
        val high = ToneCurve(highlights = 0.15f)
        assertTrue(lift.sample(0.2f) > 0.2f)
        assertTrue(shift.sample(0.5f) > 0.5f)
        assertTrue(high.sample(0.9f) > 0.9f)
    }
}
