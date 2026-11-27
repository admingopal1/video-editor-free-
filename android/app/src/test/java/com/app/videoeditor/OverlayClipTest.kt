package com.app.videoeditor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayClipTest {
    private fun sample() = OverlayClip(
        id = 42L,
        uriString = "content://media/external/video/media/7",
        name = "PIP layer",
        durationMs = 12_000L,
        width = 1920,
        height = 1080,
        startMs = 1_000L,
        endMs = 9_000L,
        timelineStartMs = 2_500L,
        x = 0.4f,
        y = -0.3f,
        scale = 0.42f,
        rotation = 12f,
        opacity = 0.8f,
        volume = 0.6f,
        muted = false,
        layerOrder = 3
    )

    @Test
    fun overlayLayerRoundTripsAllEditingProperties() {
        val original = sample()
        val restored = overlayClipFromJson(original.toJson())

        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun timelineVisibilityUsesTrimmedOutputDurationAndStartOffset() {
        val layer = sample()

        assertFalse(layer.isVisibleAt(2_499L))
        assertTrue(layer.isVisibleAt(2_500L))
        assertTrue(layer.isVisibleAt(10_499L))
        assertFalse(layer.isVisibleAt(10_500L))
        assertEquals(8_000L, layer.outputMs)
    }

    @Test
    fun timelineEndSaturatesInsteadOfOverflowingLongRange() {
        val layer = sample().copy(timelineStartMs = Long.MAX_VALUE - 500L)

        assertEquals(Long.MAX_VALUE, layer.timelineEndMs)
        assertTrue(layer.isVisibleAt(Long.MAX_VALUE - 1L))
        assertFalse(layer.isVisibleAt(Long.MAX_VALUE))
    }

    @Test
    fun legacyStyleOverlayJsonUsesSafeDefaultsForOptionalProperties() {
        val json = JSONObject()
            .put("id", 9L)
            .put("uri", "content://media/external/images/media/2")
            .put("dur", 5_000L)
            .put("w", 1000)
            .put("h", 1000)

        val restored = overlayClipFromJson(json)

        assertNotNull(restored)
        assertEquals(0L, restored?.timelineStartMs)
        assertEquals(1f, restored?.speed)
        assertEquals(0.35f, restored?.scale)
        assertTrue(restored?.muted == true)
    }

    @Test
    fun layerReorderingMovesOnlyOneStepAndKeepsUniqueStableOrder() {
        val back = sample().copy(id = 1L, layerOrder = 0)
        val middle = sample().copy(id = 2L, layerOrder = 1)
        val front = sample().copy(id = 3L, layerOrder = 2)
        val original = listOf(back, middle, front)

        val movedForward = reorderOverlayLayer(original, 1L, 1)
        assertEquals(listOf(2L, 1L, 3L), movedForward.map { it.id })
        assertEquals(listOf(0, 1, 2), movedForward.map { it.layerOrder })

        val movedBackward = reorderOverlayLayer(movedForward, 1L, -1)
        assertEquals(original.map { it.id }, movedBackward.map { it.id })
        assertEquals(original, movedBackward)
    }

    @Test
    fun layerReorderingAtBoundariesIsANoOpAndUnknownIdsAreIgnored() {
        val first = sample().copy(id = 1L, layerOrder = 0)
        val second = sample().copy(id = 2L, layerOrder = 1)
        val layers = listOf(first, second)

        assertEquals(layers, reorderOverlayLayer(layers, 1L, -1))
        assertEquals(layers, reorderOverlayLayer(layers, 2L, 1))
        assertEquals(layers, reorderOverlayLayer(layers, 99L, 1))
        assertEquals(layers, reorderOverlayLayer(layers, 1L, 0))
    }

    @Test
    fun overlayValidationRejectsNonFiniteAndOutOfRangeEditingValues() {
        val valid = sample()

        assertFalse(valid.copy(speed = Float.NaN).isValidOverlayClip())
        assertFalse(valid.copy(speed = Float.POSITIVE_INFINITY).isValidOverlayClip())
        assertFalse(valid.copy(scale = Float.NEGATIVE_INFINITY).isValidOverlayClip())
        assertFalse(valid.copy(opacity = -0.01f).isValidOverlayClip())
        assertFalse(valid.copy(opacity = 1.01f).isValidOverlayClip())
        assertFalse(valid.copy(volume = -0.01f).isValidOverlayClip())
        assertFalse(valid.copy(volume = 2.01f).isValidOverlayClip())
        assertFalse(valid.copy(x = Float.NaN).isValidOverlayClip())
        assertFalse(valid.copy(y = Float.POSITIVE_INFINITY).isValidOverlayClip())
        assertFalse(valid.copy(rotation = Float.NaN).isValidOverlayClip())
    }

    @Test
    fun overlayVisibilityIsFalseBeforeStartAndAtExclusiveEnd() {
        val layer = sample()
        assertFalse(layer.isVisibleAt(-1L))
        assertFalse(layer.isVisibleAt(layer.timelineEndMs))
    }

    @Test
    fun invalidOverlayGeometryOrTimingIsRejected() {
        val invalid = sample().copy(endMs = 13_000L)

        assertFalse(invalid.isValidOverlayClip())
        assertNull(overlayClipFromJson(JSONObject().put("id", -1L)))
    }
}
