package com.app.videoeditor

/**
 * Stable input assignment for the future multi-source PIP compositor.
 *
 * Input 0 is reserved for the already-rendered primary timeline. PIP video inputs
 * are assigned from 1 in deterministic back-to-front layer order. This is planning
 * data only: export remains guarded until a compositor can guarantee the required
 * primary/background ordering and provide frames across each input's inactive gaps.
 */
data class PipVideoInputPlan(
    val inputId: Int,
    val layer: OverlayClip
) {
    val timelineStartUs: Long get() = layer.timelineStartMs * 1_000L
    val timelineEndUs: Long get() = layer.timelineEndMs * 1_000L

    /** Returns source PTS in microseconds, or null while this layer is inactive. */
    fun sourceTimeUs(projectTimeUs: Long): Long? {
        if (projectTimeUs < 0L) return null
        val sourceMs = overlaySourceTimeMs(layer, projectTimeUs / 1_000L) ?: return null
        return sourceMs * 1_000L
    }
}

/**
 * Builds deterministic PIP-video input IDs. Images are already handled by the
 * existing per-clip OverlayEffect path and must not consume video input IDs.
 */
fun buildPipVideoCompositionPlan(layers: List<OverlayClip>): List<PipVideoInputPlan> =
    layers.asSequence()
        .filter { !it.isImage && it.isValidOverlayClip() }
        .sortedWith(compareBy<OverlayClip> { it.layerOrder }.thenBy { it.id })
        .mapIndexed { index, layer -> PipVideoInputPlan(index + 1, layer) }
        .toList()
