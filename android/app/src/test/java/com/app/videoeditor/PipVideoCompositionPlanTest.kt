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
    fun assignsPipInputsBeforePrimaryAndOrdersForegroundFirst() {
        val plan = buildPipVideoCompositionPlan(
            listOf(layer(30L, 2), layer(20L, 1), layer(10L, 1))
        )

        assertEquals(listOf(0, 1, 2), plan.videoInputs.map { it.inputId })
        assertEquals(listOf(30L, 10L, 20L), plan.videoInputs.map { it.layer.id })
        assertEquals(3, plan.primaryInputId)
        assertEquals(listOf(2, 1, 1), plan.videoInputs.map { it.layer.layerOrder })
    }

    @Test
    fun stillImagesAndInvalidLayersDoNotConsumeVideoInputIds() {
        val plan = buildPipVideoCompositionPlan(
            listOf(layer(1L, 0, image = true), layer(2L, 1, speed = Float.NaN), layer(3L, 2))
        )

        assertEquals(1, plan.videoInputs.size)
        assertEquals(0, plan.videoInputs.single().inputId)
        assertEquals(3L, plan.videoInputs.single().layer.id)
        assertEquals(1, plan.primaryInputId)
    }

    @Test
    fun sourceClockHonorsTimelineOffsetTrimSpeedAndExclusiveEnd() {
        val plan = buildPipVideoCompositionPlan(
            listOf(layer(5L, 0, timelineStartMs = 2_000L, speed = 2f))
        ).videoInputs.single()

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
        ).videoInputs.single()

        assertEquals(Long.MAX_VALUE, plan.timelineStartUs)
        assertEquals(Long.MAX_VALUE, plan.timelineEndUs)
        assertNull(plan.sourceTimeUs(Long.MAX_VALUE))

        val lateSource = layer(7L, 0, timelineStartMs = 0L).copy(
            durationMs = Long.MAX_VALUE,
            startMs = Long.MAX_VALUE - 1_000L,
            endMs = Long.MAX_VALUE
        )
        val sourcePlan = buildPipVideoCompositionPlan(listOf(lateSource)).videoInputs.single()
        assertEquals(Long.MAX_VALUE, sourcePlan.sourceTimeUs(500_000L))
    }

    @Test
    fun pipSequenceGetsTrailingGapToCoverFullProjectClock() {
        val input = buildPipVideoCompositionPlan(
            listOf(layer(41L, 0, timelineStartMs = 2_000L))
        ).videoInputs.single()

        assertEquals(4_000_000L, pipVideoTrailingGapUs(input, 10_000_000L))
        assertEquals(0L, pipVideoTrailingGapUs(input, 6_000_000L))
        assertEquals(0L, pipVideoTrailingGapUs(input, 5_000_000L))
    }

    @Test
    fun everyPipSequenceGetsTrailingGapToProjectEnd() {
        val inputs = buildPipVideoCompositionPlan(
            listOf(
                layer(51L, 1, timelineStartMs = 2_000L),
                layer(52L, 0, timelineStartMs = 4_000L)
            )
        ).videoInputs

        assertEquals(6_000_000L, pipVideoTrailingGapUs(inputs[0], 12_000_000L))
        assertEquals(4_000_000L, pipVideoTrailingGapUs(inputs[1], 12_000_000L))
    }

    @Test
    fun compositorVisibilityHidesLeadingAndTrailingGapFrames() {
        val input = buildPipVideoCompositionPlan(
            listOf(layer(40L, 0, timelineStartMs = 2_000L))
        ).videoInputs.single()

        assertTrue(!isPipVideoInputVisible(input, 1_999_999L))
        assertTrue(isPipVideoInputVisible(input, 2_000_000L))
        // The source trim is 4 seconds long (5_000 - 1_000 ms), so
        // at 1x speed the PIP interval ends at 6_000 ms on the project timeline.
        assertTrue(isPipVideoInputVisible(input, 5_999_999L))
        assertTrue(!isPipVideoInputVisible(input, 6_000_000L))
        assertTrue(!isPipVideoInputVisible(input, -1L))
    }

    @Test
    fun compositorUsesPrimaryVideoInputForOutputSizeIndex() {
        assertEquals(2, pipPrimaryInputIndex(primaryInputId = 2, inputCount = 3))
        assertEquals(1, pipPrimaryInputIndex(primaryInputId = 9, inputCount = 2))
        assertEquals(-1, pipPrimaryInputIndex(primaryInputId = 0, inputCount = 0))
    }

    @Test
    fun emptyOrImageOnlyProjectsHaveNoVideoInputs() {
        val empty = buildPipVideoCompositionPlan(emptyList())
        assertTrue(empty.videoInputs.isEmpty())
        assertEquals(0, empty.primaryInputId)

        val imageOnly = buildPipVideoCompositionPlan(listOf(layer(1L, 0, image = true)))
        assertTrue(imageOnly.videoInputs.isEmpty())
        assertEquals(0, imageOnly.primaryInputId)
    }
}
