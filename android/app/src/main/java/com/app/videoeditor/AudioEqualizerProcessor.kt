@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Five-band peaking EQ for decoded PCM audio.
 *
 * Gains are ordered 60 Hz, 230 Hz, 910 Hz, 3.6 kHz and 14 kHz.
 * This processor intentionally handles PCM only; encoded passthrough/offload is left untouched
 * by Media3 and therefore cannot be presented as EQ-processed audio.
 */
class AudioEqualizerProcessor(
    initialGainsDb: List<Float> = List(BAND_COUNT) { 0f }
) : BaseAudioProcessor() {
    @Volatile
    private var requestedGainsDb: List<Float> = normalizeGains(initialGainsDb)

    private var filtersByChannel: Array<Array<PeakingBiquad>> = emptyArray()

    /** Safe to call while playback is running; coefficients are refreshed on the audio thread. */
    fun setGainsDb(gainsDb: List<Float>) {
        requestedGainsDb = normalizeGains(gainsDb)
        rebuildFilters()
    }

    fun gainsDb(): List<Float> = requestedGainsDb

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET
        if (requestedGainsDb.all { it == 0f }) return AudioProcessor.AudioFormat.NOT_SET
        filtersByChannel = Array(inputAudioFormat.channelCount.coerceAtLeast(1)) {
            createFilters(inputAudioFormat.sampleRate, requestedGainsDb)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val channels = format.channelCount.coerceAtLeast(1)
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val filters = filtersByChannel
        while (inputBuffer.remaining() >= format.bytesPerFrame) {
            repeat(channels) { channel ->
                val chain = filters.getOrNull(channel)
                if (isFloat) {
                    var sample = inputBuffer.float
                    if (chain != null) chain.forEach { sample = it.process(sample) }
                    output.putFloat(clampPcmFloatSample(sample))
                } else {
                    var sample = inputBuffer.short.toFloat() / Short.MAX_VALUE.toFloat()
                    if (chain != null) chain.forEach { sample = it.process(sample) }
                    output.putShort((clampPcmFloatSample(sample) * Short.MAX_VALUE)
                        .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        filtersByChannel.forEach { channel -> channel.forEach { it.reset() } }
    }

    private fun rebuildFilters() {
        val format = inputAudioFormat
        if (format == AudioProcessor.AudioFormat.NOT_SET) return
        filtersByChannel = if (requestedGainsDb.all { it == 0f }) {
            emptyArray()
        } else {
            Array(format.channelCount.coerceAtLeast(1)) {
                createFilters(format.sampleRate, requestedGainsDb)
            }
        }
    }

    private fun createFilters(sampleRate: Int, gains: List<Float>): Array<PeakingBiquad> =
        BAND_FREQUENCIES_HZ.indices.map { index ->
            PeakingBiquad.create(sampleRate, BAND_FREQUENCIES_HZ[index], BAND_Q, gains[index])
        }.toTypedArray()

    companion object {
        const val BAND_COUNT = 5
        val BAND_FREQUENCIES_HZ = listOf(60f, 230f, 910f, 3600f, 14000f)
        const val BAND_Q = 1.0f

        fun normalizeGains(gains: List<Float>): List<Float> =
            List(BAND_COUNT) { index ->
                (gains.getOrNull(index) ?: 0f).let { if (it.isFinite()) it.coerceIn(-12f, 12f) else 0f }
            }
    }
}

/** RBJ peaking-EQ biquad in transposed direct-form II. */
internal class PeakingBiquad private constructor(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float
) {
    private var z1 = 0f
    private var z2 = 0f

    fun process(input: Float): Float {
        val output = b0 * input + z1
        z1 = b1 * input - a1 * output + z2
        z2 = b2 * input - a2 * output
        return if (output.isFinite()) output else 0f
    }

    fun reset() {
        z1 = 0f
        z2 = 0f
    }

    companion object {
        fun create(sampleRate: Int, centerHz: Float, q: Float, gainDb: Float): PeakingBiquad {
            val nyquistSafeHz = centerHz.coerceIn(1f, (sampleRate * 0.45f).coerceAtLeast(1f))
            val a = 10.0.pow((gainDb / 40.0).toDouble()).toFloat()
            val w0 = (2.0 * PI * nyquistSafeHz / sampleRate.coerceAtLeast(1)).toFloat()
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2f * q.coerceAtLeast(0.1f))
            val a0 = 1f + alpha / a
            return PeakingBiquad(
                (1f + alpha * a) / a0,
                (-2f * cosW0) / a0,
                (1f - alpha * a) / a0,
                (-2f * cosW0) / a0,
                (1f - alpha / a) / a0
            )
        }
    }
}
