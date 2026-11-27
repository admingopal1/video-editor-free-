@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.app.videoeditor

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.util.AtomicFile
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
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
    var audioTracks by mutableStateOf(listOf<AudioTrack>()); private set
    /** Secondary visual layers; persisted separately so the primary clip track remains backward-compatible. */
    var overlayClips by mutableStateOf(listOf<OverlayClip>()); private set
    var recording by mutableStateOf(false); private set
    val waves = mutableStateMapOf<String, FloatArray>()
    private val waveLoading = mutableSetOf<String>()
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordOffsetMs = 0L
    var export by mutableStateOf<ExportState>(ExportState.Idle)
    val thumbs = mutableStateMapOf<Long, Bitmap>()

    private var nextId = 1L

    /** Live preview of the whole project timeline (double-buffered players + photo clock). */
    val timeline = TimelinePlayer(
        ctx, viewModelScope,
        clipsProvider = { clips },
        audioProvider = { audioTracks },
        onClipChanged = { c -> if (selectedId != c.id) selectedId = c.id },
        decodePhoto = { c -> withContext(Dispatchers.IO) { decodeBitmap(c.uri, 1600) } },
        waveformProvider = { waves[it.toString()] }
    )
    private var transformer: Transformer? = null
    private val exportTracker = ExportRequestTracker()
    private var exportFile: File? = null
    private var exportTimeoutReceiverRegistered = false

    private val exportTimeoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ExportService.ACTION_TIMEOUT || export !is ExportState.Running) return
            // Do not report success: Android has ended the foreground-service time window.
            exportTracker.cancel()
            abandonExport(immediate = false)
            export = ExportState.Failed(
                "Android stopped this background export after its time limit. Reopen the app and try a shorter export."
            )
            ExportService.stop(ctx, "Export stopped: Android's background time limit was reached. Open the app and try again.", isError = true)
        }
    }

    val selected: Clip? get() = clips.firstOrNull { it.id == selectedId }
    val totalMs: Long get() = clips.sumOf { it.outputMs }

    // ---------- complete project undo / redo ----------
    private data class EditorSnapshot(
        val clips: List<Clip>,
        val audioTracks: List<AudioTrack>,
        val overlayClips: List<OverlayClip>,
        val aspect: Aspect,
        val quality: Int,
        val fill: Boolean,
        val fps: Int,
        val bitrateLevel: Int
    )

    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    var undoCount by mutableIntStateOf(0); private set
    var redoCount by mutableIntStateOf(0); private set
    private var lastRecord = 0L

    private fun snapshot(): EditorSnapshot = EditorSnapshot(
        clips = clips.toList(),
        audioTracks = audioTracks.toList(),
        overlayClips = overlayClips.toList(),
        aspect = aspect,
        quality = quality,
        fill = fill,
        fps = fps,
        bitrateLevel = bitrateLevel
    )

    private fun restore(snapshot: EditorSnapshot) {
        clips = snapshot.clips
        audioTracks = snapshot.audioTracks
        overlayClips = snapshot.overlayClips
        aspect = snapshot.aspect
        quality = snapshot.quality
        fill = snapshot.fill
        fps = snapshot.fps
        bitrateLevel = snapshot.bitrateLevel
        fixSelection()
    }

    private fun record(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (force || now - lastRecord > 800) {
            undoStack.addLast(snapshot())
            if (undoStack.size > 60) undoStack.removeFirst()
            redoStack.clear()
        }
        lastRecord = now
        undoCount = undoStack.size; redoCount = redoStack.size
    }

    private fun fixSelection() {
        if (clips.none { it.id == selectedId }) selectedId = clips.firstOrNull()?.id
        if (clips.isEmpty()) timeline.reset() else timeline.refresh()
        undoCount = undoStack.size; redoCount = redoStack.size
        lastRecord = 0L
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(snapshot())
        restore(undoStack.removeLast())
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(snapshot())
        restore(redoStack.removeLast())
    }

    fun changeAspect(value: Aspect) {
        if (aspect == value) return
        record(true)
        aspect = value
    }

    fun changeQuality(value: Int) {
        if (quality == value) return
        record(true)
        quality = value
    }

    fun changeFill(value: Boolean) {
        if (fill == value) return
        record(true)
        fill = value
    }

    fun changeFps(value: Int) {
        if (fps == value) return
        record(true)
        fps = value
    }

    fun changeBitrateLevel(value: Int) {
        if (bitrateLevel == value) return
        record(true)
        bitrateLevel = value
    }

    // ---------- init: load last project, autosave ----------
    init {
        ContextCompat.registerReceiver(
            ctx,
            exportTimeoutReceiver,
            IntentFilter(ExportService.ACTION_TIMEOUT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        exportTimeoutReceiverRegistered = true

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Keep recent exports intact; remove only abandoned temporary exports.
                val staleExportBefore = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                ctx.cacheDir.listFiles { f -> f.name.startsWith("export_") }?.forEach { file ->
                    if (shouldDeleteStaleExportFile(file.name, file.lastModified(), staleExportBefore)) file.delete()
                }
                // Silence files are short-lived intermediate assets and can be cleared on startup.
                ctx.cacheDir.listFiles { f -> f.name.startsWith("silence_") }?.forEach { it.delete() }
                migrateOldProject()
            }
            refreshProjects()
            val last = prefs.getString("current", null)
            val target = projects.firstOrNull { it.id == last }?.id ?: projects.firstOrNull()?.id
            if (target == null || !loadInto(target)) { startEmpty("My project"); saveNow() }
            snapshotFlow {
                listOf<Any?>(clips, audioTracks, overlayClips, aspect, quality, fill, fps, bitrateLevel, projectName)
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
        o.put("audio", JSONArray().apply { audioTracks.forEach { put(it.toJson()) } })
        o.put("overlayClips", JSONArray().apply { overlayClips.forEach { put(it.toJson()) } })
        o.put("aspect", aspect.name); o.put("quality", quality); o.put("fill", fill)
        o.put("fps", fps); o.put("bitrate", bitrateLevel)
        return o.toString()
    }

    private val projectSaveMutex = Mutex()

    private suspend fun saveNow() {
        val saved = projectSaveMutex.withLock {
            // Capture the project identity and matching content together before leaving the main thread.
            val id = projectId
            if (id.isEmpty()) return@withLock false
            val json = serialize()
            val thumb = clips.firstOrNull()?.let { thumbs[it.id] }
            withContext(Dispatchers.IO) {
                val projectFile = AtomicFile(File(projectsDir, "$id.json"))
                var stream: java.io.FileOutputStream? = null
                try {
                    stream = projectFile.startWrite()
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    projectFile.finishWrite(stream)
                    stream = null
                } catch (e: Exception) {
                    stream?.let { projectFile.failWrite(it) }
                    throw e
                }

                val jpgFile = AtomicFile(File(projectsDir, "$id.jpg"))
                if (thumb != null) {
                    var imageStream: java.io.FileOutputStream? = null
                    try {
                        imageStream = jpgFile.startWrite()
                        if (!thumb.compress(Bitmap.CompressFormat.JPEG, 80, imageStream)) {
                            throw IllegalStateException("Could not encode project thumbnail")
                        }
                        jpgFile.finishWrite(imageStream)
                        imageStream = null
                    } catch (e: Exception) {
                        imageStream?.let { jpgFile.failWrite(it) }
                        throw e
                    }
                } else {
                    jpgFile.delete()
                }
            }
            true
        }
        if (saved) refreshProjects()
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

    private class Loaded(val o: JSONObject, val clips: List<Clip>, val thumbs: Map<Long, Bitmap>, val dropped: Int, val audio: List<AudioTrack>, val overlays: List<OverlayClip>)

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
        val tracks = mutableListOf<AudioTrack>()
        val aArr = o.optJSONArray("audio")
        if (aArr != null) {
            for (i in 0 until aArr.length()) {
                val t = audioFromJson(aArr.getJSONObject(i))
                if (canOpen(t.uri)) tracks += t else dropped++
            }
        } else {
            // projects saved by older versions had a single music file
            val ms = o.optString("music", "")
            if (ms.isNotBlank() && ms != "null") {
                val u = Uri.parse(ms)
                if (canOpen(u)) tracks += AudioTrack(900_000L, u, displayName(u), o.optDouble("musicVolume", 1.0).toFloat(), loop = true)
            }
        }
        val overlays = mutableListOf<OverlayClip>()
        val overlayArray = o.optJSONArray("overlayClips")
        if (overlayArray != null) {
            for (i in 0 until overlayArray.length()) {
                val overlayJson = overlayArray.optJSONObject(i)
                val overlay = overlayJson?.let { overlayClipFromJson(it) }
                if (overlay == null) {
                    dropped++
                    continue
                }
                val overlayUri = Uri.parse(overlay.uriString)
                if (!canOpen(overlayUri)) {
                    dropped++
                    continue
                }
                if (overlay.isImage && decodeBitmap(overlayUri, 320) == null) {
                    dropped++
                    continue
                }
                overlays += overlay
            }
        }
        Loaded(o, list, th, dropped, tracks, overlays)
    }.getOrNull()

    private fun resetPlayback() {
        timeline.reset()
    }

    /** After the VM changes the selection itself (add, split, duplicate, delete...), move the playhead there. */
    private fun syncPlayheadToSelection() {
        val id = selectedId
        if (id == null) timeline.reset() else timeline.seekToClip(id, false)
    }

    private fun applyLoaded(id: String, l: Loaded) {
        resetPlayback()
        projectId = id
        projectName = l.o.optString("name", "My project")
        thumbs.clear(); thumbs.putAll(l.thumbs)
        undoStack.clear(); redoStack.clear(); undoCount = 0; redoCount = 0
        clips = l.clips
        nextId = maxOf(
            (clips.maxOfOrNull { c -> maxOf(c.id, c.layers.maxOfOrNull { it.id } ?: 0L) } ?: 0L),
            l.audio.maxOfOrNull { it.id } ?: 0L,
            l.overlays.maxOfOrNull { it.id } ?: 0L
        ) + 1
        selectedId = clips.firstOrNull()?.id
        selectedLayerId = null
        audioTracks = l.audio
        overlayClips = l.overlays
        aspect = runCatching { Aspect.valueOf(l.o.getString("aspect")) }.getOrDefault(Aspect.Original)
        quality = l.o.optInt("quality", 720); fill = l.o.optBoolean("fill", false)
        fps = l.o.optInt("fps", 0); bitrateLevel = l.o.optInt("bitrate", 0)
        prefs.edit().putString("current", id).apply()
        if (l.dropped > 0) toast("${l.dropped} clip(s) removed: the file is no longer available")
    }

    private suspend fun loadInto(id: String): Boolean {
        val raw = withContext(Dispatchers.IO) { runCatching { File(projectsDir, "$id.json").readText() }.getOrNull() } ?: return false
        val l = withContext(Dispatchers.IO) { loadFromJson(raw) } ?: return false
        if (l.dropped > 0) {
            // Missing media is removed from the open project. Keep the untouched original once, so
            // clips that were only temporarily unavailable (e.g. storage not mounted) are not lost for good.
            withContext(Dispatchers.IO) {
                val bak = File(projectsDir, "$id.json.bak")
                if (!bak.exists()) runCatching { bak.writeText(raw) }
            }
        }
        applyLoaded(id, l)
        return true
    }

    private fun startEmpty(name: String) {
        resetPlayback()
        projectId = System.currentTimeMillis().toString()
        projectName = name
        thumbs.clear(); undoStack.clear(); redoStack.clear(); undoCount = 0; redoCount = 0
        clips = emptyList(); selectedId = null; selectedLayerId = null; audioTracks = emptyList(); overlayClips = emptyList()
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
            // Wait for any in-flight autosave so it cannot re-create the deleted project.
            projectSaveMutex.withLock {
                withContext(Dispatchers.IO) {
                    File(projectsDir, "$id.json").delete(); File(projectsDir, "$id.jpg").delete()
                    File(projectsDir, "$id.json.bak").delete()
                }
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
                syncPlayheadToSelection()
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

    // ---------- secondary visual layers (additive PIP photo support) ----------
    fun addOverlayImage(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeBitmap(uri, 1200) }
            if (bitmap == null) {
                toast("Could not read this overlay image")
                return@launch
            }
            val remaining = (timeline.totalMs() - timeMs).coerceAtLeast(1_000L)
            record(true)
            overlayClips = overlayClips + OverlayClip(
                id = nextId++,
                uriString = uri.toString(),
                name = displayName(uri),
                durationMs = remaining,
                width = bitmap.width,
                height = bitmap.height,
                startMs = 0L,
                endMs = remaining,
                timelineStartMs = timeMs,
                isImage = true,
                scale = 0.32f,
                layerOrder = overlayClips.size
            )
            overlayBitmaps[uri.toString()] = bitmap
            selectedOverlayId = overlayClips.last().id
        }
    }

    fun addOverlayVideo(uri: Uri) {
        if (clips.isEmpty()) {
            toast("Add a main video or photo first")
            return
        }
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(ctx, uri)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation == 90 || rotation == 270) { val swap = width; width = height; height = swap }
                    Triple(duration, width, height)
                } catch (_: Exception) {
                    Triple(0L, 0, 0)
                } finally {
                    retriever.release()
                }
            }
            val duration = info.first
            if (duration <= 0L || info.second <= 0 || info.third <= 0) {
                toast("Could not read this video")
                return@launch
            }
            val remaining = (timeline.totalMs() - timeMs).coerceAtLeast(1L)
            val sourceDuration = minOf(duration, remaining)
            if (sourceDuration < 500L) {
                toast("Move the playhead earlier to add a longer PIP video")
                return@launch
            }
            record(true)
            val layer = OverlayClip(
                id = nextId++,
                uriString = uri.toString(),
                name = displayName(uri),
                durationMs = duration,
                width = info.second,
                height = info.third,
                startMs = 0L,
                endMs = sourceDuration,
                timelineStartMs = timeMs,
                isImage = false,
                muted = true,
                scale = 0.32f,
                layerOrder = overlayClips.size
            )
            overlayClips = overlayClips + layer
            selectedOverlayId = layer.id
        }
    }

    val overlayBitmaps = mutableStateMapOf<String, Bitmap>()
    var selectedOverlayId by mutableStateOf<Long?>(null)

    fun overlayBitmap(uriString: String): Bitmap? = overlayBitmaps[uriString]
        ?: decodeBitmap(Uri.parse(uriString), 1200)?.also { overlayBitmaps[uriString] = it }

    fun updateOverlay(id: Long, change: (OverlayClip) -> OverlayClip) {
        if (overlayClips.none { it.id == id }) return
        record()
        overlayClips = overlayClips.map { if (it.id == id) change(it) else it }
    }

    fun removeOverlay(id: Long) {
        if (overlayClips.none { it.id == id }) return
        record(true)
        overlayClips = overlayClips.filterNot { it.id == id }
        if (selectedOverlayId == id) selectedOverlayId = overlayClips.lastOrNull()?.id
    }

    /** Move a selected PIP layer one level: -1 sends it backward, +1 brings it forward. */
    fun moveOverlayLayer(id: Long, direction: Int) {
        val reordered = reorderOverlayLayer(overlayClips, id, direction)
        if (reordered == overlayClips) return
        record(true)
        overlayClips = reordered
        selectedOverlayId = id
    }

    // ---------- clip operations ----------
    fun update(id: Long, f: (Clip) -> Clip) {
        record()
        clips = clips.map { if (it.id == id) f(it) else it }
    }

    fun split() {
        val c = selected ?: return
        if (c.isImage) { toast("Photos can't be split. Drag the handles to set the duration."); return }
        val pos = playheadLocalMediaMs()
        if (pos < 300 || pos > c.trimmedMs - 300) { toast("Move the playhead inside the clip first"); return }
        record(true)
        val at = c.startMs + pos
        val second = c.copy(id = nextId++, startMs = at)
        val first = c.copy(endMs = at)
        thumbs[c.id]?.let { thumbs[second.id] = it }
        val i = clips.indexOfFirst { it.id == c.id }
        clips = clips.toMutableList().apply { set(i, first); add(i + 1, second) }
        selectedId = second.id
        syncPlayheadToSelection()
    }

    fun trimToPlayhead(left: Boolean) {
        val c = selected ?: return
        if (c.isImage) { toast("Drag the clip handles to change a photo's duration"); return }
        val pos = playheadLocalMediaMs()
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
        syncPlayheadToSelection()
    }

    fun delete() {
        val c = selected ?: return
        record(true)
        val i = clips.indexOfFirst { it.id == c.id }
        clips = clips.filter { it.id != c.id }
        selectedId = clips.getOrNull(minOf(i, clips.size - 1))?.id
        selectedLayerId = null
        if (clips.isEmpty()) timeline.reset() else syncPlayheadToSelection()
    }

    fun move(dir: Int) {
        val c = selected ?: return
        val i = clips.indexOfFirst { it.id == c.id }
        val j = i + dir
        if (j !in clips.indices) return
        record(true)
        clips = clips.toMutableList().apply { removeAt(i); add(j, c) }
    }

    // ---------- audio tracks ----------
    private fun displayName(uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Audio"

    fun addAudio(uri: Uri, name: String? = null, offsetMs: Long = 0L, isVoiceover: Boolean = false) {
        if (audioTracks.size >= 4) { toast("Up to 4 audio tracks"); return }
        record(true)
        val first = audioTracks.isEmpty() && offsetMs == 0L && !isVoiceover
        audioTracks = audioTracks + AudioTrack(nextId++, uri, name ?: displayName(uri), loop = first, offsetMs = offsetMs, isVoiceover = isVoiceover)
        ensureWaveform(uri)
    }

    fun updateAudio(id: Long, f: (AudioTrack) -> AudioTrack) {
        record()
        audioTracks = audioTracks.map { if (it.id == id) f(it) else it }
    }

    fun removeAudio(id: Long) {
        if (audioTracks.none { it.id == id }) return
        record(true)
        audioTracks = audioTracks.filter { it.id != id }
    }

    fun ensureWaveform(uri: Uri) {
        val key = uri.toString()
        if (waves.containsKey(key) || !waveLoading.add(key)) return
        viewModelScope.launch {
            val w = withContext(Dispatchers.IO) { Waveform.compute(ctx, uri) } ?: FloatArray(0)
            waves[key] = w
            waveLoading.remove(key)
        }
    }

    // ---------- voice-over ----------
    fun startVoice(): Boolean {
        if (recording) return false
        val c = selected
        val offset = if (c != null) clips.takeWhile { it.id != c.id }.sumOf { it.outputMs } else 0L
        val f = File(ctx.filesDir, "voice_${System.currentTimeMillis()}.m4a")
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r; recordFile = f; recordOffsetMs = offset; recording = true
            selectedId?.let { timeline.seekToClip(it, true) }
            true
        } catch (e: Exception) {
            toast("Could not start recording")
            false
        }
    }

    fun stopVoice() {
        val r = recorder ?: return
        try { r.stop() } catch (e: Exception) { }
        r.release()
        recorder = null; recording = false
        timeline.pause()
        val f = recordFile ?: return
        if (f.exists() && f.length() > 1000) addAudio(Uri.fromFile(f), "Voiceover", recordOffsetMs, isVoiceover = true) else toast("Recording was too short")
    }

    // ---------- keyframes, transitions, speed ramps ----------
    fun addKeyframe(frac: Float) {
        val c = selected ?: return
        val t = if (c.isImage) minOf(1f, (c.keyframes.maxOfOrNull { it.t }?.plus(0.25f)) ?: 0f) else frac.coerceIn(0f, 1f)
        val base = interpKeyframes(c.keyframes.sortedBy { it.t }, t).copy(t = t)
        update(c.id) { cl -> cl.copy(keyframes = (cl.keyframes.filter { abs(it.t - t) > 0.01f } + base).sortedBy { it.t }) }
    }

    fun updateKeyframe(index: Int, f: (Keyframe) -> Keyframe) {
        val c = selected ?: return
        update(c.id) { cl ->
            val l = cl.keyframes.toMutableList()
            if (index in l.indices) l[index] = f(l[index])
            cl.copy(keyframes = l.sortedBy { it.t })
        }
    }

    fun deleteKeyframe(index: Int) {
        val c = selected ?: return
        record(true)
        clips = clips.map { cl -> if (cl.id == c.id) cl.copy(keyframes = cl.keyframes.filterIndexed { i, _ -> i != index }) else cl }
    }

    fun clearKeyframes() {
        val c = selected ?: return
        update(c.id) { it.copy(keyframes = emptyList()) }
    }

    fun applyKfPreset(kind: Int) {
        val c = selected ?: return
        val kfs = when (kind) {
            0 -> listOf(Keyframe(0f, scale = 0.3f, opacity = 0f), Keyframe(0.18f, scale = 1.12f), Keyframe(0.3f))
            1 -> listOf(Keyframe(0f, x = -1.3f), Keyframe(0.2f))
            2 -> listOf(Keyframe(0f, scale = 0.2f, rotation = -90f, opacity = 0f), Keyframe(0.25f))
            else -> listOf(Keyframe(0f), Keyframe(1f, scale = 1.3f))
        }
        update(c.id) { it.copy(keyframes = kfs) }
    }

    /** Dip-to-black style transition between this clip and the next one. */
    fun setTransition(ms: Long) {
        val c = selected ?: return
        val i = clips.indexOfFirst { it.id == c.id }
        record(true)
        clips = clips.mapIndexed { idx, cl ->
            when (idx) {
                i -> cl.copy(fadeOutMs = ms)
                i + 1 -> cl.copy(fadeInMs = ms)
                else -> cl
            }
        }
    }

    /** Splits the clip in three parts and gives each its own speed. */
    fun speedRamp(kind: Int) {
        val c = selected ?: return
        if (c.isImage || c.trimmedMs < 1500) { toast("This clip is too short for a speed ramp"); return }
        record(true)
        val speeds = if (kind == 0) floatArrayOf(1f, 0.35f, 1f) else floatArrayOf(1.8f, 0.4f, 1.8f)
        val s1 = c.startMs + c.trimmedMs / 3
        val s2 = c.startMs + 2 * c.trimmedMs / 3
        val a = c.copy(endMs = s1, speed = speeds[0], fadeOutMs = 0L)
        val b = c.copy(id = nextId++, startMs = s1, endMs = s2, speed = speeds[1], layers = emptyList(), keyframes = emptyList(), fadeInMs = 0L, fadeOutMs = 0L)
        val d = c.copy(id = nextId++, startMs = s2, speed = speeds[2], layers = emptyList(), keyframes = emptyList(), fadeInMs = 0L)
        thumbs[c.id]?.let { thumbs[b.id] = it; thumbs[d.id] = it }
        val i = clips.indexOfFirst { it.id == c.id }
        clips = clips.toMutableList().apply { removeAt(i); addAll(i, listOf(a, b, d)) }
        selectedId = b.id
        syncPlayheadToSelection()
    }

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

    // ---------- preview (delegates to TimelinePlayer) ----------
    val timeMs: Long get() = timeline.timeMs
    val isPlaying: Boolean get() = timeline.isPlaying

    /** Time inside the clip under the playhead, in source (media) milliseconds. */
    fun playheadLocalMediaMs(): Long {
        val c = clips.getOrNull(timeline.currentIndex) ?: return 0L
        return (timeline.localOutMs() * c.speed).toLong().coerceIn(0L, c.trimmedMs)
    }

    fun togglePlay() = timeline.toggle()

    fun pausePreview() = timeline.pause()

    /** Scrub the playhead to a project time (pauses playback). */
    fun seekTimeline(ms: Long) = timeline.seekTo(ms, false)

    /** Selects a clip and puts the playhead at its start. */
    fun selectClip(id: Long) {
        selectedId = id
        timeline.seekToClip(id, false)
    }

    /** Called after any edit: the preview re-reads the project (live effects, trims, order). */
    fun onProjectChanged() = timeline.refresh()

    /** Moves the playhead to a spot inside [c], given in source (media) milliseconds. */
    fun scrubTo(c: Clip, localMediaMs: Long) {
        val i = clips.indexOfFirst { it.id == c.id }
        if (i < 0) return
        timeline.seekTo(timeline.startOf(i) + (localMediaMs / c.speed).toLong(), false)
    }

    // ---------- export ----------
    fun applyPreset(kind: Int) {
        record(true)
        when (kind) {
            0 -> { aspect = Aspect.R9x16; quality = 1080; fill = true; fps = 30; bitrateLevel = 2 }   // Reels / Shorts / TikTok
            1 -> { aspect = Aspect.R16x9; quality = 1080; fill = true; fps = 0; bitrateLevel = 2 }    // YouTube
            2 -> { aspect = Aspect.R4x5; quality = 1080; fill = true; fps = 30; bitrateLevel = 1 }    // Instagram post
            3 -> { aspect = Aspect.R9x16; quality = 720; fill = true; fps = 30; bitrateLevel = 1 }    // WhatsApp / small file
        }
    }

    /** Width in pixels of the exported video (used to scale preview text like the export does). */
    fun outputWidthPx(): Int = if (clips.isEmpty()) 1080 else outputSize().first

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
        if (overlayClips.any { !it.isImage }) {
            return "PIP video is available in preview and timeline, but video-layer export is still being implemented. Remove the PIP video layer or export a project with PIP photos only."
        }
        overlayClips.forEach { layer ->
            if (!canOpen(Uri.parse(layer.uriString))) {
                return "The PIP file “${layer.name}” is no longer available. Remove it and add it again."
            }
        }
        clips.forEachIndexed { i, c ->
            if (!canOpen(c.uri)) return "Clip ${i + 1} is no longer available. Delete it or add it again."
        }
        audioTracks.forEach { if (!canOpen(it.uri)) return "The audio file “${it.name}” is no longer available. Remove it and add it again." }
        val (w, h) = outputSize()
        val need = (totalMs / 1000.0 * estimateBitrate(w, h) / 8.0 * 2.2).toLong() + 30_000_000L
        val free = StatFs(ctx.cacheDir.path).availableBytes
        if (free < need) return "Not enough free storage. Free up about ${need / 1_000_000} MB and try again."
        return null
    }

    fun startExport() {
        if (clips.isEmpty() || export is ExportState.Running || exportTracker.isRunning) return
        val requestId = exportTracker.begin()
        timeline.pause()
        export = ExportState.Running(0f)
        viewModelScope.launch {
            val problem = withContext(Dispatchers.IO) { preflight() }
            if (!exportTracker.owns(requestId)) return@launch
            if (problem != null) {
                if (exportTracker.finish(requestId)) export = ExportState.Failed(problem)
                return@launch
            }
            try {
                val voiceWaveforms = withContext(Dispatchers.IO) {
                    audioTracks.filter { it.isVoiceover && !it.muted && (!audioTracks.any { a -> a.solo && !a.muted } || it.solo)}.mapNotNull { track ->
                        Waveform.compute(ctx, track.uri)?.let { track.id to it }
                    }.toMap()
                }
                runExport(requestId, voiceWaveforms)
            } catch (e: Exception) {
                abandonExport(immediate = false)
                if (exportTracker.finish(requestId)) {
                    export = ExportState.Failed(e.message ?: "Could not start export")
                    ExportService.stop(ctx)
                }
            }
        }
    }

    private fun runExport(requestId: Long, voiceWaveforms: Map<Long, FloatArray>) {
        val (w, h) = outputSize()
        val layout = if (fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        val noiseGatePoints = audioTracks.filter { it.isVoiceover && it.noiseGateEnabled && !it.muted && (!audioTracks.any { a -> a.solo && !a.muted } || it.solo)}.mapNotNull { track ->
            val wave = voiceWaveforms[track.id] ?: return@mapNotNull null
            track.id to buildTrimmedVoiceGatePoints(wave, track.trimStartMs, track.trimEndMs, track.noiseGateThreshold)
        }.toMap()
        val duckingPoints = audioTracks.filter { it.autoDuck && !it.isVoiceover && !it.muted && (!audioTracks.any { a -> a.solo && !a.muted } || it.solo)}.associate { track ->
            track.id to buildVoiceDuckingPoints(audioTracks.filter { it.isVoiceover }.map { VoiceoverActivity(it.id, it.offsetMs, it.trimStartMs, it.trimEndMs) }, voiceWaveforms, totalMs, track.offsetMs)
        }
        val comp = buildComposition(ctx, clips, Presentation.createForWidthAndHeight(w, h, layout), audioTracks, fps, overlayClips, duckingPoints, noiseGatePoints)
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
                        var publishedUri: Uri? = null
                        try {
                            // A cancelled or superseded export must never reach the gallery.
                            if (!exportTracker.owns(requestId)) return@launch
                            publishedUri = withContext(Dispatchers.IO) { saveToGallery(out) }
                            if (exportTracker.finish(requestId)) {
                                transformer = null
                                exportFile = null
                                val uri = publishedUri
                                if (uri != null) {
                                    export = ExportState.Done(uri)
                                    ExportService.stop(ctx, "Your video is saved in Movies/VideoEditor")
                                } else {
                                    export = ExportState.Failed("Could not save the file. Check your free storage.")
                                    ExportService.stop(ctx)
                                }
                                publishedUri = null   // ownership moved to the Done state
                            }
                        } catch (e: Exception) {
                            if (exportTracker.finish(requestId)) {
                                transformer = null
                                exportFile = null
                                export = ExportState.Failed(e.message ?: "Could not save the exported video")
                                ExportService.stop(ctx)
                            }
                        } finally {
                            // Cancelled while saving: take the half-published video back out of the gallery.
                            val orphan = publishedUri
                            withContext(NonCancellable + Dispatchers.IO) {
                                if (orphan != null) runCatching { ctx.contentResolver.delete(orphan, null, null) }
                                out.delete()
                            }
                        }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    out.delete()
                    if (exportTracker.finish(requestId)) {
                        transformer = null
                        exportFile = null
                        export = ExportState.Failed(exportException.message ?: "Export failed")
                        ExportService.stop(ctx)
                    }
                }
            }).build()
        transformer = t
        exportFile = out
        ExportService.start(ctx)
        t.start(comp, out.absolutePath)

        viewModelScope.launch {
            val holder = ProgressHolder()
            while (exportTracker.owns(requestId) && export is ExportState.Running) {
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    export = ExportState.Running(holder.progress / 100f)
                    ExportService.progress(ctx, holder.progress)
                }
                delay(300)
            }
        }
    }

    /**
     * Stops the Transformer and removes its temporary file. Deleting is delayed briefly so the encoder
     * has finished releasing the file; leftovers are also cleared by the stale-export cleanup on startup.
     */
    private fun abandonExport(immediate: Boolean) {
        val t = transformer
        val f = exportFile
        transformer = null
        exportFile = null
        runCatching { t?.cancel() }
        if (f == null) return
        if (immediate) {
            runCatching { f.delete() }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                delay(1500)
                runCatching { f.delete() }
            }
        }
    }

    fun cancelExport() {
        // Invalidates preflight, Transformer and gallery-save callbacks of the running export.
        exportTracker.cancel()
        abandonExport(immediate = false)
        export = ExportState.Idle
        ExportService.stop(ctx)
    }

    fun dismissExport() { export = ExportState.Idle }

    private fun saveToGallery(file: File): Uri? {
        // Never publish a missing or empty export as a completed gallery video.
        if (!file.isFile || file.length() <= 0L) return null

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VideoEditor_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val output = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open the gallery output file")
            output.use { stream -> file.inputStream().use { input -> input.copyTo(stream) } }

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) <= 0) {
                throw IllegalStateException("Could not publish the exported video")
            }
            file.delete()
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    override fun onCleared() {
        if (exportTimeoutReceiverRegistered) {
            runCatching { ctx.unregisterReceiver(exportTimeoutReceiver) }
            exportTimeoutReceiverRegistered = false
        }
        runCatching { recorder?.stop() }
        recorder?.release()
        if (exportTracker.isRunning) {
            exportTracker.cancel()
            ExportService.stop(ctx)   // do not leave the foreground notification behind
        }
        abandonExport(immediate = true)
        timeline.release()
    }
}
