@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Creates an ExoPlayer audio sink with this track's project EQ in its PCM processing chain. */
class EqualizerRenderersFactory(
    context: Context,
    private val equalizer: AudioEqualizerProcessor,
    private val gain: AudioGainProcessor = AudioGainProcessor()
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(equalizer, gain))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
            .build()
    }
}
