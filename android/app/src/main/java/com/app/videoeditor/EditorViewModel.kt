@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

@OptIn(FlowPreview::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs = ctx.getSharedPreferences("project", Context.MODE_PRIVATE)
    private val projectsDir = File(ctx.filesDir, "projects").apply { mkdirs() }

    // ---------- project state ----------
    var projectId by mutableStateOf(""); private set
    var projectName by mutableStateOf("My project")
    var projects by mutableStateOf(listOf<ProjectInfo>()); private set

    var clips by mutableStateOf(listOf<Clip>()); private set
    var selectedId by mutableStateOf<Long?>(null)
    var selectedLayerId by mutableStateOf<Long?>(null)
    var aspect by mutableStateOf(Aspect.Original)
    var quality by mutableStateOf(720)
    var fill by mutableStateOf(false)
    var fps by mutableStateOf(0)            // 0 = keep original, else max fps
    var bitrateLevel by mutableStateOf(0)   // 0 auto, 1 balanced, 2 high, 3 max
    var safeZone by mutableStateOf(false)
    var music by mutableStateOf<Uri?>(null); private set
    var musicVolume by mutableStateOf(1f)
    var export by mutableStateOf<ExportState>(ExportState.Idle)
    var previewBitmap by mutableStateOf<Bitmap?>(null); private set
    var previewAll by mutableStateOf(false); private set
    val thumbs = mutableStateMapOf<Long, Bitmap>()

    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    private var nextId = 1L
    private var previewKey: Triple<Long, Long, Long>? = null
    private var previewImageId = -1L
    private var pendingSeek = -1L
    private var playlist: List<Clip> = emptyList()
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
        leavePlayAll()
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
        leavePlayAll()
        if (clips.none { it.id == selectedId }) selectedId = clips.firstOrNull()?.id
        if (clips.isEmpty()) { previewKey = null; previewImageId = -1L; previewBitmap = null; player.clearMediaItems() }
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

    // ---------- init: load last project, autosave ----------
    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (previewAll) syncPlaylistItem()
            }
        })
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ctx.cacheDir.listFiles { f -> f.name.startsWith("export_") }?.forEach { it.delete() }
                migrateOldProject()
            }
            refreshProjects()
            val last = prefs.getString("current", null)
            val target = projects.firstOrNull { it.id == last }?.id ?: projects.firstOrNull()?.id
            if (target == null || !loadInto(target)) { startEmpty("My project"); saveNow() }
            snapshotFlow {
                listOf<Any?>(clips, music, musicVolume, aspect, quality, fill, fps, bitrateLevel, projectName)
            }.debounce(600).collect { saveNow() }
        }
    }

    private fun migrateOldProject() {
        val old = prefs.getString("project", null) ?: return
        val hasProjects = projectsDir.listFiles { f -> f.extension == "json" }?.isNotEmpty() == true
        if (!hasProjects) {
            runCatching {
                val o = JSONObject(old).put("name", "My project").put("updated", System.currentTimeMillis())
                File(projectsDir, "1.json").writeText(o.toString())
            }
        }
        prefs.edit().remove("project").apply()
    }

    // ---------- projects ----------
    private fun serialize(): String {
        val o = JSONObject()
        o.put("name", projectName); o.put("updated", System.currentTimeMillis())
        o.put("clips", JSONArray().apply { clips.forEach { put(it.toJson()) } })
        o.put("music", music?.toString())
        o.put("musicVolume", musicVolume.toDouble())
        o.put("aspect", aspect.name); o.put("quality", quality); o.put("fill", fill)
        o.put("fps", fps); o.put("bitrate", bitrateLevel)
        return o.toString()
    }

    private suspend fun saveNow() {
        val id = projectId
        if (id.isEmpty()) return
        val json = serialize()
        val thumb = clips.firstOrNull()?.let { thumbs[it.id] }
        withContext(Dispatchers.IO) {
            File(projectsDir, "$id.json").writeText(json)
            val jpg = File(projectsDir, "$id.jpg")
            if (thumb != null) runCatching { jpg.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
            else jpg.delete()
        }
        refreshProjects()
    }

    private suspend fun refreshProjects() {
        val list = withContext(Dispatchers.IO) {
            (projectsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    val arr = o.optJSONArray("clips")
                    var dur = 0L
                    if (arr != null) for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        dur += ((c.getLong("end") - c.getLong("start")) / c.optDouble("speed", 1.0)).toLong()
                    }
                    ProjectInfo(f.nameWithoutExtension, o.optString("name", "Untitled"), o.optLong("updated", f.lastModified()), arr?.length() ?: 0, dur)
                }.getOrNull()
            }.sortedByDescending { it.updated }
        }
        projects = list
    }

    fun projectThumb(id: String): Bitmap? = BitmapFactory.decodeFile(File(projectsDir, "$id.jpg").path)

    private class Loaded(val o: JSONObject, val clips: List<Clip>, val thumbs: Map<Long, Bitmap>, val dropped: Int, val music: Uri?)

    private fun canOpen(uri: Uri): Boolean = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    private fun loadFromJson(raw: String): Loaded? = runCatching {
        val o = JSONObject(raw)
        val arr = o.getJSONArray("clips")
        val list = mutableListOf<Clip>()
        val th = mutableMapOf<Long, Bitmap>()
        var dropped = 0
        for (i in 0 until arr.length()) {
            val c = clipFromJson(arr.getJSONObject(i))
            if (c.isImage) {
                val b = decodeBitmap(c.uri, 320)
                if (b != null) { list += c; th[c.id] = b } else dropped++
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, c.uri)
                    r.getScaledFrameAtTime(c.startMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)?.let { th[c.id] = it }
                    list += c
                } catch (e: Exception) {
                    dropped++
                } finally { r.release() }
            }
        }
        val ms = o.optString("music", "")
        val m = if (ms.isNotBlank() && ms != "null") Uri.parse(ms).takeIf { canOpen(it) } else null
        Loaded(o, list, th, dropped, m)
    }.getOrNull()

    private fun resetPlayback() {
        previewAll = false
        previewKey = null; previewImageId = -1L; previewBitmap = null; pendingSeek = -1L
        player.pause(); player.clearMediaItems()
    }

    private fun applyLoaded(id: String, l: Loaded) {
        resetPlayback()
        projectId = id
        projectName = l.o.optString("name", "My project")
        thumbs.clear(); thumbs.putAll(l.thumbs)
        undoStack.clear(); redoStack.clear(); undoCount = 0; redoCount = 0
        clips = l.clips
        nextId = (clips.maxOfOrNull { c -> maxOf(c.id, c.layers.maxOfOrNull { it.id } ?: 0L) } ?: 0L) + 1
        selectedId = clips.firstOrNull()?.id
        selectedLayerId = null
        music = l.music
        musicVolume = l.o.optDouble("musicVolume", 1.0).toFloat()
        aspect = runCatching { Aspect.valueOf(l.o.getString("aspect")) }.getOrDefault(Aspect.Original)
        quality = l.o.optInt("quality", 720); fill = l.o.optBoolean("fill", false)
        fps = l.o.optInt("fps", 0); bitrateLevel = l.o.optInt("bitrate", 0)
        prefs.edit().putString("current", id).apply()
        if (l.dropped > 0) toast("${l.dropped} clip(s) removed: the file is no longer available")
    }

    private suspend fun loadInto(id: String): Boolean {
        val raw = withContext(Dispatchers.IO) { runCatching { File(projectsDir, "$id.json").readText() }.getOrNull() } ?: return false
        val l = withContext(Dispatchers.IO) { loadFromJson(raw) } ?: return false
        applyLoaded(id, l)
        return true
    }

    private fun startEmpty(name: String) {
        resetPlayback()
        projectId = System.currentTimeMillis().toString()
        projectName = name
        thumbs.clear(); undoStack.clear(); redoStack.clear(); undoCount = 0; redoCount = 0
        clips = emptyList(); selectedId = null; selectedLayerId = null; music = null
        prefs.edit().putString("current", projectId).apply()
    }

    fun newProject() {
        viewModelScope.launch {
            saveNow()
            startEmpty("Project ${projects.size + 1}")
            saveNow()
        }
    }

    fun openProject(id: String) {
        if (id == projectId) return
        viewModelScope.launch {
            saveNow()
            if (!loadInto(id)) toast("Could not open that project")
        }
    }

    fun renameProject(id: String, name: String) {
        val clean = name.trim().ifBlank { return }
        if (id == projectId) { projectName = clean; return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(projectsDir, "$id.json")
                    f.writeText(JSONObject(f.readText()).put("name", clean).toString())
                }
            }
            refreshProjects()
        }
    }

    fun duplicateProject(id: String) {
        viewModelScope.launch {
            if (id == projectId) saveNow()
            withContext(Dispatchers.IO) {
                runCatching {
                    val newId = System.currentTimeMillis().toString()
                    val o = JSONObject(File(projectsDir, "$id.json").readText())
                    o.put("name", o.optString("name", "Project") + " copy").put("updated", System.currentTimeMillis())
                    File(projectsDir, "$newId.json").writeText(o.toString())
                    val jpg = File(projectsDir, "$id.jpg")
                    if (jpg.exists()) jpg.copyTo(File(projectsDir, "$newId.jpg"), overwrite = true)
                }
            }
            refreshProjects()
        }
    }

    fun deleteProject(id: String) {
        val wasCurrent = id == projectId
        if (wasCurrent) projectId = ""   // stop autosave from re-creating it
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(projectsDir, "$id.json").delete(); File(projectsDir, "$id.jpg").delete()
            }
            refreshProjects()
            if (wasCurrent) {
                val next = projects.firstOrNull()?.id
                if (next == null || !loadInto(next)) { startEmpty("My project"); saveNow() }
            }
        }
    }

    // ---------- importing ----------
    private fun decodeBitmap(uri: Uri, maxDim: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxDim)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }

    fun addUris(uris: List<Uri>) {
        viewModelScope.launch {
            for (u in uris) {
                val loaded = withContext(Dispatchers.IO) { load(u) }
                if (loaded == null) {
                    toast("Could not read that file")
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
        val type = ctx.contentResolver.getType(uri) ?: ""
        if (type.startsWith("image/")) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            } catch (e: Exception) { return null }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val thumb = decodeBitmap(uri, 320)
            return Clip(nextId++, uri, 60_000L, bounds.outWidth, bounds.outHeight, 0L, 3_000L, isImage = true) to thumb
        }
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
        if (c.isImage) { toast("Photos can't be split. Drag the handles to set the duration."); return }
        val pos = player.currentPosition
        if (pos < 300 || pos > c.trimmedMs - 300) { toast("Move the playhead inside the clip first"); return }
        record(true)
        val at = c.startMs + pos
        val second = c.copy(id = nextId++, startMs = at)
        val first = c.copy(endMs = at)
        thumbs[c.id]?.let { thumbs[second.id] = it }
        val i = clips.indexOfFirst { it.id == c.id }
        clips = clips.toMutableList().apply { set(i, first); add(i + 1, second) }
        selectedId = second.id
    }

    fun trimToPlayhead(left: Boolean) {
        val c = selected ?: return
        if (c.isImage) { toast("Drag the clip handles to change a photo's duration"); return }
        val pos = player.currentPosition
        if (pos < 300 || pos > c.trimmedMs - 300) { toast("Move the playhead inside the clip first"); return }
        update(c.id) { if (left) it.copy(startMs = it.startMs + pos) else it.copy(endMs = it.startMs + pos) }
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
        selectedLayerId = null
        if (clips.isEmpty()) { previewKey = null; previewImageId = -1L; previewBitmap = null; player.clearMediaItems() }
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

    // ---------- text & sticker layers ----------
    fun addLayer(text: String, scale: Float, y: Float) {
        val c = selected ?: return
        val layer = TextLayer(nextId++, text = text, scale = scale, y = y)
        update(c.id) { it.copy(layers = it.layers + layer) }
        selectedLayerId = layer.id
    }

    fun updateLayer(layerId: Long, f: (TextLayer) -> TextLayer) {
        val c = selected ?: return
        update(c.id) { cl -> cl.copy(layers = cl.layers.map { if (it.id == layerId) f(it) else it }) }
    }

    fun deleteLayer(layerId: Long) {
        val c = selected ?: return
        record(true)
        clips = clips.map { if (it.id == c.id) it.copy(layers = it.layers.filter { l -> l.id != layerId }) else it }
        selectedLayerId = null
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    // ---------- preview ----------
    private fun clipItem(c: Clip): MediaItem = MediaItem.Builder().setUri(c.uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()
        ).build()

    private fun applyClipSettings(c: Clip) {
        player.setPlaybackParameters(PlaybackParameters(c.speed))
        player.volume = if (c.muted) 0f else c.volume.coerceAtMost(1f)
        player.setVideoEffects(buildVideoEffects(c, null, false))
    }

    fun applyPreview(c: Clip) {
        if (previewAll) return
        if (c.isImage) {
            pendingSeek = -1L
            player.pause()
            if (previewKey != null) { player.clearMediaItems(); previewKey = null }
            if (previewImageId != c.id) {
                previewImageId = c.id
                viewModelScope.launch { previewBitmap = withContext(Dispatchers.IO) { decodeBitmap(c.uri, 1600) } }
            }
            return
        }
        previewBitmap = null; previewImageId = -1L
        val key = Triple(c.id, c.startMs, c.endMs)
        if (key != previewKey) {
            previewKey = key
            player.setMediaItem(clipItem(c))
            player.prepare()
        }
        applyClipSettings(c)
        if (pendingSeek >= 0) { player.seekTo(pendingSeek); pendingSeek = -1L }
    }

    /** Move the playhead to a clip + local position (used by timeline scrubbing and taps). */
    fun scrubTo(c: Clip, localMs: Long) {
        player.pause()
        if (previewAll) {
            previewAll = false; player.clearMediaItems(); previewKey = null
            if (c.id == selectedId) { selected?.let { applyPreview(it) } }
        }
        if (c.id != selectedId) {
            selectedId = c.id
            pendingSeek = localMs
        } else if (!c.isImage) {
            player.seekTo(localMs)
        }
    }

    // ---- play the whole project (video clips, in order) ----
    fun playAll() {
        val vids = clips.filter { !it.isImage }
        if (vids.isEmpty()) { toast("Add a video first"); return }
        previewBitmap = null; previewImageId = -1L
        playlist = vids; previewAll = true; previewKey = null
        val start = vids.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        player.setMediaItems(vids.map { clipItem(it) }, start, 0L)
        player.prepare()
        applyClipSettings(vids[start])
        selectedId = vids[start].id
        player.play()
    }

    private fun syncPlaylistItem() {
        val c = playlist.getOrNull(player.currentMediaItemIndex) ?: return
        selectedId = c.id
        applyClipSettings(c)
    }

    fun stopPlayAll() {
        if (!previewAll) return
        previewAll = false
        player.pause(); player.clearMediaItems(); previewKey = null
        selected?.let { applyPreview(it) }
    }

    private fun leavePlayAll() {
        if (!previewAll) return
        previewAll = false
        player.pause(); player.clearMediaItems(); previewKey = null
        viewModelScope.launch { delay(150); selected?.let { applyPreview(it) } }
    }

    fun projectPositionMs(): Long {
        if (!previewAll) return 0L
        val idx = player.currentMediaItemIndex
        val before = playlist.take(idx).sumOf { it.outputMs }
        val c = playlist.getOrNull(idx)
        return before + (player.currentPosition / (c?.speed ?: 1f)).toLong()
    }

    fun playlistTotalMs(): Long = if (previewAll) playlist.sumOf { it.outputMs } else 0L

    fun seekProject(ms: Long) {
        var acc = 0L
        for ((i, c) in playlist.withIndex()) {
            if (ms < acc + c.outputMs || i == playlist.lastIndex) {
                player.seekTo(i, ((ms - acc) * c.speed).toLong().coerceAtLeast(0L))
                return
            }
            acc += c.outputMs
        }
    }

    fun togglePlay() {
        if (!previewAll && selected?.isImage == true) return
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) {
                if (previewAll) player.seekTo(0, 0L) else player.seekTo(0)
            }
            player.play()
        }
    }

    // ---------- export ----------
    fun applyPreset(kind: Int) {
        when (kind) {
            0 -> { aspect = Aspect.R9x16; quality = 1080; fill = true; fps = 30; bitrateLevel = 2 }   // Reels / Shorts / TikTok
            1 -> { aspect = Aspect.R16x9; quality = 1080; fill = true; fps = 0; bitrateLevel = 2 }    // YouTube
            2 -> { aspect = Aspect.R4x5; quality = 1080; fill = true; fps = 30; bitrateLevel = 1 }    // Instagram post
            3 -> { aspect = Aspect.R9x16; quality = 720; fill = true; fps = 30; bitrateLevel = 1 }    // WhatsApp / small file
        }
    }

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

    private fun estimateBitrate(w: Int, h: Int): Int {
        val f = if (fps > 0) fps else 30
        val bpp = when (bitrateLevel) { 1 -> 0.10; 2 -> 0.16; 3 -> 0.24; else -> 0.09 }
        return (w.toLong() * h * f * bpp).toInt().coerceIn(1_500_000, 60_000_000)
    }

    /** Returns a human-readable problem, or null if export can start. */
    private fun preflight(): String? {
        clips.forEachIndexed { i, c ->
            if (!canOpen(c.uri)) return "Clip ${i + 1} is no longer available. Delete it or add it again."
        }
        music?.let { if (!canOpen(it)) return "The music file is no longer available. Remove or change it." }
        val (w, h) = outputSize()
        val need = (totalMs / 1000.0 * estimateBitrate(w, h) / 8.0 * 2.2).toLong() + 30_000_000L
        val free = StatFs(ctx.cacheDir.path).availableBytes
        if (free < need) return "Not enough free storage. Free up about ${need / 1_000_000} MB and try again."
        return null
    }

    fun startExport() {
        if (clips.isEmpty() || export is ExportState.Running) return
        player.pause()
        export = ExportState.Running(0f)
        viewModelScope.launch {
            val problem = withContext(Dispatchers.IO) { preflight() }
            if (problem != null) { export = ExportState.Failed(problem); return@launch }
            runExport()
        }
    }

    private fun runExport() {
        val (w, h) = outputSize()
        val layout = if (fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        val comp = buildComposition(clips, Presentation.createForWidthAndHeight(w, h, layout), music, musicVolume, fps)
        val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")

        val builder = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
        if (bitrateLevel > 0) {
            builder.setEncoderFactory(
                DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(estimateBitrate(w, h)).build())
                    .build()
            )
        }
        val t = builder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    viewModelScope.launch {
                        val uri = withContext(Dispatchers.IO) { saveToGallery(out) }
                        export = if (uri != null) ExportState.Done(uri) else ExportState.Failed("Could not save the file. Check your free storage.")
                        ExportService.stop(ctx, if (uri != null) "Your video is saved in Movies/VideoEditor" else null)
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    out.delete()
                    export = ExportState.Failed(exportException.message ?: "Export failed")
                    ExportService.stop(ctx)
                }
            }).build()
        transformer = t
        ExportService.start(ctx)
        t.start(comp, out.absolutePath)

        viewModelScope.launch {
            val holder = ProgressHolder()
            while (export is ExportState.Running) {
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    export = ExportState.Running(holder.progress / 100f)
                    ExportService.progress(ctx, holder.progress)
                }
                delay(300)
            }
        }
    }

    fun cancelExport() {
        transformer?.cancel()
        export = ExportState.Idle
        ExportService.stop(ctx)
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
