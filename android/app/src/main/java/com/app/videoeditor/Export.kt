@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.CharacterStyle
import android.text.style.ReplacementSpan
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Brightness
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import kotlin.math.PI
import kotlin.math.sin

private val sepiaMatrix = object : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = floatArrayOf(
        0.393f, 0.349f, 0.272f, 0f,
        0.769f, 0.686f, 0.534f, 0f,
        0.189f, 0.168f, 0.131f, 0f,
        0f, 0f, 0f, 1f
    )
}

/** Remembers the first timestamp it sees so effects work whether timestamps are per-clip or absolute. */
private class TimeBase {
    private var base = -1L
    fun rel(t: Long): Long {
        if (base < 0) base = t
        return (t - base).coerceAtLeast(0L)
    }
}

private fun animEffect(c: Clip): Effect? {
    if (c.anim == Anim.None) return null
    val total = (c.outputMs * 1000L).coerceAtLeast(1L)
    val tb = TimeBase()
    return object : MatrixTransformation {
        override fun getMatrix(presentationTimeUs: Long): Matrix {
            val xf = Motion.anim(c.anim, tb.rel(presentationTimeUs), total)
            val m = Matrix()
            m.postScale(xf.scale, xf.scale)
            m.postTranslate(xf.dx, 0f)
            return m
        }
    }
}

/** A still-image PIP overlay that is visible only during its timeline interval. */
private class TimedBitmapOverlay(
    private val bitmap: Bitmap,
    private val layer: OverlayClip,
    private val startUs: Long,
    private val endUs: Long
) : BitmapOverlay() {
    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val visible = presentationTimeUs >= startUs && presentationTimeUs < endUs
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(layer.x, layer.y)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(layer.scale, layer.scale)
            .setRotationDegrees(layer.rotation)
            .setAlphaScale(if (visible) layer.opacity else 0f)
            .build()
    }
}

private fun decodeOverlayBitmap(context: Context, layer: OverlayClip): Bitmap? =
    runCatching {
        context.contentResolver.openInputStream(Uri.parse(layer.uriString))?.use {
            BitmapFactory.decodeStream(it)
        }
    }.getOrNull()

private fun diagMatrix(o: Float): FloatArray {
    val f = o.coerceIn(0f, 1f)
    return floatArrayOf(
        f, 0f, 0f, 0f,
        0f, f, 0f, 0f,
        0f, 0f, f, 0f,
        0f, 0f, 0f, 1f
    )
}

private fun kfMatrix(c: Clip, k: Keyframe): Matrix {
    val a = c.frameAspect()
    val m = Matrix()
    m.postScale(k.scale, k.scale)
    if (k.rotation != 0f) {
        m.postScale(a, 1f); m.postRotate(k.rotation); m.postScale(1f / a, 1f)
    }
    m.postTranslate(k.x, k.y)
    return m
}

/** Keyframe animation. Export animates over time; preview shows the values at the playhead. */
private fun kfEffects(c: Clip, previewFrac: Float, forExport: Boolean): List<Effect> {
    if (c.keyframes.isEmpty()) return emptyList()
    val kfs = c.keyframes.sortedBy { it.t }
    val out = mutableListOf<Effect>()
    if (forExport) {
        val total = (c.outputMs * 1000L).coerceAtLeast(1L)
        val tb = TimeBase()
        out += object : MatrixTransformation {
            override fun getMatrix(presentationTimeUs: Long): Matrix {
                val f = (tb.rel(presentationTimeUs).toFloat() / total).coerceIn(0f, 1f)
                return kfMatrix(c, interpKeyframes(kfs, f))
            }
        }
        if (kfs.any { it.opacity != 1f }) {
            val tb2 = TimeBase()
            out += object : RgbMatrix {
                override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
                    val f = (tb2.rel(presentationTimeUs).toFloat() / total).coerceIn(0f, 1f)
                    return diagMatrix(interpKeyframes(kfs, f).opacity)
                }
            }
        }
    } else if (previewFrac >= 0f) {
        val k = interpKeyframes(kfs, previewFrac)
        out += object : MatrixTransformation {
            override fun getMatrix(presentationTimeUs: Long): Matrix = kfMatrix(c, k)
        }
        if (k.opacity != 1f) {
            out += object : RgbMatrix {
                override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = diagMatrix(k.opacity)
            }
        }
    }
    return out
}

