@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.OverlaySettings

/**
 * Maps the deterministic PIP input plan to Media3 compositor layout settings.
 *
 * PIP inputs are foreground inputs and the primary timeline is the final/background
 * input. Outside a PIP layer's active project interval its alpha is set to zero so
 * any gap placeholder frame cannot cover the primary sequence. This adapter is not
 * enabled in export until a real 1.5.1 composition export verifies gap timestamps
 * and transparent pass-through on supported devices.
 */
class PipVideoCompositorSettings(
    private val plan: PipVideoCompositionPlan
) : VideoCompositorSettings {

    override fun getOutputSize(inputSizes: List<Size>): Size =
        inputSizes.getOrNull(plan.primaryInputId)
            ?: inputSizes.lastOrNull()
            ?: Size.UNKNOWN

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val layerInput = plan.videoInputs.firstOrNull { it.inputId == inputId }
            ?: return OverlaySettings.Builder().setAlphaScale(1f).build()

        val layer = layerInput.layer
        val visible = isPipVideoInputVisible(layerInput, presentationTimeUs)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(layer.x, layer.y)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(layer.scale, layer.scale)
            .setRotationDegrees(layer.rotation)
            .setAlphaScale(if (visible) layer.opacity else 0f)
            .build()
    }
}
