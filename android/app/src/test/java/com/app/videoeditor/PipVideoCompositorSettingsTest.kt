package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipVideoCompositorSettingsTest {
    private fun layer(
        id: Long,
        order: Int,
        startMs: Long = 1_000L,
        speed: Float = 1f,
        opacity: Float = 0.65f
    ) = OverlayClip(
        id = id,
        uriString = "content://media/$id",
        name = "PIP $id",
        durationMs = 10_000L,
        width = 1920,
        height = 1080,
        startMs = 500L,
        endMs = 4_500L,
        timelineStartMs = startMs,
        speed = speed,
        x = -0.4f,
        y = 0.3f,
        scale = 0.42f,
        rotation = 17f,
        opacity = opacity,
        layerOrder = order
    )

    @Test
    fun compositorPlanUsesForegroundInputIdsAndPrimaryAsBackground() {
        val plan = buildPipVideoCompositionPlan(
            listOf(layer(1L, 0), layer(2L, 3), layer(3L, 1))
        )
        assertEquals(listOf(2L, 3L, 1L), plan.videoInputs.map { it.layer.id })
        assertEquals(3, plan.primaryInputId)
    }

    @Test
    fun visibilityUsesHalfOpenTimelineAndSourceTrimSpeed() {
        val input = buildPipVideoCompositionPlan(
            listOf(layer(4L, 0, startMs = 2_000L, speed = 2f))
        ).videoInputs.single()

        assertNull(input.sourceTimeUs(1_999_999L))
        assertEquals(500_000L, input.sourceTimeUs(2_000_000L))
        assertEquals(4_498_000L, input.sourceTimeUs(3_999_999L))
        assertNull(input.sourceTimeUs(4_000_000L))
    }

    @Test
    fun zeroOpacityIsPreservedAsAnExplicitTransparentLayerValue() {
        val transparent = layer(5L, 0, opacity = 0f)
        assertEquals(0f, transparent.opacity)
        assertEquals(true, transparent.isValidOverlayClip())
    }
}