private fun fadeEffect(c: Clip): Effect? {
    if (c.fadeInMs <= 0L && c.fadeOutMs <= 0L) return null
    val total = c.outputMs * 1000L
    val inUs = c.fadeInMs * 1000L
    val outUs = c.fadeOutMs * 1000L
    val tb = TimeBase()
    return object : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray =
            diagMatrix(Motion.fade(inUs, outUs, total, tb.rel(presentationTimeUs)))
    }
}

/** Soft drop shadow behind the text. */
private class ShadowSpan : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) {
        tp.setShadowLayer(8f, 4f, 4f, 0xCC000000.toInt())
    }
}

/** Draws the text twice: a dark stroke first, then the normal fill on top. */
private class OutlineSpan(private val strokeColor: Int, private val strokeWidth: Float) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        Math.ceil((paint.measureText(text, start, end) + strokeWidth).toDouble()).toInt()

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val stroke = Paint(paint)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = strokeWidth
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.color = strokeColor
        canvas.drawText(text, start, end, x + strokeWidth / 2f, y.toFloat(), stroke)
        canvas.drawText(text, start, end, x + strokeWidth / 2f, y.toFloat(), paint)
    }
}

/** Stroke width (in text-bitmap pixels) of the outline style; the preview scales it the same way. */
const val OUTLINE_PX = 8f

private fun styledText(t: TextLayer): SpannableString {
    val s = SpannableString(t.text)
    val n = s.length
    val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    s.setSpan(ForegroundColorSpan(t.color), 0, n, flag)
    s.setSpan(AbsoluteSizeSpan(72), 0, n, flag)
    s.setSpan(TypefaceSpan(FONT_NAMES[t.font.coerceIn(0, FONT_NAMES.lastIndex)]), 0, n, flag)
    if (t.bold) s.setSpan(StyleSpan(Typeface.BOLD), 0, n, flag)
    if (t.background) s.setSpan(BackgroundColorSpan(0xB3000000.toInt()), 0, n, flag)
    if (t.shadow) s.setSpan(ShadowSpan(), 0, n, flag)
    if (t.outline) s.setSpan(OutlineSpan(0xFF000000.toInt(), OUTLINE_PX), 0, n, flag)
    val alignment = when (t.align) {
        0 -> Layout.Alignment.ALIGN_NORMAL
        2 -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_CENTER
    }
    s.setSpan(AlignmentSpan.Standard(alignment), 0, n, flag)
    return s
}

/** Time-aware text overlay for export. Preview uses the same textRender() maths. */
private fun timedOverlay(c: Clip, t: TextLayer): TextureOverlay {
    val text = styledText(t)
    val total = (c.outputMs * 1000L).coerceAtLeast(1L)
    val tb = TimeBase()
    return object : TextOverlay() {
        override fun getText(presentationTimeUs: Long): SpannableString = text

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val r = textRender(t, total, tb.rel(presentationTimeUs))
            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor(t.x, r.y)
                .setRotationDegrees(t.rotation)
                .setScale(r.scale, r.scale)
                .setAlphaScale(if (r.visible) r.alpha else 0f)
                .build()
        }
    }
}

private fun textOverlays(c: Clip): List<TextureOverlay> =
    c.layers.filter { it.text.isNotBlank() }.map { t ->
        if (requiresTimedTextOverlay(t, c.outputMs)) {
            timedOverlay(c, t)
        } else {
            TextOverlay.createStaticTextOverlay(
                styledText(t),
                OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(t.x, t.y)
                    .setRotationDegrees(t.rotation)
                    .setScale(t.scale, t.scale)
                    .build()
            )
        }
    }

