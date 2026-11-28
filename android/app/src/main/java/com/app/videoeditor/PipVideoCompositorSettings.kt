@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.app.videoeditor

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.StaticOverlaySettings

/** Pure, JVM-testable layout values used to configure a Media3 overlay. */
internal data class PipOverlayTransform(
    val backgroundAnchorX: Float,
    val backgroundAnchorY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Float,
    val alpha: Float
)

internal fun pipOverlayTransform(input: PipVideoInputPlan?, presentationTimeUs: Long): PipOverlayTransform {
    if (input == null) return PipOverlayTransform(0f, 0f, 1f, 1f, 0f, 1f)
    val layer = input.layer
    val visible = isPipVideoInputVisible(input, presentationTimeUs)
    return PipOverlayTransform(
        backgroundAnchorX = layer.x.coerceIn(-1f, 1f),
        backgroundAnchorY = layer.y.coerceIn(-1f, 1f),
        scaleX = layer.scale,
        scaleY = layer.scale,
        rotationDegrees = layer.rotation,
        alpha = if (visible) layer.opacity.coerceIn(0f, 1f) else 0f
    )
}

/**
 * Media3 multi-sequence compositor layout used by buildComposition().
 *
 * The mapping is covered by JVM tests. Rendered-frame and audio acceptance tests are
 * still required before EditorViewModel.preflight() can allow PIP-video exports.
 */
class PipVideoCompositorSettings(private val plan: PipVideoCompositionPlan) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size {
        if (inputSizes.isEmpty()) return Size(1, 1)
        // Media3 passes the primary stream first, followed by secondary overlay streams.
        return inputSizes.first()
    }

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val input = plan.videoInputs.firstOrNull { it.inputId == inputId }
        val transform = pipOverlayTransform(input, presentationTimeUs)
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(transform.backgroundAnchorX, transform.backgroundAnchorY)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(transform.scaleX, transform.scaleY)
            .setRotationDegrees(transform.rotationDegrees)
            .setAlphaScale(transform.alpha)
            .build()
    }
}
