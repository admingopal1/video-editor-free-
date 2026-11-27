package com.app.videoeditor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Applies project preview gain in decoded PCM so the editor can faithfully preview
 * its supported 0–200% clip/track volume and up to 4x combined track automation.
 */
class AudioGainProcessor(initialGain: Float = 1f) : BaseAudioProcessor() {
    @Volatile
    private var requestedGain = normalizeGain(initialGain)

    fun setGain(gain: Float) {
        requestedGain = normalizeGain(gain)
    }

    fun gain(): Float = requestedGain

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val gain = requestedGain
        while (inputBuffer.remaining() >= inputAudioFormat.bytesPerFrame) {
            repeat(channels) {
                if (isFloat) {
                    output.putFloat(applyPcmFloatGain(inputBuffer.float, gain))
                } else {
                    output.putShort(applyPcm16Gain(inputBuffer.short, gain))
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    companion object {
        const val MAX_GAIN = 4f

        fun normalizeGain(gain: Float): Float =
            if (gain.isFinite()) gain.coerceIn(0f, MAX_GAIN) else 0f

        fun applyPcmFloatGain(sample: Float, gain: Float): Float =
            clampPcmFloatSample(sample * normalizeGain(gain))

        fun applyPcm16Gain(sample: Short, gain: Float): Short =
            (sample.toFloat() * normalizeGain(gain)).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
    }
}
