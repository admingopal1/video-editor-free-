package com.app.videoeditor

enum class TextLayerSnapMode { MOVE, TRIM_START, TRIM_END }
data class TextLayerSnapResult(val layer: TextLayer, val snappedToMs: Long?)

/** Magnetically aligns the moved or trimmed text/sticker edge to clip-relative snap points. */
fun snapTextLayerTiming(layer: TextLayer, clipOutputDurationMs: Long, deltaMs: Long,
    snapPointsMs: List<Long>, thresholdMs: Long, mode: TextLayerSnapMode): TextLayerSnapResult {
    val total = clipOutputDurationMs.coerceAtLeast(0L)
    if (total < 100L || deltaMs == 0L || thresholdMs < 0L) return TextLayerSnapResult(layer, null)
    val proposed = when (mode) {
        TextLayerSnapMode.MOVE -> moveTextLayerTiming(layer, total, deltaMs)
        TextLayerSnapMode.TRIM_START -> trimTextLayerStart(layer, total, deltaMs)
        TextLayerSnapMode.TRIM_END -> trimTextLayerEnd(layer, total, deltaMs)
    }
    val t = textLayerTiming(proposed, total)
    data class Candidate(val distance: Long, val point: Long, val edge: Int)
    val candidates = snapPointsMs.asSequence().filter { it in 0L..total }.distinct().flatMap { point ->
        when (mode) {
            TextLayerSnapMode.MOVE -> sequenceOf(Candidate(kotlin.math.abs(t.startMs-point),point,0), Candidate(kotlin.math.abs(t.endMs-point),point,1))
            TextLayerSnapMode.TRIM_START -> sequenceOf(Candidate(kotlin.math.abs(t.startMs-point),point,0))
            TextLayerSnapMode.TRIM_END -> sequenceOf(Candidate(kotlin.math.abs(t.endMs-point),point,1))
        }
    }.filter { it.distance <= thresholdMs }.minWithOrNull(compareBy<Candidate> { it.distance }.thenBy { it.point }.thenBy { it.edge })
        ?: return TextLayerSnapResult(proposed, null)
    val snapped = when (mode) {
        TextLayerSnapMode.MOVE -> {
            val duration=t.durationMs
            val start=(if(candidates.edge==0) candidates.point else candidates.point-duration).coerceIn(0L,(total-duration).coerceAtLeast(0L))
            proposed.copy(startFrac=start.toFloat()/total,endFrac=(start+duration).toFloat()/total)
        }
        TextLayerSnapMode.TRIM_START -> proposed.copy(startFrac=candidates.point.coerceIn(0L,(t.endMs-100L).coerceAtLeast(0L)).toFloat()/total)
        TextLayerSnapMode.TRIM_END -> proposed.copy(endFrac=candidates.point.coerceIn((t.startMs+100L).coerceAtMost(total),total).toFloat()/total)
    }
    return TextLayerSnapResult(snapped,candidates.point)
}
