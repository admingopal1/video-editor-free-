@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import android.util.Size
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.effect.StaticOverlaySettings

/**
 * Media3 workaround for video PIP sequence gaps.
 *
 * Media3 currently represents a video gap with a placeholder black frame. A PIP
 * sequence must therefore be transparent during its leading/trailing gap, and
 * output dimensions must come from the primary sequence (which is ordered last).
 *
 * Keep this prototype behind the export preflight guard until decoded-frame
 * integration tests verify the behavior on real devices.
 */
class PipVideoCompositorSettings(
    private val plan: PipVideoCompositionPlan
) : VideoCompositorSettings {

    override fun getOutputSize(inputSizes: List<Size>): Size {
        val primaryIndex = pipPrimaryInputIndex(plan.primaryInputId, inputSizes.size)
        return inputSizes.getOrNull(primaryIndex)
            ?: inputSizes.lastOrNull()
            ?: Size(1, 1)
    }

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val input = plan.videoInputs.firstOrNull { it.inputId == inputId }
            ?: return StaticOverlaySettings.Builder().build()

        val alpha = if (isPipVideoInputVisible(input, presentationTimeUs)) {
            input.layer.opacity
        } else {
            0f
        }

        return StaticOverlaySettings.Builder()
            .setAlphaScale(alpha)
            .setBackgroundFrameAnchor(input.layer.x, input.layer.y)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(input.layer.scale, input.layer.scale)
            .setRotationDegrees(input.layer.rotation)
            .build()
    }
}

/** Returns true only within the layer's half-open project-timeline interval. */
internal fun isPipVideoInputVisible(
    input: PipVideoInputPlan,
    presentationTimeUs: Long
): Boolean = presentationTimeUs >= input.timelineStartUs &&
    presentationTimeUs < input.timelineEndUs

/** The primary sequence is deliberately the final video input. */
internal fun pipPrimaryInputIndex(primaryInputId: Int, inputCount: Int): Int =
    if (inputCount <= 0) -1 else primaryInputId.coerceIn(0, inputCount - 1)
