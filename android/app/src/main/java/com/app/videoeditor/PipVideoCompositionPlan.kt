package com.app.videoeditor

private fun millisecondsToMicrosecondsSaturated(valueMs: Long): Long =
    if (valueMs > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else valueMs * 1_000L

/**
 * Stable input assignment for the future multi-source PIP compositor.
 *
 * Media3's official PIP example uses input 0 as the foreground overlay and the
 * later input as the background. Therefore video overlays are planned first,
 * from front to back, and the primary timeline is assigned the final input ID.
 *
 * This is planning data only: export remains guarded until an instrumented
 * compositor test verifies multi-overlay z-order, inactive gaps, audio and clocks.
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

data class PipVideoCompositionPlan(
    val videoInputs: List<PipVideoInputPlan>,
    /** The base/primary timeline is last, matching Media3's documented PIP example. */
    val primaryInputId: Int
)

fun buildPipVideoCompositionPlan(layers: List<OverlayClip>): PipVideoCompositionPlan {
    val videoInputs = layers.asSequence()
        .filter { !it.isImage && it.isValidOverlayClip() }
        // Media3 documents input 0 as foreground. Higher layerOrder is in front.
        .sortedWith(compareByDescending<OverlayClip> { it.layerOrder }.thenBy { it.id })
        .mapIndexed { index, layer -> PipVideoInputPlan(index, layer) }
        .toList()
    return PipVideoCompositionPlan(
        videoInputs = videoInputs,
        primaryInputId = videoInputs.size
    )
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
