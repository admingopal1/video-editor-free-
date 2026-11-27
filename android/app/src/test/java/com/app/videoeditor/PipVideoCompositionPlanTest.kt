package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipVideoCompositionPlanTest {
    private fun layer(
        id: Long,
        order: Int,
        startMs: Long = 1_000L,
        endMs: Long = 5_000L,
        timelineStartMs: Long = 2_000L,
        speed: Float = 1f,
        image: Boolean = false
    ) = OverlayClip(
        id = id,
        uriString = "content://media/$id",
        name = "Layer $id",
        durationMs = 8_000L,
        width = 1280,
        height = 720,
        startMs = startMs,
        endMs = endMs,
        timelineStartMs = timelineStartMs,
        speed = speed,
        isImage = image,
        layerOrder = order
    )

    @Test
    fun reservesInputZeroAndAssignsVideoInputsBackToFrontDeterministically() {
        val plan = buildPipVideoCompositionPlan(
            listOf(
                layer(id = 30L, order = 2),
                layer(id = 20L, order = 1),
                layer(id = 10L, order = 1)
            )
        )

        assertEquals(listOf(1, 2, 3), plan.map { it.inputId })
        assertEquals(listOf(10L, 20L, 30L), plan.map { it.layer.id })
        assertEquals(listOf(1, 1, 2), plan.map { it.layer.layerOrder })
    }

    @Test
    fun stillImagesAndInvalidLayersDoNotConsumeVideoInputIds() {
        val plan = buildPipVideoCompositionPlan(
            listOf(
                layer(id = 1L, order = 0, image = true),
                layer(id = 2L, order = 1, speed = Float.NaN),
                layer(id = 3L, order = 2)
            )
        )

        assertEquals(1, plan.size)
        assertEquals(1, plan.single().inputId)
        assertEquals(3L, plan.single().layer.id)
    }

    @Test
    fun sourceClockHonorsTimelineOffsetTrimSpeedAndExclusiveEnd() {
        val plan = buildPipVideoCompositionPlan(
            listOf(layer(id = 5L, order = 0, timelineStartMs = 2_000L, speed = 2f))
        ).single()

        assertEquals(2_000_000L, plan.timelineStartUs)
        assertEquals(4_000_000L, plan.timelineEndUs)
        assertNull(plan.sourceTimeUs(1_999_999L))
        assertEquals(1_000_000L, plan.sourceTimeUs(2_000_000L))
        assertEquals(3_000_000L, plan.sourceTimeUs(3_000_000L))
        assertNull(plan.sourceTimeUs(4_000_000L))
    }

    @Test
    fun emptyOrImageOnlyProjectsHaveNoVideoInputs() {
        assertTrue(buildPipVideoCompositionPlan(emptyList()).isEmpty())
        assertTrue(buildPipVideoCompositionPlan(listOf(layer(id = 1L, order = 0, image = true))).isEmpty())
    }
}