private fun addColorEffects(l: MutableList<Effect>, c: Clip, context: Context? = null, strictLut: Boolean = false) {
    if (c.brightness != 0f) l += Brightness(c.brightness)
    if (c.contrast != 0f) l += Contrast(c.contrast)
    if (c.saturation != 0f || c.hue != 0f) {
        l += HslAdjustment.Builder().adjustHue(c.hue * 180f).adjustSaturation(c.saturation * 100f).build()
    }
    if (c.warmth != 0f) {
        l += RgbAdjustment.Builder()
            .setRedScale(1f + 0.3f * c.warmth)
            .setBlueScale(1f - 0.3f * c.warmth)
            .build()
    }
    if (c.redGain != 1f || c.greenGain != 1f || c.blueGain != 1f) {
        l += RgbAdjustment.Builder()
            .setRedScale(c.redGain)
            .setGreenScale(c.greenGain)
            .setBlueScale(c.blueGain)
            .build()
    }
    when (c.filter) {
        Filter.None -> {}
        Filter.Gray -> l += RgbFilter.createGrayscaleFilter()
        Filter.Noir -> { l += RgbFilter.createGrayscaleFilter(); l += Contrast(0.3f) }
        Filter.Invert -> l += RgbFilter.createInvertedFilter()
        Filter.Sepia -> l += sepiaMatrix
        Filter.Vintage -> {
            l += HslAdjustment.Builder().adjustSaturation(-30f).build()
            l += RgbAdjustment.Builder().setRedScale(1.1f).setBlueScale(0.85f).build()
        }
        Filter.Cinema -> {
            l += RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(0.97f).setBlueScale(1.05f).build()
            l += Contrast(0.2f)
            l += HslAdjustment.Builder().adjustSaturation(10f).build()
        }
        Filter.Warm -> l += RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(0.88f).build()
        Filter.Cool -> l += RgbAdjustment.Builder().setRedScale(0.88f).setBlueScale(1.15f).build()
        Filter.Vivid -> {
            l += HslAdjustment.Builder().adjustSaturation(40f).build()
            l += Contrast(0.15f)
        }
        Filter.Fade -> {
            l += Contrast(-0.25f)
            l += Brightness(0.08f)
            l += HslAdjustment.Builder().adjustSaturation(-20f).build()
        }
        Filter.Pop -> {
            l += HslAdjustment.Builder().adjustSaturation(60f).build()
            l += Contrast(0.2f)
        }
    }
    if (c.toneShadows != 0f || c.toneMidtones != 0f || c.toneHighlights != 0f) {
        l += SingleColorLut.createFromCube(ToneCurve(c.toneShadows, c.toneMidtones, c.toneHighlights).toMedia3ArgbCube())
    }
    c.lutUri?.let { uriString ->
        try {
            val ctx = context ?: throw IllegalStateException("LUT context is unavailable")
            val bytes = ctx.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
                ?: throw IllegalStateException("The selected LUT file is no longer accessible")
            require(bytes.size <= 8 * 1024 * 1024) { "LUT file exceeds the 8 MiB limit" }
            val cube = CubeLut.parse(bytes.toString(Charsets.UTF_8)).toMedia3ArgbCube()
            l += SingleColorLut.createFromCube(cube)
        } catch (error: Exception) {
            if (strictLut) throw IllegalStateException("Could not apply the selected 3D LUT: ${error.message}", error)
            // Keep preview responsive if external storage permission has been revoked.
        }
    }
}

/**
 * GL effects used by the live preview: crop, colour adjustments and filter. Geometry, motion, fades and
 * text are drawn by Compose from the shared PreviewMath so they follow the timeline clock exactly.
 */
