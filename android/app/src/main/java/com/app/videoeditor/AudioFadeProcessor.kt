package com.app.videoeditor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Applies a linear envelope to one trimmed, non-looping audio segment during export. */
class AudioFadeProcessor(
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val durationMs: Long,
    private val gainPoints: List<AudioGainPoint> = emptyList(),
    private val duckingPoints: List<AudioGainPoint> = emptyList(),
    private val noiseGatePoints: List<AudioGainPoint> = emptyList()
) : BaseAudioProcessor() {
    private var frameIndex = 0L
    private var durationFrames = 0L
    private var fadeInFrames = 0L
    private var fadeOutFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (fadeInMs <= 0L && fadeOutMs <= 0L && gainPoints.isEmpty() && duckingPoints.isEmpty() && noiseGatePoints.isEmpty()) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET
        durationFrames = (durationMs.coerceAtLeast(0L) * inputAudioFormat.sampleRate) / 1000L
        fadeInFrames = (fadeInMs.coerceAtLeast(0L) * inputAudioFormat.sampleRate) / 1000L
        fadeOutFrames = (fadeOutMs.coerceAtLeast(0L) * inputAudioFormat.sampleRate) / 1000L
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        val output = replaceOutputBuffer(bytes)
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        while (inputBuffer.remaining() >= inputAudioFormat.bytesPerFrame) {
            val fadeInGain = if (fadeInFrames > 0L) {
                (frameIndex.toFloat() / fadeInFrames).coerceIn(0f, 1f)
            } else 1f
            val fadeOutGain = if (fadeOutFrames > 0L && durationFrames > 0L) {
                ((durationFrames - frameIndex).toFloat() / fadeOutFrames).coerceIn(0f, 1f)
            } else 1f
            val elapsedMs = (frameIndex * 1000L) / inputAudioFormat.sampleRate.coerceAtLeast(1)
            val keyframeGain = audioKeyframeGainAt(gainPoints, elapsedMs)
            val duckGain = audioKeyframeGainAt(duckingPoints, elapsedMs)
            val gateGain = audioKeyframeGainAt(noiseGatePoints, elapsedMs)
            val gain = minOf(fadeInGain, fadeOutGain) * keyframeGain * duckGain * gateGain
            repeat(channels) {
                if (isFloat) {
                    output.putFloat(inputBuffer.float * gain)
                } else {
                    val sample = inputBuffer.short.toInt()
                    output.putShort((sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
            frameIndex++
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        frameIndex = 0L
    }
}
