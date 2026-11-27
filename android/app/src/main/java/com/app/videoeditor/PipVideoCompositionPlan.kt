package com.app.videoeditor

private fun millisecondsToMicrosecondsSaturated(valueMs: Long): Long =
    if (valueMs > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else valueMs * 1_000L

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
    val timelineStartUs: Long get() = millisecondsToMicrosecondsSaturated(layer.timelineStartMs)
    val timelineEndUs: Long get() = millisecondsToMicrosecondsSaturated(layer.timelineEndMs)

    /** Returns source PTS in microseconds, or null while this layer is inactive. */
    fun sourceTimeUs(projectTimeUs: Long): Long? {
        if (projectTimeUs < 0L) return null
        val sourceMs = overlaySourceTimeMs(layer, projectTimeUs / 1_000L) ?: return null
        return millisecondsToMicrosecondsSaturated(sourceMs)
    }
}

fun buildPipVideoCompositionPlan(layers: List<OverlayClip>): List<PipVideoInputPlan> =
    layers.asSequence()
        .filter { !it.isImage && it.isValidOverlayClip() }
        .sortedWith(compareBy<OverlayClip> { it.layerOrder }.thenBy { it.id })
        .mapIndexed { index, layer -> PipVideoInputPlan(index + 1, layer) }
        .toList()