fun buildPreviewGlEffects(c: Clip, context: Context? = null): List<Effect> {
    val l = mutableListOf<Effect>()
    if (c.cropL > 0f || c.cropR > 0f || c.cropT > 0f || c.cropB > 0f) {
        l += Crop(-1f + 2f * c.cropL, 1f - 2f * c.cropR, -1f + 2f * c.cropB, 1f - 2f * c.cropT)
    }
    addColorEffects(l, c, context, strictLut = false)
    return l
}

/** Visual effects for one clip. Time-based effects (animation, fade) and speed are export-only. */
fun buildVideoEffects(c: Clip, presentation: Presentation?, forExport: Boolean, fps: Int = 0, kfFrac: Float = -1f, overlayLayers: List<OverlayClip> = emptyList(), clipTimelineStartMs: Long = 0L, overlayContext: Context? = null): List<Effect> {
    val l = mutableListOf<Effect>()
    if (forExport && c.speed != 1f && !c.isImage) l += SpeedChangeEffect(c.speed)

    if (c.cropL > 0f || c.cropR > 0f || c.cropT > 0f || c.cropB > 0f) {
        l += Crop(-1f + 2f * c.cropL, 1f - 2f * c.cropR, -1f + 2f * c.cropB, 1f - 2f * c.cropT)
    }
    if (c.rotation != 0 || c.flipH || c.flipV || c.zoom != 1f) {
        val sx = if (c.flipH) -c.zoom else c.zoom
        val sy = if (c.flipV) -c.zoom else c.zoom
        l += ScaleAndRotateTransformation.Builder()
            .setRotationDegrees(c.rotation.toFloat())
            .setScale(sx, sy)
            .build()
    }
    if (forExport) animEffect(c)?.let { l += it }
    l += kfEffects(c, kfFrac, forExport)

    addColorEffects(l, c, overlayContext, strictLut = forExport)
    if (forExport) fadeEffect(c)?.let { l += it }
    if (forExport && fps > 0) l += FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat())
    if (presentation != null) l += presentation

    if (forExport && overlayContext != null && overlayLayers.isNotEmpty()) {
        val imageOverlays = overlayLayers
            .filter { it.isImage && it.isValidOverlayClip() }
            .mapNotNull { layer ->
                val fromMs = (layer.timelineStartMs - clipTimelineStartMs).coerceAtLeast(0L)
                val toMs = (layer.timelineEndMs - clipTimelineStartMs).coerceAtMost(c.outputMs)
                if (toMs <= fromMs) return@mapNotNull null
                val bitmap = decodeOverlayBitmap(overlayContext, layer) ?: return@mapNotNull null
                TimedBitmapOverlay(bitmap, layer, fromMs * 1000L, toMs * 1000L)
            }
        if (imageOverlays.isNotEmpty()) l += OverlayEffect(ImmutableList.copyOf<TextureOverlay>(imageOverlays))
    }
    val textTextureOverlays = textOverlays(c)
    if (textTextureOverlays.isNotEmpty()) l += OverlayEffect(ImmutableList.copyOf<TextureOverlay>(textTextureOverlays))
    return l
}

fun buildAudioProcessors(c: Clip): List<AudioProcessor> {
    val l = mutableListOf<AudioProcessor>()
    if (!c.isImage && c.audioEqGainsDb.any { it != 0f }) l += AudioEqualizerProcessor(c.audioEqGainsDb)
    if (c.speed != 1f && !c.isImage) l += SonicAudioProcessor().apply { setSpeed(c.speed) }
    if (c.volume != 1f && !c.muted) l += volumeProcessor(c.volume)
    if (c.audioFadeInMs > 0L || c.audioFadeOutMs > 0L) {
        l += AudioFadeProcessor(c.audioFadeInMs, c.audioFadeOutMs, c.outputMs)
    }
    return l
}

fun volumeProcessor(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
    putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2).scaleBy(v))
    putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(v))
}

