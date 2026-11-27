package com.app.videoeditor

import android.net.Uri
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
        uri = Uri.parse("content://media/external/video/media/7"),
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
    fun invalidOverlayGeometryOrTimingIsRejected() {
        val invalid = sample().copy(endMs = 13_000L)

        assertFalse(invalid.isValidOverlayClip())
        assertNull(overlayClipFromJson(JSONObject().put("id", -1L)))
    }
}
