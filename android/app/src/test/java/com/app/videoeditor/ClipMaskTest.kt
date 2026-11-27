package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipMaskTest {
    @Test fun noneShapePreservesEveryPixel() {
        assertEquals(1f, clipMaskCoverage(ClipMaskShape.None, 0f, 0f, 0.5f, 0.5f, 0.8f, 0.8f, 0.1f), 0f)
    }
    @Test fun rectangleKeepsCenterAndRemovesOutside() {
        assertEquals(1f, clipMaskCoverage(ClipMaskShape.Rectangle, 0.5f, 0.5f, 0.5f, 0.5f, 0.6f, 0.6f, 0f), 0f)
        assertEquals(0f, clipMaskCoverage(ClipMaskShape.Rectangle, 0.95f, 0.5f, 0.5f, 0.5f, 0.6f, 0.6f, 0f), 0f)
    }
    @Test fun ellipseRemovesBoundingBoxCorners() {
        assertEquals(1f, clipMaskCoverage(ClipMaskShape.Ellipse, 0.5f, 0.5f, 0.5f, 0.5f, 0.8f, 0.8f, 0f), 0f)
        assertEquals(0f, clipMaskCoverage(ClipMaskShape.Ellipse, 0.1f, 0.1f, 0.5f, 0.5f, 0.8f, 0.8f, 0f), 0f)
    }
    @Test fun featherIsSmoothAndInvertComplementsCoverage() {
        val edge = clipMaskCoverage(ClipMaskShape.Rectangle, 0.8f, 0.5f, 0.5f, 0.5f, 0.6f, 0.6f, 0.1f)
        assertEquals(0.5f, edge, 0.001f)
        val inverted = clipMaskCoverage(ClipMaskShape.Rectangle, 0.8f, 0.5f, 0.5f, 0.5f, 0.6f, 0.6f, 0.1f, true)
        assertEquals(1f - edge, inverted, 0.001f)
    }
    @Test fun invalidGeometryIsRejected() {
        assertTrue(isValidClipMask(ClipMaskShape.Ellipse, 0.5f, 0.5f, 0.8f, 0.8f, 0.05f))
        assertFalse(isValidClipMask(ClipMaskShape.Rectangle, Float.NaN, 0.5f, 0.8f, 0.8f, 0.05f))
        assertFalse(isValidClipMask(ClipMaskShape.Rectangle, 0.5f, 0.5f, 0f, 0.8f, 0.05f))
        assertFalse(isValidClipMask(ClipMaskShape.Rectangle, 0.5f, 0.5f, 0.8f, 0.8f, Float.POSITIVE_INFINITY))
    }
}
