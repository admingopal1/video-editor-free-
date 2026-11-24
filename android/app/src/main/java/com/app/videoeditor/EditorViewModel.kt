@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import org.json.JSONArray
import org.json.JSONObject
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(FlowPreview::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var clips by mutableStateOf(listOf<Clip>()); private set
    var selectedId by mutableStateOf<Long?>(null)
    var aspect by mutableStateOf(Aspect.Original)
    var quality by mutableStateOf(720)
    var fill by mutableStateOf(false)
    var music by mutableStateOf<Uri?>(null); private set
    var musicVolume by mutableStateOf(1f)
    var export by mutableStateOf<ExportState>(ExportState.Idle)
    val thumbs = mutableStateMapOf<Long, Bitmap>()

    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    private var nextId = 1L
    private var previewKey: Triple<Long, Long, Long>? = null
    private var transformer: Transformer? = null

    val selected: Clip? get() = clips.firstOrNull { it.id == selectedId }
    val totalMs: Long get() = clips.sumOf { it.outputMs }

    // ---------- undo / redo ----------
    private val undoStack = ArrayDeque<List<Clip>>()
    private val redoStack = ArrayDeque<List<Clip>>()
    var undoCount by mutableIntStateOf(0); private set
    var redoCount by mutableIntStateOf(0); private set
    private var lastRecord = 0L

    private fun record(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (force || now - lastRecord > 800) {
            undoStack.addLast(clips)
            if (undoStack.size > 60) undoStack.removeFirst()
            redoStack.clear()
        }
        lastRecord = now
        undoCount = undoStack.size; redoCount = redoStack.size
    }

    private fun fixSelection() {
        if (clips.none { it.id == selectedId }) selectedId = clips.firstOrNull()?.id
        if (clips.isEmpty()) { previewKey = null; player.clearMediaItems() }
        undoCount = undoStack.size; redoCount = redoStack.size
        lastRecord = 0L
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(clips); clips = undoStack.removeLast(); fixSelection()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(clips); clips = redoStack.removeLast(); fixSelection()
    }

    // ---------- autosave / restore ----------
    private val prefs = ctx.getSharedPreferences("project", Context.MODE_PRIVATE)

    init {
        restore()
        viewModelScope.launch {
            snapshotFlow { listOf<Any?>(clips, music, musicVolume, aspect, quality, fill) }
                .debounce(600).collect { save() }
        }
    }

    private fun save() {
        val o = JSONObject()
        o.put("clips", JSONArray().apply { clips.forEach { put(it.toJson()) } })
        o.put("music", music?.toString())
        o.put("musicVolume", musicVolume.toDouble())
        o.put("aspect", aspect.name); o.put("quality", quality); o.put("fill", fill)
        prefs.edit().putString("project", o.toString()).apply()
    }

    private fun restore() {
        val raw = prefs.getString("project", null) ?: return
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching {
                    val o = JSONObject(raw)
                    val arr = o.getJSONArray("clips")
                    val out = mutableListOf<Pair<Clip, Bitmap?>>()
                    for (i in 0 until arr.length()) {
                        val c = clipFromJson(arr.getJSONObject(i))
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(ctx, c.uri)
                            out += c to r.getScaledFrameAtTime(c.startMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)
                        } catch (e: Exception) { /* file no longer available: drop clip */ } finally { r.release() }
                    }
                    Triple(o, out, o.optString("music", ""))
                }.getOrNull()
            } ?: return@launch
            val (o, list, musicStr) = restored
            list.forEach { (c, b) -> if (b != null) thumbs[c.id] = b }
            clips = list.map { it.first }
            nextId = (clips.maxOfOrNull { it.id } ?: 0L) + 1
            selectedId = clips.firstOrNull()?.id
            if (musicStr.isNotBlank() && musicStr != "null") music = Uri.parse(musicStr)
            musicVolume = o.optDouble("musicVolume", 1.0).toFloat()
            aspect = runCatching { Aspect.valueOf(o.getString("aspect")) }.getOrDefault(Aspect.Original)
            quality = o.optInt("quality", 720); fill = o.optBoolean("fill", false)
        }
    }

    fun newProject() {
        record(true)
        clips = emptyList(); selectedId = null; music = null
        previewKey = null; player.clearMediaItems()
    }

    // ---------- importing ----------
    fun addUris(uris: List<Uri>) {
        viewModelScope.launch {
            for (u in uris) {
                val loaded = withContext(Dispatchers.IO) { load(u) }
                if (loaded == null) {
                    Toast.makeText(ctx, "Could not read that file", Toast.LENGTH_SHORT).show()
                    continue
                }
                val (clip, thumb) = loaded
                record(true)
                clips = clips + clip
                if (thumb != null) thumbs[clip.id] = thumb
                selectedId = clip.id
            }
        }
    }

    private fun load(uri: Uri): Pair<Clip, Bitmap?>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val bmp = r.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)
            Clip(nextId++, uri, dur, w, h) to bmp
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    // ---------- clip operations ----------
    fun update(id: Long, f: (Clip) -> Clip) {
        record()
        clips = clips.map { if (it.id == id) f(it) else it }
    }

    fun split() {
        val c = selected ?: return
        val pos = player.currentPosition
        if (pos < 300 || pos > c.trimmedMs - 300) {
            Toast.makeText(ctx, "Move the playhead inside the clip first", Toast.LENGTH_SHORT).show()
            return
        }
        record(true)
        val at = c.startMs + pos
        val second = c.copy(id = nextId++, startMs = at)
        val first = c.copy(endMs = at)
        thumbs[c.id]?.let { thumbs[second.id] = it }
        val i = clips.indexOfFirst { it.id == c.id }
        clips = clips.toMutableList().apply { set(i, first); add(i + 1, second) }
        selectedId = second.id
    }

    fun duplicate() {
        val c = selected ?: return
        record(true)
        val copy = c.copy(id = nextId++)
        thumbs[c.id]?.let { thumbs[copy.id] = it }
        val i = clips.indexOfFirst { it.id == c.id }
        clips = clips.toMutableList().apply { add(i + 1, copy) }
        selectedId = copy.id
    }

    fun delete() {
        val c = selected ?: return
        record(true)
        val i = clips.indexOfFirst { it.id == c.id }
        clips = clips.filter { it.id != c.id }
        selectedId = clips.getOrNull(minOf(i, clips.size - 1))?.id
        if (clips.isEmpty()) { previewKey = null; player.clearMediaItems() }
    }

    fun move(dir: Int) {
        val c = selected ?: return
        val i = clips.indexOfFirst { it.id == c.id }
        val j = i + dir
        if (j !in clips.indices) return
        record(true)
        clips = clips.toMutableList().apply { removeAt(i); add(j, c) }
    }

    fun updateMusic(uri: Uri?) { music = uri }

    // ---------- preview ----------
    fun applyPreview(c: Clip) {
        val key = Triple(c.id, c.startMs, c.endMs)
        if (key != previewKey) {
            previewKey = key
            val item = MediaItem.Builder().setUri(c.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()
                ).build()
            player.setMediaItem(item)
            player.prepare()
        }
        player.setPlaybackParameters(PlaybackParameters(c.speed))
        player.volume = if (c.muted) 0f else c.volume.coerceAtMost(1f)
        player.setVideoEffects(buildVideoEffects(c, null, false))
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    // ---------- export ----------
    private fun outputSize(): Pair<Int, Int> {
        val first = clips.first()
        val ratio = aspect.ratio ?: (first.width.toFloat() / first.height.toFloat())
        val q = quality
        var w: Int
        var h: Int
        if (ratio >= 1f) { h = q; w = (q * ratio).roundToInt() } else { w = q; h = (q / ratio).roundToInt() }
        w -= w % 2; h -= h % 2
        return w to h
    }

    fun startExport() {
        if (clips.isEmpty() || export is ExportState.Running) return
        player.pause()
        val (w, h) = outputSize()
        val layout = if (fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        val comp = buildComposition(clips, Presentation.createForWidthAndHeight(w, h, layout), music, musicVolume)
        val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")

        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    viewModelScope.launch {
                        val uri = withContext(Dispatchers.IO) { saveToGallery(out) }
                        export = if (uri != null) ExportState.Done(uri) else ExportState.Failed("Could not save the file")
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    export = ExportState.Failed(exportException.message ?: "Export failed")
                }
            }).build()
        transformer = t
        export = ExportState.Running(0f)
        t.start(comp, out.absolutePath)

        viewModelScope.launch {
            val holder = ProgressHolder()
            while (export is ExportState.Running) {
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    export = ExportState.Running(holder.progress / 100f)
                }
                delay(300)
            }
        }
    }

    fun cancelExport() {
        transformer?.cancel()
        export = ExportState.Idle
    }

    fun dismissExport() { export = ExportState.Idle }

    private fun saveToGallery(file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VideoEditor_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    override fun onCleared() {
        transformer?.cancel()
        player.release()
    }
}
