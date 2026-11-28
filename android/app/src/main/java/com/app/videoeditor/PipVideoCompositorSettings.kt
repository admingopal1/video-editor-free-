@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.app.videoeditor

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.StaticOverlaySettings

/** Layout adapter for multi-sequence PIP export. Kept unwired until rendered-frame/audio acceptance passes. */
class PipVideoCompositorSettings(private val plan: PipVideoCompositionPlan) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size {
        if (inputSizes.isEmpty()) return Size(1, 1)
        return inputSizes[pipPrimaryInputIndex(plan.primaryInputId, inputSizes.size)]
    }

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val input = plan.videoInputs.firstOrNull { it.inputId == inputId }
            ?: return StaticOverlaySettings.Builder().build()
        val layer = input.layer
        val visible = isPipVideoInputVisible(input, presentationTimeUs)
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(layer.x.coerceIn(-1f, 1f), layer.y.coerceIn(-1f, 1f))
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(layer.scale, layer.scale)
            .setRotationDegrees(layer.rotation)
            .setAlphaScale(if (visible) layer.opacity.coerceIn(0f, 1f) else 0f)
            .build()
    }
}
