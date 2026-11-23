@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList

private val sepiaMatrix = object : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = floatArrayOf(
        0.393f, 0.349f, 0.272f, 0f,
        0.769f, 0.686f, 0.534f, 0f,
        0.189f, 0.168f, 0.131f, 0f,
        0f, 0f, 0f, 1f
    )
}

/** Visual effects for one clip. [presentation] is only used for export (final canvas size). */
fun buildVideoEffects(c: Clip, presentation: Presentation?, forExport: Boolean): List<Effect> {
    val l = mutableListOf<Effect>()
    if (forExport && c.speed != 1f) l += SpeedChangeEffect(c.speed)

    if (c.rotation != 0 || c.flipH || c.flipV || c.zoom != 1f) {
        val sx = if (c.flipH) -c.zoom else c.zoom
        val sy = if (c.flipV) -c.zoom else c.zoom
        l += ScaleAndRotateTransformation.Builder()
            .setRotationDegrees(c.rotation.toFloat())
            .setScale(sx, sy)
            .build()
    }
    if (c.brightness != 0f) l += Brightness(c.brightness)
    if (c.contrast != 0f) l += Contrast(c.contrast)
    if (c.saturation != 0f) l += HslAdjustment.Builder().adjustSaturation(c.saturation * 100f).build()
    if (c.warmth != 0f) {
        l += RgbAdjustment.Builder()
            .setRedScale(1f + 0.3f * c.warmth)
            .setBlueScale(1f - 0.3f * c.warmth)
            .build()
    }
    when (c.filter) {
        Filter.None -> {}
        Filter.Gray -> l += RgbFilter.createGrayscaleFilter()
        Filter.Invert -> l += RgbFilter.createInvertedFilter()
        Filter.Sepia -> l += sepiaMatrix
        Filter.Warm -> l += RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(0.88f).build()
        Filter.Cool -> l += RgbAdjustment.Builder().setRedScale(0.88f).setBlueScale(1.15f).build()
        Filter.Vivid -> {
            l += HslAdjustment.Builder().adjustSaturation(40f).build()
            l += Contrast(0.15f)
        }
    }
    if (presentation != null) l += presentation

    if (c.text.isNotBlank()) {
        val s = SpannableString(c.text)
        s.setSpan(ForegroundColorSpan(c.textColor), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(AbsoluteSizeSpan(72), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, c.textY)
            .setScale(c.textScale, c.textScale)
            .build()
        l += OverlayEffect(ImmutableList.of(TextOverlay.createStaticTextOverlay(s, settings)))
    }
    return l
}

fun buildAudioProcessors(c: Clip): List<AudioProcessor> {
    val l = mutableListOf<AudioProcessor>()
    if (c.speed != 1f) l += SonicAudioProcessor().apply { setSpeed(c.speed) }
    if (c.volume != 1f && !c.muted) l += volumeProcessor(c.volume)
    return l
}

fun volumeProcessor(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
    putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2).scaleBy(v))
    putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(v))
}

fun buildComposition(
    clips: List<Clip>,
    presentation: Presentation,
    music: Uri?,
    musicVolume: Float
): Composition {
    val items = clips.map { c ->
        val mi = MediaItem.Builder()
            .setUri(c.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.startMs)
                    .setEndPositionMs(c.endMs)
                    .build()
            ).build()
        EditedMediaItem.Builder(mi)
            .setRemoveAudio(c.muted)
            .setEffects(Effects(buildAudioProcessors(c), buildVideoEffects(c, presentation, true)))
            .build()
    }
    val sequences = mutableListOf(EditedMediaItemSequence(items))
    if (music != null) {
        val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(music))
            .setRemoveVideo(true)
            .setEffects(
                Effects(
                    if (musicVolume != 1f) listOf(volumeProcessor(musicVolume)) else emptyList(),
                    emptyList()
                )
            ).build()
        sequences += EditedMediaItemSequence(listOf(audioItem), true)
    }
    return Composition.Builder(sequences)
        .experimentalSetForceAudioTrack(true)
        .build()
}
