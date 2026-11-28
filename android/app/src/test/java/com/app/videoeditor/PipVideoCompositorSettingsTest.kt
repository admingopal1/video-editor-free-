package com.app.videoeditor

import androidx.media3.common.util.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class PipVideoCompositorSettingsTest {
    private fun layer() = OverlayClip(
        id = 1L, uriString = "content://media/1", name = "PIP", durationMs = 6000L,
        width = 1920, height = 1080, startMs = 500L, endMs = 2500L,
        timelineStartMs = 1000L, x = 0.55f, y = -0.55f, scale = 0.3f,
        rotation = 12f, opacity = 0.65f
    )

    @Test fun outputSizeComesFromPrimarySequence() {
        val settings = PipVideoCompositorSettings(buildPipVideoCompositionPlan(listOf(layer())))
        assertEquals(Size(1920, 1080), settings.getOutputSize(listOf(Size(640, 480), Size(1920, 1080))))
    }

    @Test fun inactivePipIsTransparentAndActiveInputUsesLayerTransform() {
        val plan = buildPipVideoCompositionPlan(listOf(layer()))
        val settings = PipVideoCompositorSettings(plan)
        val before = settings.getOverlaySettings(0, 999999L)
        val active = settings.getOverlaySettings(0, 1000000L)
        val after = settings.getOverlaySettings(0, 3000000L)
        assertEquals(0f, before.alphaScale, 0f)
        assertEquals(0.65f, active.alphaScale, 0f)
        assertEquals(0f, after.alphaScale, 0f)
        assertEquals(12f, active.rotationDegrees, 0f)
        assertEquals(0.3f, active.scale.first, 0f)
        assertEquals(0.55f, active.backgroundFrameAnchor.first, 0f)
    }

    @Test fun primaryInputRemainsFullyVisible() {
        val plan = buildPipVideoCompositionPlan(listOf(layer()))
        val settings = PipVideoCompositorSettings(plan)
        assertEquals(1f, settings.getOverlaySettings(plan.primaryInputId, 0L).alphaScale, 0f)
    }
}