fun buildComposition(
    ctx: Context,
    clips: List<Clip>,
    presentation: Presentation,
    tracks: List<AudioTrack>,
    fps: Int = 0,
    overlayLayers: List<OverlayClip> = emptyList(),
    duckingPoints: Map<Long, List<AudioGainPoint>> = emptyMap(),
    noiseGatePoints: Map<Long, List<AudioGainPoint>> = emptyMap()
): Composition {
    var clipTimelineStartMs = 0L
    val items = clips.map { c ->
        val clipStartMs = clipTimelineStartMs
        clipTimelineStartMs += c.outputMs
        val builder = if (c.isImage) {
            EditedMediaItem.Builder(MediaItem.fromUri(c.uri))
                .setDurationUs(c.trimmedMs * 1000L)
                .setFrameRate(30)
                .setRemoveAudio(true)
        } else {
            val mi = MediaItem.Builder()
                .setUri(c.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.startMs)
                        .setEndPositionMs(c.endMs)
                        .build()
                ).build()
            EditedMediaItem.Builder(mi).setRemoveAudio(c.muted)
        }
        builder.setEffects(Effects(buildAudioProcessors(c), buildVideoEffects(c, presentation, true, fps, overlayLayers = overlayLayers, clipTimelineStartMs = clipStartMs, overlayContext = ctx))).build()
    }
    val sequences = mutableListOf(EditedMediaItemSequence(items))

    val totalMs = clips.sumOf { it.outputMs }
    var loopUsed = false
    val soloActive = tracks.any { it.solo && !it.muted }
    for (t in tracks) {
        if (!isAudioTrackAudible(t.muted, t.solo, soloActive) || t.offsetMs >= totalMs) continue
        val audioItems = mutableListOf<EditedMediaItem>()
        if (t.offsetMs > 0L) {
            audioItems += EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(makeSilence(ctx, t.offsetMs))))
                .setRemoveVideo(true).build()
        }
        val loop = t.loop && t.fadeInMs == 0L && t.fadeOutMs == 0L && t.gainPoints.isEmpty() && t.eqGainsDb.all { it == 0f } && duckingPoints[t.id].isNullOrEmpty() && noiseGatePoints[t.id].isNullOrEmpty() && t.offsetMs == 0L && !loopUsed
        if (loop) loopUsed = true
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(t.trimStartMs)
        val effectiveDuration = audioTrackOutputDurationMs(t.trimStartMs, t.trimEndMs, t.offsetMs, totalMs)
        val effectiveEnd = t.trimStartMs + effectiveDuration
        if (loop && t.trimEndMs != Long.MAX_VALUE) {
            clipping.setEndPositionMs(t.trimEndMs)
        } else if (!loop) {
            clipping.setEndPositionMs(effectiveEnd)
        }
        val mi = MediaItem.Builder().setUri(t.uri).setClippingConfiguration(clipping.build()).build()
        audioItems += EditedMediaItem.Builder(mi)
            .setRemoveVideo(true)
             .setEffects(Effects(buildList {
                if (t.eqGainsDb.any { it != 0f }) add(AudioEqualizerProcessor(t.eqGainsDb))
                if (t.volume != 1f) add(volumeProcessor(t.volume))
                if (t.fadeInMs > 0L || t.fadeOutMs > 0L || t.gainPoints.isNotEmpty() || !duckingPoints[t.id].isNullOrEmpty() || !noiseGatePoints[t.id].isNullOrEmpty()) {
                    add(AudioFadeProcessor(t.fadeInMs, t.fadeOutMs, (effectiveEnd - t.trimStartMs).coerceAtLeast(0L), t.gainPoints, duckingPoints[t.id] ?: emptyList(), noiseGatePoints[t.id] ?: emptyList()))
                }
            }, emptyList()))
            .build()
        sequences += EditedMediaItemSequence(audioItems, loop)
    }
    return Composition.Builder(sequences)
        .experimentalSetForceAudioTrack(true)
        .build()
}
