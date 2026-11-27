package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipVideoCompositionPlanTest {
    private fun layer(
        id: Long,
        order: Int,
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
        startMs = 1_000L,
        endMs = 5_000L,
        timelineStartMs = timelineStartMs,
        speed = speed,
        isImage = image,
        layerOrder = order
    )

    @Test
    fun reservesInputZeroAndAssignsVideoInputsBackToFrontDeterministically() {
        val plan = buildPipVideoCompositionPlan(
            listOf(layer(30L, 2), layer(20L, 1), layer(10L, 1))
        )

        assertEquals(listOf(1, 2, 3), plan.map { it.inputId })
        assertEquals(listOf(10L, 20L, 30L), plan.map { it.layer.id })
        assertEquals(listOf(1, 1, 2), plan.map { it.layer.layerOrder })
    }

    @Test
    fun stillImagesAndInvalidLayersDoNotConsumeVideoInputIds() {
        val plan = buildPipVideoCompositionPlan(
            listOf(layer(1L, 0, image = true), layer(2L, 1, speed = Float.NaN), layer(3L, 2))
        )

        assertEquals(1, plan.size)
        assertEquals(1, plan.single().inputId)
        assertEquals(3L, plan.single().layer.id)
    }

    @Test
    fun sourceClockHonorsTimelineOffsetTrimSpeedAndExclusiveEnd() {
        val plan = buildPipVideoCompositionPlan(
            listOf(layer(5L, 0, timelineStartMs = 2_000L, speed = 2f))
        ).single()

        assertEquals(2_000_000L, plan.timelineStartUs)
        assertEquals(4_000_000L, plan.timelineEndUs)
        assertNull(plan.sourceTimeUs(1_999_999L))
        assertEquals(1_000_000L, plan.sourceTimeUs(2_000_000L))
        assertEquals(3_000_000L, plan.sourceTimeUs(3_000_000L))
        assertNull(plan.sourceTimeUs(4_000_000L))
    }

    @Test
    fun timestampConversionSaturatesInsteadOfOverflowing() {
        val plan = buildPipVideoCompositionPlan(
            listOf(layer(6L, 0, timelineStartMs = Long.MAX_VALUE - 1L))
        ).single()

        assertEquals(Long.MAX_VALUE, plan.timelineStartUs)
        assertEquals(Long.MAX_VALUE, plan.timelineEndUs)
        // This timestamp is before the saturated timeline start, so the input is inactive.
        assertNull(plan.sourceTimeUs(Long.MAX_VALUE))

        val lateSource = layer(7L, 0, timelineStartMs = 0L).copy(
            durationMs = Long.MAX_VALUE,
            startMs = Long.MAX_VALUE - 1_000L,
            endMs = Long.MAX_VALUE
        )
        val sourcePlan = buildPipVideoCompositionPlan(listOf(lateSource)).single()
        // A valid source timestamp near Long.MAX_VALUE must saturate when converted to microseconds.
        assertEquals(Long.MAX_VALUE, sourcePlan.sourceTimeUs(500_000L))
    }

    @Test
    fun emptyOrImageOnlyProjectsHaveNoVideoInputs() {
        assertTrue(buildPipVideoCompositionPlan(emptyList()).isEmpty())
        assertTrue(buildPipVideoCompositionPlan(listOf(layer(1L, 0, image = true))).isEmpty())
    }
}
