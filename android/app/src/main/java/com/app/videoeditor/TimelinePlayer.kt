@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Live preview of the whole project timeline.
 *
 * Two ExoPlayers are used as a double buffer: while one plays the current video clip, the other has the
 * next clip prepared (paused on its first frame), so clip boundaries swap without a black frame. Photos
 * are shown by Compose and timed by this clock. Everything that animates (transforms, fades, text) is
 * drawn by Compose from timeMs using the same maths as the exporter (PreviewMath.kt).
 */
class TimelinePlayer(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val clipsProvider: () -> List<Clip>,
    private val audioProvider: () -> List<AudioTrack>,
    private val onClipChanged: (Clip) -> Unit,
    private val decodePhoto: suspend (Clip) -> Bitmap?,
    private val waveformProvider: (Uri) -> FloatArray? = { null }
) {
    private val clipEqA = AudioEqualizerProcessor()
    private val clipEqB = AudioEqualizerProcessor()
    private val clipGainA = AudioGainProcessor()
    private val clipGainB = AudioGainProcessor()
    val playerA: ExoPlayer = ExoPlayer.Builder(ctx, EqualizerRenderersFactory(ctx, clipEqA, clipGainA)).build()
    val playerB: ExoPlayer = ExoPlayer.Builder(ctx, EqualizerRenderersFactory(ctx, clipEqB, clipGainB)).build()

    /** Project playhead in output milliseconds. */
    var timeMs by mutableLongStateOf(0L); private set
    var isPlaying by mutableStateOf(false); private set
    var activeIsA by mutableStateOf(true); private set
    var currentIndex by mutableIntStateOf(-1); private set

    /** Clip currently prepared in each player (used by the UI to size and place the video layers). */
    var clipIdA by mutableStateOf<Long?>(null); private set
    var clipIdB by mutableStateOf<Long?>(null); private set

    /** Bitmap of the photo at the playhead (null when the playhead is on a video). */
    var photo by mutableStateOf<Bitmap?>(null); private set

    private data class LoadKey(val id: Long, val uri: Uri, val startMs: Long, val endMs: Long)

    private var keyA: LoadKey? = null
    private var keyB: LoadKey? = null
    private var photoLocalMs = 0L
    private var photoId = -1L
    private val photoCache = LinkedHashMap<Long, Bitmap>()
    private var tickJob: Job? = null
    private var currentId = -1L
    private var lastAudioSync = 0L
    private val audioPlayers = HashMap<Long, ExoPlayer>()
    private val audioEqProcessors = HashMap<Long, AudioEqualizerProcessor>()
    private val audioGainProcessors = HashMap<Long, AudioGainProcessor>()
    private var duckingCacheKey = ""
    private var duckingCache: Map<Long, List<AudioGainPoint>> = emptyMap()
    private var gateCacheKey = ""
    private var gateCache: Map<Long, List<AudioGainPoint>> = emptyMap()

    private val active: ExoPlayer get() = if (activeIsA) playerA else playerB
    private val standby: ExoPlayer get() = if (activeIsA) playerB else playerA
    private fun activeKey() = if (activeIsA) keyA else keyB
    private fun standbyKey() = if (activeIsA) keyB else keyA

    private fun keyOf(c: Clip) = LoadKey(c.id, c.uri, c.startMs, c.endMs)

    private fun durations(clips: List<Clip>) = clips.map { it.outputMs }

    fun totalMs(): Long = TimelineMath.total(durations(clipsProvider()))

    /** Start (output time) of the clip at [index]. */
    fun startOf(index: Int): Long = TimelineMath.starts(durations(clipsProvider())).getOrNull(index) ?: 0L

    /** Local time inside the current clip in output ms. */
    fun localOutMs(): Long {
        val i = currentIndex
        if (i < 0) return 0L
        return (timeMs - startOf(i)).coerceAtLeast(0L)
    }

    // ---------------------------------------------------------------- playback control

    fun play() {
        val clips = clipsProvider()
        if (clips.isEmpty()) return
        if (timeMs >= totalMs() - 20L) seekTo(0L, playAfter = false)   // restart from the beginning
        isPlaying = true
        resumeCurrent()
        startTicking()
        syncAudio(force = true)
    }

    fun pause() {
        isPlaying = false
        tickJob?.cancel()
        tickJob = null
        playerA.pause()
        playerB.pause()
        syncAudio(force = true)
    }

    fun toggle() { if (isPlaying) pause() else play() }

    /** Moves the playhead. Keeps playing if playback was running and [playAfter] is true. */
    fun seekTo(targetMs: Long, playAfter: Boolean = isPlaying) {
        val clips = clipsProvider()
        val loc = TimelineMath.locate(durations(clips), targetMs)
        if (loc == null) { reset(); return }
        timeMs = targetMs.coerceIn(0L, totalMs())
        activateClip(loc.index, loc.localMs, playAfter)
        if (playAfter && tickJob == null) { isPlaying = true; startTicking() }
        if (!playAfter) { isPlaying = false; tickJob?.cancel(); tickJob = null }
        syncAudio(force = true)
    }

    fun seekToClip(id: Long, playAfter: Boolean = false) {
        val i = clipsProvider().indexOfFirst { it.id == id }
        if (i >= 0) seekTo(startOf(i), playAfter)
    }

    /** Re-reads the project after an edit: live-updates effects/speed/volume and reloads trimmed media. */
    fun refresh() {
        val clips = clipsProvider()
        if (clips.isEmpty()) { reset(); return }
        val keepIdx = clips.indexOfFirst { it.id == currentId }
        val idx = if (keepIdx >= 0) keepIdx else currentIndex.coerceIn(0, clips.lastIndex)
        val c = clips[idx]
        val local = if (keepIdx >= 0) (timeMs - TimelineMath.starts(durations(clips))[idx]).coerceIn(0L, c.outputMs) else 0L
        timeMs = TimelineMath.starts(durations(clips))[idx] + local
        val key = keyOf(c)
        if (c.isImage || keepIdx < 0 || activeKey() != key) {
            activateClip(idx, local, isPlaying)
        } else {
            currentIndex = idx
            configure(active, c, local)
            prefetch(idx + 1)
        }
        syncAudio(force = true)
    }

    fun reset() {
        isPlaying = false
        tickJob?.cancel(); tickJob = null
        playerA.pause(); playerB.pause()
        playerA.clearMediaItems(); playerB.clearMediaItems()
        keyA = null; keyB = null; clipIdA = null; clipIdB = null
        currentIndex = -1; currentId = -1L; timeMs = 0L
        photo = null; photoId = -1L
        syncAudio(force = true)
    }

    fun release() {
        tickJob?.cancel()
        playerA.release(); playerB.release()
        audioPlayers.values.forEach { runCatching { it.release() } }
        audioPlayers.clear()
        audioEqProcessors.clear()
        audioGainProcessors.clear()
    }

    // ---------------------------------------------------------------- internals

    private fun resumeCurrent() {
        val clips = clipsProvider()
        val c = clips.getOrNull(currentIndex)
        if (c == null) { seekTo(timeMs, true); return }
        if (!c.isImage) active.play()
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var last = SystemClock.uptimeMillis()
            while (isActive && isPlaying) {
                delay(16)
                val now = SystemClock.uptimeMillis()
                val dt = now - last
                last = now
                tick(dt)
            }
        }
    }

    /** Playback ticks update the clock at ~30 Hz; seeks always write it directly. */
    private fun setClock(t: Long) {
        if (abs(t - timeMs) >= 30L) timeMs = t
    }

    private fun tick(dtMs: Long) {
        val clips = clipsProvider()
        val i = currentIndex
        val c = clips.getOrNull(i)
        if (c == null) { pause(); return }
        val start = startOf(i)
        if (c.isImage) {
            photoLocalMs += dtMs
            if (photoLocalMs >= c.outputMs) advance(i, clips) else setClock(start + photoLocalMs)
        } else {
            val p = active
            val posMedia = p.currentPosition
            val localOut = (posMedia / c.speed).toLong().coerceIn(0L, c.outputMs)
            setClipAudioGain(p, c, localOut)
            val ended = p.playbackState == Player.STATE_ENDED || posMedia >= c.trimmedMs - 15L
            if (ended) advance(i, clips)
            else if (p.playbackState == Player.STATE_READY) setClock(start + localOut)
        }
        if (SystemClock.uptimeMillis() - lastAudioSync > 40L) syncAudio(force = false)
    }

    private fun advance(i: Int, clips: List<Clip>) {
        val next = i + 1
        if (next >= clips.size) {
            timeMs = totalMs()
            pause()
            return
        }
        timeMs = startOf(next)
        activateClip(next, 0L, true)
    }

    /** Makes clip [i] the current one, at [localOutMs] inside it. */
    private fun activateClip(i: Int, localOutMs: Long, play: Boolean) {
        val clips = clipsProvider()
        val c = clips.getOrNull(i) ?: return
        currentIndex = i
        currentId = c.id
        onClipChanged(c)
        if (c.isImage) {
            active.pause(); standby.pause()
            photoLocalMs = localOutMs
            showPhoto(c)
            prefetch(i + 1)
            return
        }
        photo = null; photoId = -1L
        val key = keyOf(c)
        val localMedia = (localOutMs * c.speed).toLong().coerceIn(0L, (c.trimmedMs - 1L).coerceAtLeast(0L))
        if (standbyKey() == key && localMedia == 0L) {
            // The next clip is already prepared on the other player: swap, no black frame.
            val old = active
            activeIsA = !activeIsA
            configure(active, c, localOutMs)
            active.playWhenReady = play
            old.pause()
        } else if (activeKey() == key) {
            configure(active, c, localOutMs)
            active.seekTo(localMedia)
            active.playWhenReady = play
        } else {
            val p = active
            p.setMediaItem(itemOf(c), localMedia)
            p.prepare()
            configure(p, c, localOutMs)
            p.playWhenReady = play
            if (activeIsA) { keyA = key; clipIdA = c.id } else { keyB = key; clipIdB = c.id }
        }
        prefetch(i + 1)
    }

    private fun showPhoto(c: Clip) {
        if (photoId == c.id && photo != null) return
        photoId = c.id
        photoCache[c.id]?.let { photo = it; return }
        scope.launch {
            val bmp = decodePhoto(c)
            if (bmp != null) {
                photoCache[c.id] = bmp
                while (photoCache.size > 4) photoCache.remove(photoCache.keys.first())
                if (photoId == c.id) photo = bmp
            }
        }
    }

    /** Prepares the clip after the current one on the standby player so the boundary is seamless. */
    private fun prefetch(nextIdx: Int) {
        val next = clipsProvider().getOrNull(nextIdx) ?: return
        if (next.isImage) {
            if (!photoCache.containsKey(next.id)) scope.launch {
                decodePhoto(next)?.let { photoCache[next.id] = it }
            }
            return
        }
        val key = keyOf(next)
        val p = standby
        if (standbyKey() != key) {
            p.setMediaItem(itemOf(next), 0L)
            p.prepare()
            if (activeIsA) { keyB = key; clipIdB = next.id } else { keyA = key; clipIdA = next.id }
        }
        configure(p, next)
        p.playWhenReady = false
    }

    private fun itemOf(c: Clip): MediaItem = MediaItem.Builder().setUri(c.uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()
        ).build()

    private fun configure(p: ExoPlayer, c: Clip, localOutMs: Long = 0L) {
        if (p === playerA) {
            clipEqA.setGainsDb(c.audioEqGainsDb)
        } else if (p === playerB) {
            clipEqB.setGainsDb(c.audioEqGainsDb)
        }
        p.setPlaybackParameters(PlaybackParameters(c.speed))
        setClipAudioGain(p, c, localOutMs)
        p.setVideoEffects(buildPreviewGlEffects(c, ctx))
    }

    /** Keep ExoPlayer's post-mixer volume at unity; PCM gain supports the editor's 0–200% range. */
    private fun setClipAudioGain(p: ExoPlayer, c: Clip, localOutMs: Long) {
        val gain = clipAudioVolumeAt(c, localOutMs)
        if (p === playerA) clipGainA.setGain(gain) else if (p === playerB) clipGainB.setGain(gain)
        p.volume = 1f
    }

    private fun clipAudioVolumeAt(c: Clip, localOutMs: Long): Float {
        if (c.muted) return 0f
        val envelope = audioFadeGainAt(localOutMs, c.outputMs, c.audioFadeInMs, c.audioFadeOutMs)
        return (c.volume * envelope).coerceIn(0f, 2f)
    }

    // ---------------------------------------------------------------- audio tracks

    /** Keeps one small player per audio track in step with the timeline. */
    private fun syncAudio(force: Boolean) {
        lastAudioSync = SystemClock.uptimeMillis()
        val tracks = audioProvider()
        val ids = tracks.map { it.id }.toSet()
        audioPlayers.keys.filter { it !in ids }.forEach { id ->
            audioPlayers.remove(id)?.release()
            audioEqProcessors.remove(id)
            audioGainProcessors.remove(id)
        }
        val soloActive = tracks.any { it.solo && !it.muted }
        val audibleTracks = tracks.filter { isAudioTrackAudible(it.muted, it.solo, soloActive) }
        val voiceovers = audibleTracks.filter { it.isVoiceover }
        val voiceWaveforms = voiceovers.mapNotNull { voice -> waveformProvider(voice.uri)?.let { voice.id to it } }.toMap()
        val cacheKey = tracks.joinToString("|") { "${it.id}:${it.offsetMs}:${it.trimStartMs}:${it.trimEndMs}:${it.isVoiceover}:${it.autoDuck}:${it.noiseGateEnabled}:${it.noiseGateThreshold}:${it.muted}:${it.solo}:${waveformProvider(it.uri)?.let { wave -> System.identityHashCode(wave) } ?: 0}" }
        if (cacheKey != duckingCacheKey) {
            duckingCacheKey = cacheKey
            duckingCache = audibleTracks.filter { it.autoDuck && !it.isVoiceover }.associate { track ->
                track.id to buildVoiceDuckingPoints(voiceovers.map { VoiceoverActivity(it.id, it.offsetMs, it.trimStartMs, it.trimEndMs) }, voiceWaveforms, totalMs(), track.offsetMs)
            }
        }
        if (cacheKey != gateCacheKey) {
            gateCacheKey = cacheKey
            gateCache = audibleTracks.filter { it.isVoiceover && it.noiseGateEnabled }.mapNotNull { track ->
                val wave = waveformProvider(track.uri) ?: return@mapNotNull null
                track.id to buildTrimmedVoiceGatePoints(wave, track.trimStartMs, track.trimEndMs, track.noiseGateThreshold)
            }.toMap()
        }
        for (t in tracks) {
            val p = audioPlayers.getOrPut(t.id) {
                val eq = AudioEqualizerProcessor(t.eqGainsDb)
                val gain = AudioGainProcessor()
                audioEqProcessors[t.id] = eq
                audioGainProcessors[t.id] = gain
                ExoPlayer.Builder(ctx, EqualizerRenderersFactory(ctx, eq, gain)).build().also { ap ->
                    ap.setMediaItem(MediaItem.fromUri(t.uri))
                    ap.prepare()
                }
            }
            audioEqProcessors[t.id]?.setGainsDb(t.eqGainsDb)
            val trackDurationMs = audioTrackOutputDurationMs(t.trimStartMs, t.trimEndMs, t.offsetMs, totalMs()).coerceAtLeast(1L)
            val elapsedMs = (timeMs - t.offsetMs).coerceAtLeast(0L)
            val fadeGain = audioFadeGainAt(elapsedMs, trackDurationMs, t.fadeInMs, t.fadeOutMs)
            val keyframeGain = audioKeyframeGainAt(t.gainPoints, elapsedMs)
            val duckGain = audioKeyframeGainAt(duckingCache[t.id] ?: emptyList(), elapsedMs)
            val gateGain = audioKeyframeGainAt(gateCache[t.id] ?: emptyList(), elapsedMs)
            audioGainProcessors[t.id]?.setGain(combinedPreviewAudioGain(
                volume = t.volume,
                fade = fadeGain,
                automation = keyframeGain,
                ducking = duckGain,
                noiseGate = gateGain,
                audible = isAudioTrackAudible(t.muted, t.solo, soloActive)
            ))
            p.volume = 1f
            p.repeatMode = if (t.loop && t.fadeInMs == 0L && t.fadeOutMs == 0L && t.gainPoints.isEmpty() && !t.autoDuck && !t.noiseGateEnabled && (duckingCache[t.id].isNullOrEmpty()) && (gateCache[t.id].isNullOrEmpty()) && t.offsetMs == 0L && t.trimEndMs == Long.MAX_VALUE) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            val wanted = timeMs - t.offsetMs + t.trimStartMs
            val reachedTrimEnd = t.trimEndMs != Long.MAX_VALUE && wanted >= t.trimEndMs
            if (!isPlaying || timeMs < t.offsetMs || reachedTrimEnd) {
                p.pause()
                if (force) p.seekTo(
                    when {
                        timeMs < t.offsetMs -> t.trimStartMs
                        reachedTrimEnd -> t.trimEndMs
                        else -> wanted.coerceAtLeast(0L)
                    }
                )
            } else {
                if (shouldResyncAudioPlayer(p.currentPosition, wanted, force)) p.seekTo(wanted.coerceAtLeast(0L))
                p.play()
            }
        }
    }
}
