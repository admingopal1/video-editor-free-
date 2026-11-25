@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import android.content.Context
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
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
            val r = tb.rel(presentationTimeUs)
            val f = (r.toFloat() / total).coerceIn(0f, 1f)
            val m = Matrix()
            when (c.anim) {
                Anim.ZoomIn -> { val s = 1f + 0.25f * f; m.postScale(s, s) }
                Anim.ZoomOut -> { val s = 1.25f - 0.25f * f; m.postScale(s, s) }
                Anim.PanLeft -> { m.postScale(1.2f, 1.2f); m.postTranslate(0.18f * (1f - 2f * f), 0f) }
                Anim.PanRight -> { m.postScale(1.2f, 1.2f); m.postTranslate(-0.18f * (1f - 2f * f), 0f) }
                Anim.Pulse -> {
                    val sec = r / 1_000_000f
                    val s = 1f + 0.05f * sin(2.0 * PI * sec).toFloat()
                    m.postScale(s, s)
                }
                Anim.None -> {}
            }
            return m
        }
    }
}

private fun diagMatrix(o: Float): FloatArray {
    val f = o.coerceIn(0f, 1f)
    return floatArrayOf(
        f, 0f, 0f, 0f,
        0f, f, 0f, 0f,
        0f, 0f, f, 0f,
        0f, 0f, 0f, 1f
    )
}

private fun frameAspect(c: Clip): Float {
    var w = c.width * (1f - c.cropL - c.cropR)
    var h = c.height * (1f - c.cropT - c.cropB)
    if (c.rotation == 90 || c.rotation == 270) { val t = w; w = h; h = t }
    return (w / h.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
}

private fun kfMatrix(c: Clip, k: Keyframe): Matrix {
    val a = frameAspect(c)
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
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            val t = tb.rel(presentationTimeUs)
            var f = 1f
            if (inUs > 0 && t < inUs) f = t.toFloat() / inUs
            if (outUs > 0 && t > total - outUs) f = minOf(f, (total - t).toFloat() / outUs)
            f = f.coerceIn(0f, 1f)
            return floatArrayOf(
                f, 0f, 0f, 0f,
                0f, f, 0f, 0f,
                0f, 0f, f, 0f,
                0f, 0f, 0f, 1f
            )
        }
    }
}

private fun styledText(t: TextLayer): SpannableString {
    val s = SpannableString(t.text)
    val n = s.length
    val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    s.setSpan(ForegroundColorSpan(t.color), 0, n, flag)
    s.setSpan(AbsoluteSizeSpan(72), 0, n, flag)
    s.setSpan(TypefaceSpan(FONT_NAMES[t.font.coerceIn(0, FONT_NAMES.lastIndex)]), 0, n, flag)
    if (t.bold) s.setSpan(StyleSpan(Typeface.BOLD), 0, n, flag)
    if (t.background) s.setSpan(BackgroundColorSpan(0xB3000000.toInt()), 0, n, flag)
    return s
}

/** Time-aware text overlay shared by preview and export. */
private fun timedOverlay(c: Clip, t: TextLayer): TextureOverlay {
    val text = styledText(t)
    val total = (c.outputMs * 1000L).coerceAtLeast(1L)
    val startFrac = t.startFrac.coerceIn(0f, 1f)
    val endFrac = t.endFrac.coerceIn(startFrac, 1f)
    val startUs = (startFrac * total).toLong()
    val endUs = (endFrac * total).toLong()
    val animUs = 400_000L
    val tb = TimeBase()
    return object : TextOverlay() {
        override fun getText(presentationTimeUs: Long): SpannableString = text

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val r = tb.rel(presentationTimeUs)
            val visible = r >= startUs && r <= endUs
            var alpha = if (visible) 1f else 0f
            var scale = t.scale
            var y = t.y
            if (visible) {
                val a = ((r - startUs).toFloat() / animUs).coerceIn(0f, 1f)
                when (t.anim) {
                    TextAnim.Fade -> alpha = a
                    TextAnim.Pop -> scale = t.scale * (0.5f + 0.5f * a)
                    TextAnim.SlideUp -> y = t.y - 0.25f * (1f - a)
                    TextAnim.None -> {}
                }
            }
            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor(t.x, y)
                .setScale(scale, scale)
                .setAlphaScale(alpha)
                .build()
        }
    }
}

private fun textOverlays(c: Clip): List<TextureOverlay> =
    c.layers.filter { it.text.isNotBlank() }.map { t ->
        if (t.startFrac > 0f || t.endFrac < 1f || t.anim != TextAnim.None) {
            timedOverlay(c, t)
        } else {
            TextOverlay.createStaticTextOverlay(
                styledText(t),
                OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(t.x, t.y)
                    .setScale(t.scale, t.scale)
                    .build()
            )
        }
    }

/** Visual effects for one clip. Time-based effects (animation, fade) and speed are export-only. */
fun buildVideoEffects(c: Clip, presentation: Presentation?, forExport: Boolean, fps: Int = 0, kfFrac: Float = -1f): List<Effect> {
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
    if (forExport) fadeEffect(c)?.let { l += it }
    if (forExport && fps > 0) l += FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat())
    if (presentation != null) l += presentation

    val overlays = textOverlays(c)
    if (overlays.isNotEmpty()) l += OverlayEffect(ImmutableList.copyOf<TextureOverlay>(overlays))
    return l
}

fun buildAudioProcessors(c: Clip): List<AudioProcessor> {
    val l = mutableListOf<AudioProcessor>()
    if (c.speed != 1f && !c.isImage) l += SonicAudioProcessor().apply { setSpeed(c.speed) }
    if (c.volume != 1f && !c.muted) l += volumeProcessor(c.volume)
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
    fps: Int = 0
): Composition {
    val items = clips.map { c ->
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
        builder.setEffects(Effects(buildAudioProcessors(c), buildVideoEffects(c, presentation, true, fps))).build()
    }
    val sequences = mutableListOf(EditedMediaItemSequence(items))

    val totalMs = clips.sumOf { it.outputMs }
    var loopUsed = false
    for (t in tracks) {
        if (t.offsetMs >= totalMs) continue
        val audioItems = mutableListOf<EditedMediaItem>()
        if (t.offsetMs > 0L) {
            audioItems += EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(makeSilence(ctx, t.offsetMs))))
                .setRemoveVideo(true).build()
        }
        val loop = t.loop && t.offsetMs == 0L && !loopUsed
        if (loop) loopUsed = true
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(t.trimStartMs)
        if (!loop) clipping.setEndPositionMs(t.trimStartMs + (totalMs - t.offsetMs))
        val mi = MediaItem.Builder().setUri(t.uri).setClippingConfiguration(clipping.build()).build()
        audioItems += EditedMediaItem.Builder(mi)
            .setRemoveVideo(true)
            .setEffects(Effects(if (t.volume != 1f) listOf(volumeProcessor(t.volume)) else emptyList(), emptyList()))
            .build()
        sequences += EditedMediaItemSequence(audioItems, loop)
    }
    return Composition.Builder(sequences)
        .experimentalSetForceAudioTrack(true)
        .build()
}
