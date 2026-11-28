package com.app.videoeditor

import androidx.media3.common.util.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class PipVideoCompositorSettingsTest {
    private fun plan() = buildPipVideoCompositionPlan(listOf(
        OverlayClip(
            id = 1L, uriString = "content://media/1", name = "PIP", durationMs = 6000L,
            width = 1920, height = 1080, startMs = 500L, endMs = 2500L,
            timelineStartMs = 1000L, x = 0.55f, y = -0.55f, scale = 0.3f,
            rotation = 12f, opacity = 0.65f
        )
    ))

    @Test fun inactivePipHasZeroAlphaAndActiveInputUsesLayerTransform() {
        val input = plan().videoInputs.single()
        val before = pipOverlayTransform(input, 999999L)
        val active = pipOverlayTransform(input, 1000000L)
        val after = pipOverlayTransform(input, 3000000L)
        assertEquals(0f, before.alpha, 0f)
        assertEquals(0.65f, active.alpha, 0f)
        assertEquals(0f, after.alpha, 0f)
        assertEquals(12f, active.rotationDegrees, 0f)
        assertEquals(0.3f, active.scaleX, 0f)
        assertEquals(0.55f, active.backgroundAnchorX, 0f)
        assertEquals(-0.55f, active.backgroundAnchorY, 0f)
    }

    @Test fun outputSizeUsesPrimaryTimelineInputRatherThanPipInput() {
        val settings = PipVideoCompositorSettings(plan())
        val pipSize = Size(640, 360)
        val primarySize = Size(1920, 1080)

        assertEquals(primarySize, settings.getOutputSize(listOf(pipSize, primarySize)))
    }

    @Test fun primaryOrUnknownInputRemainsFullyVisible() {
        val transform = pipOverlayTransform(null, 0L)
        assertEquals(1f, transform.alpha, 0f)
        assertEquals(1f, transform.scaleX, 0f)
        assertEquals(1f, transform.scaleY, 0f)
    }
}
