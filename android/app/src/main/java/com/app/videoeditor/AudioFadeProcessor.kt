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
    private val gainCursor = AudioGainEnvelopeCursor(gainPoints)
    private val duckCursor = AudioGainEnvelopeCursor(duckingPoints)
    private val gateCursor = AudioGainEnvelopeCursor(noiseGatePoints)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (fadeInMs <= 0L && fadeOutMs <= 0L && gainPoints.isEmpty() && duckingPoints.isEmpty() && noiseGatePoints.isEmpty()) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        val output = replaceOutputBuffer(bytes)
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        while (inputBuffer.remaining() >= inputAudioFormat.bytesPerFrame) {
            val elapsedMs = (frameIndex * 1000L) / inputAudioFormat.sampleRate.coerceAtLeast(1)
            // Use the same overlap-safe envelope as preview so exported fades match playback.
            val fadeGain = audioFadeGainAt(elapsedMs, durationMs, fadeInMs, fadeOutMs)
            val keyframeGain = gainCursor.gainAt(elapsedMs)
            val duckGain = duckCursor.gainAt(elapsedMs)
            val gateGain = gateCursor.gainAt(elapsedMs)
            val gain = fadeGain * keyframeGain * duckGain * gateGain
            repeat(channels) {
                if (isFloat) {
                    output.putFloat(clampPcmFloatSample(inputBuffer.float * gain))
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
        gainCursor.reset()
        duckCursor.reset()
        gateCursor.reset()
    }
}
