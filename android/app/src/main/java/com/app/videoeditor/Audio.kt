package com.app.videoeditor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Decodes an audio file and returns loudness peaks (0..1), one value per 100 ms. */
object Waveform {
    fun compute(ctx: Context, uri: Uri, bucketMs: Int = 100, maxMinutes: Int = 15): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            val peaks = ArrayList<Float>()
            var bucketMax = 0f
            var frames = 0L
            var bucketFrames = sampleRate.toLong() * bucketMs / 1000L
            var inputDone = false
            var done = false
            val limit = maxMinutes * 60_000 / bucketMs

            while (!done && peaks.size < limit) {
                if (!inputDone) {
                    val ib = c.dequeueInputBuffer(5_000)
                    if (ib >= 0) {
                        val buf: ByteBuffer = c.getInputBuffer(ib) ?: return null
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val ob = c.dequeueOutputBuffer(info, 5_000)
                if (ob >= 0) {
                    val out = c.getOutputBuffer(ob)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val total = sb.remaining()
                        var i = 0
                        while (i < total) {
                            var m = 0
                            for (ch in 0 until channels) {
                                if (i + ch < total) m = maxOf(m, abs(sb.get(i + ch).toInt()))
                            }
                            bucketMax = maxOf(bucketMax, m / 32768f)
                            frames++
                            i += channels
                            if (frames >= bucketFrames) { peaks.add(bucketMax); bucketMax = 0f; frames = 0L }
                        }
                    }
                    c.releaseOutputBuffer(ob, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                } else if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    bucketFrames = sampleRate.toLong() * bucketMs / 1000L
                }
            }
            if (frames > 0) peaks.add(bucketMax)
            if (peaks.isEmpty()) return null
            val mx = (peaks.maxOrNull() ?: 1f).coerceAtLeast(0.001f)
            return FloatArray(peaks.size) { sqrt(peaks[it] / mx) }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}

/** Silent 44.1 kHz stereo WAV, used to delay an audio track so it starts at a chosen time. */
fun makeSilence(ctx: Context, ms: Long): File {
    val f = File(ctx.cacheDir, "silence_$ms.wav")
    if (f.exists() && f.length() > 44) return f
    val sampleRate = 44100
    val channels = 2
    val dataLen = (sampleRate.toLong() * ms / 1000L * channels * 2L).coerceAtMost(Int.MAX_VALUE.toLong() - 64).toInt()
    BufferedOutputStream(f.outputStream()).use { out ->
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + dataLen); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
        h.putInt(sampleRate); h.putInt(sampleRate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataLen)
        out.write(h.array())
        val zeros = ByteArray(65536)
        var left = dataLen
        while (left > 0) {
            val n = minOf(left, zeros.size)
            out.write(zeros, 0, n)
            left -= n
        }
    }
    return f
}
