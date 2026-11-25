@file:OptIn(androidx.media3.common.util.UnstableApi::class, ExperimentalMaterial3Api::class)

package com.app.videoeditor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

enum class Tool(val title: String) {
    Speed("Speed"), Volume("Volume"), Animate("Animation"), Fade("Fade"), Filters("Filters"),
    Adjust("Adjust"), Crop("Crop"), Transform("Transform"), Text("Text"), Stickers("Stickers"),
    Canvas("Canvas & export"), Audio("Audio"), Keyframes("Keyframes"), Transition("Transition")
}

private val textColors = listOf(
    0xFFFFFFFF.toInt(), 0xFFFFE600.toInt(), 0xFFFF3B30.toInt(), 0xFF00E5FF.toInt(),
    0xFF34C759.toInt(), 0xFFFF2D95.toInt(), 0xFF000000.toInt()
)

private val stickerList = listOf(
    "😀", "😂", "😍", "🥳", "😎", "🤩", "😱", "🙌", "🔥", "✨", "💯", "❤️", "👍", "👏", "🎉", "⭐",
    "🌈", "💥", "🎵", "🎬", "📸", "🚀", "💡", "✅", "⚡", "🌟", "👀", "💪", "🍿", "🎁", "👑", "🌹",
    "☀️", "🌙", "🦋", "🐶", "🐱", "🏆", "➡️", "📍"
)

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun f2(v: Float) = String.format(Locale.US, "%.2f", v)

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var tool by remember { mutableStateOf<Tool?>(null) }
    var dpPerSec by remember { mutableFloatStateOf(40f) }
    var showProjects by remember { mutableStateOf(false) }
    var kfSel by remember { mutableIntStateOf(0) }
    val clip = vm.selected
    var pos by remember { mutableLongStateOf(0L) }
    var projPos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(Unit) {
        while (true) {
            pos = vm.player.currentPosition
            projPos = vm.projectPositionMs()
            playing = vm.player.isPlaying
            delay(100)
        }
    }
    LaunchedEffect(vm.export, playing) {
        view.keepScreenOn = vm.export is ExportState.Running || playing
    }
    val kfKey = if (clip != null && clip.keyframes.isNotEmpty() && !playing) pos / 80 else -1L
    LaunchedEffect(clip, kfKey) {
        if (clip != null) {
            delay(120)
            val frac = if (clip.keyframes.isNotEmpty() && !playing && !clip.isImage)
                (pos.toFloat() / clip.trimmedMs.coerceAtLeast(1)).coerceIn(0f, 1f) else -1f
            vm.applyPreview(clip, frac)
        }
    }
    LaunchedEffect(vm.clips.map { it.uri }, vm.audioTracks.map { it.uri }) {
        vm.clips.filter { !it.isImage }.forEach { vm.ensureWaveform(it.uri) }
        vm.audioTracks.forEach { vm.ensureWaveform(it.uri) }
    }
    LaunchedEffect(clip == null) {
        if (clip == null && tool != null && tool != Tool.Audio && tool != Tool.Canvas) tool = null
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        uris.forEach { u -> runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        vm.addUris(uris)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.addAudio(u)
        }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startExport() }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice()
    }
    fun recordVoice() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVoice()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun requestExport() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startExport()
    }
    fun addMedia() = pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // ---------- top bar ----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).clickable { showProjects = true }) {
                Text("${vm.projectName} ▾", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    if (vm.clips.isEmpty()) "Tap to open or create projects"
                    else "${vm.clips.size} clips · ${fmt(vm.totalMs)} · ${vm.aspect.label} · ${vm.quality}p",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1
                )
            }
            Button(onClick = { requestExport() }, enabled = vm.clips.isNotEmpty(), shape = RoundedCornerShape(20.dp)) {
                Text("Export")
            }
        }

        // ---------- preview (shows the chosen canvas ratio) ----------
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0A0A0D)), contentAlignment = Alignment.Center) {
            val ratio = vm.aspect.ratio
            val canvasMod = if (ratio == null) Modifier.fillMaxSize() else {
                val wide = maxWidth / maxHeight > ratio
                Modifier.size(if (wide) maxHeight * ratio else maxWidth, if (wide) maxHeight else maxWidth / ratio)
            }
            Box(canvasMod.background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { c -> PlayerView(c).apply { useController = false; player = vm.player } },
                    update = { it.resizeMode = if (vm.fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
                    modifier = Modifier.fillMaxSize()
                )
                if (clip != null && clip.isImage && !vm.previewAll) {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        vm.previewBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(), contentDescription = null,
                                modifier = Modifier.fillMaxSize().graphicsLayer {
                                    rotationZ = clip.rotation.toFloat()
                                    scaleX = clip.zoom * (if (clip.flipH) -1f else 1f)
                                    scaleY = clip.zoom * (if (clip.flipV) -1f else 1f)
                                },
                                contentScale = if (vm.fill) ContentScale.Crop else ContentScale.Fit,
                                colorFilter = imageFilter(clip)
                            )
                        }
                    }
                }
                if (vm.clips.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text("🎬", fontSize = 44.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Start a new project", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add videos and photos, then trim, add text, filters, music and export.",
                            color = Color.Gray, style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { addMedia() }, shape = RoundedCornerShape(24.dp)) { Text("＋  Add video or photo") }
                    }
                }
                if (vm.safeZone) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.2f).background(Color(0x33FF3B30)))
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.12f).background(Color(0x33FF3B30)))
                }
                if (clip != null) {
                    val label = if (vm.previewAll) "▶▶ ${fmt(projPos)} / ${fmt(vm.playlistTotalMs())}" else "${fmt(pos)} / ${fmt(clip.trimmedMs)}"
                    Text(
                        label,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                            .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = Color.White
                    )
                }
            }
        }

        // ---------- transport + timeline header ----------
        val totalAll = vm.playlistTotalMs().coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.togglePlay() }, enabled = vm.previewAll || (clip != null && !clip.isImage)) {
                Text(if (playing) "⏸" else "▶", style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = { vm.playAll() }, enabled = vm.clips.any { !it.isImage }) { Text("▶▶", style = MaterialTheme.typography.titleMedium) }
            if (vm.previewAll) {
                Slider(
                    value = projPos.coerceIn(0, totalAll).toFloat() / totalAll,
                    onValueChange = { vm.seekProject((it * totalAll).toLong()) },
                    modifier = Modifier.weight(1f)
                )
            } else if (clip != null && !clip.isImage) {
                val dur = clip.trimmedMs.coerceAtLeast(1)
                Slider(
                    value = pos.coerceIn(0, dur).toFloat() / dur,
                    onValueChange = { vm.scrubTo(clip, (it * dur).toLong()) },
                    modifier = Modifier.weight(1f)
                )
            } else Spacer(Modifier.weight(1f))
            TextButton(onClick = { dpPerSec = (dpPerSec / 1.4f).coerceAtLeast(12f) }) { Text("−") }
            TextButton(onClick = { dpPerSec = (dpPerSec * 1.4f).coerceAtMost(140f) }) { Text("＋") }
            TextButton(onClick = { vm.undo() }, enabled = vm.undoCount > 0) { Text("↶", style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = { vm.redo() }, enabled = vm.redoCount > 0) { Text("↷", style = MaterialTheme.typography.titleMedium) }
        }

        // ---------- timeline ----------
        Timeline(vm, pos, dpPerSec, tick = { tick() }, onAdd = { addMedia() }, openTool = { tool = it })

        // ---------- bottom: toolbar or tool panel ----------
        if (tool == null) {
            Toolbar(
                vm, clip,
                openTool = { tick(); tool = it },
                addMedia = { tick(); addMedia() },
                tick = { tick() }
            )
        } else {
            Column(Modifier.fillMaxWidth().height(250.dp).background(Color(0xFF16161D))) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tool!!.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { tool = null }) { Text("✓ Done") }
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ToolPanel(vm, tool!!, clip, pos, kfSel, { kfSel = it }, { pickAudio.launch(arrayOf("audio/*")) }, { recordVoice() })
                }
            }
        }
    }

    if (showProjects) ProjectsDialog(vm) { showProjects = false }

    // ---------- export dialog ----------
    when (val s = vm.export) {
        is ExportState.Idle -> {}
        is ExportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(s.progress * 100).toInt()}%  ·  you can switch apps, export continues")
                }
            },
            confirmButton = { TextButton(onClick = { vm.cancelExport() }) { Text("Cancel") } }
        )
        is ExportState.Done -> AlertDialog(
            onDismissRequest = { vm.dismissExport() },
            title = { Text("Saved to gallery ✅") },
            text = { Text("Your video is in Movies/VideoEditor.") },
            confirmButton = {
                TextButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, s.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(i, "Share video"))
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { vm.dismissExport() }) { Text("Close") } }
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissExport() },
            title = { Text("Export failed") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.dismissExport(); vm.startExport() }) { Text("Retry") } },
            dismissButton = { TextButton(onClick = { vm.dismissExport() }) { Text("Close") } }
        )
    }
}

// =====================================================================
// Projects
// =====================================================================
@Composable
private fun ProjectsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var renameTarget by remember { mutableStateOf<ProjectInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectInfo?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Projects") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                vm.projects.forEach { p ->
                    ProjectRow(
                        vm, p, p.id == vm.projectId,
                        onOpen = { vm.openProject(p.id); onDismiss() },
                        onRename = { renameTarget = p },
                        onDuplicate = { vm.duplicateProject(p.id) },
                        onDelete = { deleteTarget = p }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.newProject(); onDismiss() }) { Text("＋ New project") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    renameTarget?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename project") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameProject(p.id, text); renameTarget = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete project?") },
            text = { Text("“${p.name}” will be removed from this app. Your original videos and photos are not deleted.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p.id); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ProjectRow(
    vm: EditorViewModel, p: ProjectInfo, current: Boolean,
    onOpen: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit
) {
    val thumb by produceState<Bitmap?>(null, p.id, p.updated) {
        value = withContext(Dispatchers.IO) { vm.projectThumb(p.id) }
    }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A35))) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name + if (current) "  • open" else "", maxLines = 1, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
            Text("${p.clipCount} clips · ${fmt(p.durationMs)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

// =====================================================================
// Timeline: fixed centre playhead; scroll to scrub; long-press a clip to drag-reorder
// =====================================================================
@Composable
private fun Timeline(
    vm: EditorViewModel, pos: Long, dpPerSec: Float,
    tick: () -> Unit, onAdd: () -> Unit, openTool: (Tool) -> Unit
) {
    val density = LocalDensity.current
    val half = (LocalConfiguration.current.screenWidthDp / 2).dp
    val scroll = rememberScrollState()
    var expected by remember { mutableIntStateOf(-1) }
    var trimming by remember { mutableStateOf(false) }

    val widths = vm.clips.map { maxOf((it.outputMs / 1000f) * dpPerSec, 52f) }
    val starts = ArrayList<Float>().also { l ->
        var x = 0f
        widths.forEach { w -> l.add(x); x += w + 4f }
    }
    val totalDp = if (widths.isEmpty()) 0f else starts.last() + widths.last()

    val clipsNow by rememberUpdatedState(vm.clips)
    val widthsNow by rememberUpdatedState(widths)
    val startsNow by rememberUpdatedState(starts)

    // keep the centre playhead on the current position
    val sel = vm.selected
    val selIdx = vm.clips.indexOfFirst { it.id == vm.selectedId }
    val targetPx = if (sel != null && selIdx >= 0) {
        val frac = if (sel.isImage) 0f else (pos.toFloat() / sel.trimmedMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        with(density) { (starts[selIdx] + widths[selIdx] * frac).dp.roundToPx() }
    } else 0
    LaunchedEffect(targetPx, trimming) {
        if (!trimming && !scroll.isScrollInProgress && scroll.value != targetPx) {
            expected = minOf(targetPx, scroll.maxValue)
            scroll.scrollTo(targetPx)
        }
    }
    // scrubbing: when the user scrolls the timeline, move the playhead to that spot
    LaunchedEffect(Unit) {
        snapshotFlow { scroll.value to scroll.isScrollInProgress }.collect { (v, inProgress) ->
            if (!inProgress || abs(v - expected) <= 2) return@collect
            val cl = clipsNow
            if (cl.isEmpty()) return@collect
            val offDp = with(density) { v.toDp().value }
            val idx = startsNow.indexOfLast { it <= offDp }.coerceAtLeast(0)
            val frac = ((offDp - startsNow[idx]) / widthsNow[idx]).coerceIn(0f, 1f)
            vm.scrubTo(cl[idx], (frac * cl[idx].trimmedMs).toLong())
        }
    }

    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 4.dp)) {
            Column(Modifier.padding(start = half, end = half)) {
                // main track
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    vm.clips.forEachIndexed { i, c ->
                        key(c.id) {
                            ClipTile(vm, c, widths[i].dp, dpPerSec, tick) { trimming = it }
                        }
                    }
                    Box(
                        Modifier.size(48.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A35)).clickable { onAdd() },
                        contentAlignment = Alignment.Center
                    ) { Text("＋", style = MaterialTheme.typography.titleLarge) }
                }
                // text + sticker track: each layer sits at its own time range inside its clip
                if (vm.clips.any { it.layers.isNotEmpty() }) {
                    val rows = vm.clips.maxOf { minOf(it.layers.size, 3) }.coerceAtLeast(1)
                    Box(Modifier.padding(top = 4.dp).width(totalDp.dp).height((rows * 24).dp)) {
                        vm.clips.forEachIndexed { i, c ->
                            c.layers.forEachIndexed { li, l ->
                                val x = starts[i] + widths[i] * l.startFrac
                                val w = maxOf(widths[i] * (l.endFrac - l.startFrac), 30f)
                                Box(
                                    Modifier.offset(x = x.dp, y = ((li % 3) * 24).dp).width(w.dp).height(22.dp)
                                        .clip(RoundedCornerShape(5.dp)).background(Color(0xFF3B5BDB))
                                        .clickable { vm.selectedId = c.id; vm.selectedLayerId = l.id; openTool(Tool.Text) }
                                        .padding(horizontal = 5.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) { Text(l.text.take(12), fontSize = 10.sp, maxLines = 1, color = Color.White) }
                            }
                        }
                    }
                }
                // audio tracks (waveform lanes)
                vm.audioTracks.forEach { t ->
                    val wave = vm.waves[t.uri.toString()]
                    val x = minOf(t.offsetMs / 1000f * dpPerSec, maxOf(totalDp - 40f, 0f))
                    val left = maxOf(totalDp - x, 40f)
                    val len = if (wave != null && wave.isNotEmpty()) (wave.size * 0.1f - t.trimStartMs / 1000f) * dpPerSec else left
                    val w = maxOf(if (t.loop && t.offsetMs == 0L) left else minOf(len, left), 40f)
                    Box(
                        Modifier.padding(top = 4.dp).offset(x = x.dp).width(w.dp).height(26.dp).clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFF1F8A4C)).clickable { openTool(Tool.Audio) },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        WaveBars(wave, t.trimStartMs, t.trimStartMs + (w / dpPerSec * 1000f).toLong(), Color(0x99FFFFFF), Modifier.fillMaxSize())
                        Text(" ♪ ${t.name.take(22)}", fontSize = 10.sp, maxLines = 1, color = Color.White)
                    }
                }
            }
        }
        // fixed playhead
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(Color.White))
        }
    }
}

@Composable
private fun ClipTile(vm: EditorViewModel, c: Clip, w: Dp, dpPerSec: Float, tick: () -> Unit, onTrimming: (Boolean) -> Unit) {
    val sel = c.id == vm.selectedId
    var dragging by remember { mutableStateOf(false) }
    Box(
        Modifier.size(w, 56.dp).alpha(if (dragging) 0.6f else 1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF22222B))
            .border(BorderStroke(if (sel) 2.dp else 0.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Transparent), RoundedCornerShape(8.dp))
            .pointerInput(c.id, sel, c.trimmedMs) {
                detectTapGestures { off ->
                    if (!sel) { vm.selectedId = c.id; vm.stopPlayAll() }
                    else if (!c.isImage) vm.scrubTo(c, ((off.x / size.width) * c.trimmedMs).toLong())
                }
            }
            .pointerInput(c.id) {
                var acc = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { acc = 0f; dragging = true; tick(); vm.selectedId = c.id; vm.stopPlayAll() },
                    onDrag = { change, amount ->
                        change.consume()
                        acc += amount.x
                        val threshold = size.width * 0.7f
                        if (acc > threshold) { vm.move(1); acc = 0f } else if (acc < -threshold) { vm.move(-1); acc = 0f }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                )
            }
    ) {
        vm.thumbs[c.id]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (!c.isImage && !c.muted) {
            WaveBars(vm.waves[c.uri.toString()], c.startMs, c.endMs, Color(0xCCFFFFFF), Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(18.dp))
        }
        Row(
            Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (c.isImage) Text("🖼", fontSize = 9.sp)
            if (c.muted) Text("🔇", fontSize = 9.sp)
            if (c.speed != 1f) Text(String.format(Locale.US, "%.1fx", c.speed), fontSize = 9.sp, color = Color.White)
            Text(fmt(c.outputMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        if (sel) {
            c.keyframes.forEach { k ->
                Text("◆", fontSize = 10.sp, color = Color(0xFFFFD60A), modifier = Modifier.offset(x = w * k.t - 5.dp, y = 2.dp))
            }
            // trim handles
            Box(
                Modifier.align(Alignment.CenterStart).width(16.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .pointerInput(c.id, c.speed, dpPerSec) {
                        detectHorizontalDragGestures(
                            onDragStart = { onTrimming(true) },
                            onDragEnd = { onTrimming(false) },
                            onDragCancel = { onTrimming(false) }
                        ) { change, dx ->
                            change.consume()
                            val dMs = (dx / density / dpPerSec * 1000f * c.speed).toLong()
                            vm.update(c.id) { it.copy(startMs = (it.startMs + dMs).coerceIn(0L, it.endMs - 500L)) }
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("‹", color = Color.White, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                    .pointerInput(c.id, c.speed, dpPerSec) {
                        detectHorizontalDragGestures(
                            onDragStart = { onTrimming(true) },
                            onDragEnd = { onTrimming(false) },
                            onDragCancel = { onTrimming(false) }
                        ) { change, dx ->
                            change.consume()
                            val dMs = (dx / density / dpPerSec * 1000f * c.speed).toLong()
                            vm.update(c.id) { it.copy(endMs = (it.endMs + dMs).coerceIn(it.startMs + 500L, it.durationMs)) }
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("›", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

// =====================================================================
// Context-sensitive toolbar
// =====================================================================
@Composable
private fun TB(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.width(68.dp).alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled) { onClick() }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 21.sp)
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Toolbar(vm: EditorViewModel, clip: Clip?, openTool: (Tool) -> Unit, addMedia: () -> Unit, tick: () -> Unit) {
    val has = clip != null
    val vid = has && clip?.isImage == false
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
        TB("＋", "Media") { addMedia() }
        TB("🎵", "Audio") { openTool(Tool.Audio) }
        TB("🖼", "Canvas") { openTool(Tool.Canvas) }
        TB("✂", "Split", vid) { tick(); vm.split() }
        TB("⇤", "Trim left", vid) { tick(); vm.trimToPlayhead(true) }
        TB("⇥", "Trim right", vid) { tick(); vm.trimToPlayhead(false) }
        TB("⏩", "Speed", vid) { openTool(Tool.Speed) }
        TB("🔊", "Volume", vid) { openTool(Tool.Volume) }
        TB("✨", "Animate", has) { openTool(Tool.Animate) }
        TB("🌗", "Fade", has) { openTool(Tool.Fade) }
        TB("◆", "Keyframes", has) { openTool(Tool.Keyframes) }
        TB("⇄", "Transition", has) { openTool(Tool.Transition) }
        TB("🎨", "Filters", has) { openTool(Tool.Filters) }
        TB("🎚", "Adjust", has) { openTool(Tool.Adjust) }
        TB("⛶", "Crop", has) { openTool(Tool.Crop) }
        TB("🔄", "Transform", has) { openTool(Tool.Transform) }
        TB("🅣", "Text", has) { openTool(Tool.Text) }
        TB("😀", "Stickers", has) { openTool(Tool.Stickers) }
        TB("⧉", "Duplicate", has) { tick(); vm.duplicate() }
        TB("⇦", "Move left", has) { vm.move(-1) }
        TB("⇨", "Move right", has) { vm.move(1) }
        TB("🗑", "Delete", has) { tick(); vm.delete() }
        TB("🆕", "New project") { vm.newProject() }
    }
}

// =====================================================================
// Tool panels
// =====================================================================
@Composable
private fun Slide(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(shown, color = Color.Gray) }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ToolPanel(
    vm: EditorViewModel, tool: Tool, c: Clip?, pos: Long, kfSel: Int, onKfSel: (Int) -> Unit,
    pickAudio: () -> Unit, record: () -> Unit
) {
    if (tool == Tool.Canvas) { CanvasPanel(vm); return }
    if (tool == Tool.Audio) { AudioPanel(vm, pickAudio, record); return }
    if (c == null) { Text("Select a clip on the timeline first.", color = Color.Gray); return }
    val id = c.id

    when (tool) {
        Tool.Speed -> {
            Slide("Speed", c.speed, 0.25f..4f, String.format(Locale.US, "%.2fx", c.speed)) { v ->
                vm.update(id) { it.copy(speed = Math.round(v * 20) / 20f) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1f, 1.5f, 2f, 3f).forEach { s ->
                    AssistChip(onClick = { vm.update(id) { it.copy(speed = s) } }, label = { Text("${s}x") })
                }
            }
            Text("Speed is applied when you export.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text("Speed ramp (splits the clip in 3 parts)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { vm.speedRamp(0) }, label = { Text("Slow-mo moment") })
                AssistChip(onClick = { vm.speedRamp(1) }, label = { Text("Hype ramp") })
            }
        }
        Tool.Volume -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mute original audio", Modifier.weight(1f))
                Switch(checked = c.muted, onCheckedChange = { m -> vm.update(id) { it.copy(muted = m) } })
            }
            Slide("Volume", c.volume, 0f..2f, "${(c.volume * 100).toInt()}%") { v -> vm.update(id) { it.copy(volume = v) } }
        }
        Tool.Animate -> {
            Text("Motion for this clip (great for photos). Shows in the exported video.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Anim.values().forEach { a ->
                    FilterChip(selected = c.anim == a, onClick = { vm.update(id) { it.copy(anim = a) } }, label = { Text(a.label) })
                }
            }
        }
        Tool.Fade -> {
            Text("Fade through black. Use fade out + fade in on neighbouring clips for a dip transition.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Slide("Fade in", c.fadeInMs.toFloat(), 0f..2000f, "${c.fadeInMs} ms") { v -> vm.update(id) { it.copy(fadeInMs = v.toLong() / 50 * 50) } }
            Slide("Fade out", c.fadeOutMs.toFloat(), 0f..2000f, "${c.fadeOutMs} ms") { v -> vm.update(id) { it.copy(fadeOutMs = v.toLong() / 50 * 50) } }
        }
        Tool.Filters -> {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.values().forEach { f ->
                    FilterChip(selected = c.filter == f, onClick = { vm.update(id) { it.copy(filter = f) } }, label = { Text(f.label) })
                }
            }
        }
        Tool.Adjust -> {
            Slide("Brightness", c.brightness, -0.5f..0.5f, f2(c.brightness)) { v -> vm.update(id) { it.copy(brightness = v) } }
            Slide("Contrast", c.contrast, -0.5f..0.5f, f2(c.contrast)) { v -> vm.update(id) { it.copy(contrast = v) } }
            Slide("Saturation", c.saturation, -1f..1f, f2(c.saturation)) { v -> vm.update(id) { it.copy(saturation = v) } }
            Slide("Warmth", c.warmth, -1f..1f, f2(c.warmth)) { v -> vm.update(id) { it.copy(warmth = v) } }
            Slide("Hue", c.hue, -1f..1f, f2(c.hue)) { v -> vm.update(id) { it.copy(hue = v) } }
            OutlinedButton(onClick = {
                vm.update(id) { it.copy(brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f, hue = 0f) }
            }) { Text("Reset") }
        }
        Tool.Crop -> {
            Slide("Crop left", c.cropL, 0f..0.45f, "${(c.cropL * 100).toInt()}%") { v -> vm.update(id) { it.copy(cropL = v) } }
            Slide("Crop right", c.cropR, 0f..0.45f, "${(c.cropR * 100).toInt()}%") { v -> vm.update(id) { it.copy(cropR = v) } }
            Slide("Crop top", c.cropT, 0f..0.45f, "${(c.cropT * 100).toInt()}%") { v -> vm.update(id) { it.copy(cropT = v) } }
            Slide("Crop bottom", c.cropB, 0f..0.45f, "${(c.cropB * 100).toInt()}%") { v -> vm.update(id) { it.copy(cropB = v) } }
            OutlinedButton(onClick = { vm.update(id) { it.copy(cropL = 0f, cropR = 0f, cropT = 0f, cropB = 0f) } }) { Text("Reset") }
        }
        Tool.Transform -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.update(id) { it.copy(rotation = (it.rotation + 90) % 360) } }) { Text("⟳ Rotate") }
                OutlinedButton(onClick = { vm.update(id) { it.copy(flipH = !it.flipH) } }) { Text("⇋ Flip H") }
                OutlinedButton(onClick = { vm.update(id) { it.copy(flipV = !it.flipV) } }) { Text("⇅ Flip V") }
            }
            Slide("Zoom", c.zoom, 1f..3f, String.format(Locale.US, "%.2fx", c.zoom)) { v -> vm.update(id) { it.copy(zoom = v) } }
        }
        Tool.Text -> TextPanel(vm, c)
        Tool.Keyframes -> KeyframePanel(vm, c, pos, kfSel, onKfSel)
        Tool.Transition -> TransitionPanel(vm, c)
        Tool.Stickers -> {
            Text("Tap a sticker to add it. Edit size, position and timing in the Text tool.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            stickerList.chunked(8).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { e ->
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.addLayer(e, 2.5f, 0f) },
                            contentAlignment = Alignment.Center
                        ) { Text(e, fontSize = 24.sp) }
                    }
                }
            }
        }
        else -> {}
    }
}

@Composable
private fun TextPanel(vm: EditorViewModel, c: Clip) {
    val sel = c.layers.firstOrNull { it.id == vm.selectedLayerId }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.addLayer("Your text", 1f, 0.6f) }) { Text("＋ Add text") }
        c.layers.forEach { l ->
            FilterChip(selected = l.id == vm.selectedLayerId, onClick = { vm.selectedLayerId = l.id }, label = { Text(l.text.take(10).ifBlank { "…" }) })
        }
    }
    if (sel == null) {
        Text("Add a text layer, or pick one above to edit.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        return
    }
    val lid = sel.id
    OutlinedTextField(
        value = sel.text, onValueChange = { t -> vm.updateLayer(lid) { it.copy(text = t) } },
        label = { Text("Text") }, modifier = Modifier.fillMaxWidth(), singleLine = true
    )
    Slide("Size", sel.scale, 0.3f..4f, String.format(Locale.US, "%.1fx", sel.scale)) { v -> vm.updateLayer(lid) { it.copy(scale = v) } }
    Slide("Horizontal", sel.x, -0.9f..0.9f, f2(sel.x)) { v -> vm.updateLayer(lid) { it.copy(x = v) } }
    Slide("Vertical (down ↔ up)", sel.y, -0.9f..0.9f, f2(sel.y)) { v -> vm.updateLayer(lid) { it.copy(y = v) } }

    val secs = c.outputMs / 1000f
    Text("Timing (export)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Slide("Show from", sel.startFrac, 0f..1f, String.format(Locale.US, "%.1fs", sel.startFrac * secs)) { v ->
        vm.updateLayer(lid) { it.copy(startFrac = minOf(v, it.endFrac - 0.05f).coerceAtLeast(0f)) }
    }
    Slide("Show until", sel.endFrac, 0f..1f, String.format(Locale.US, "%.1fs", sel.endFrac * secs)) { v ->
        vm.updateLayer(lid) { it.copy(endFrac = maxOf(v, it.startFrac + 0.05f).coerceAtMost(1f)) }
    }
    Text("Entry animation", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextAnim.values().forEach { a ->
            FilterChip(selected = sel.anim == a, onClick = { vm.updateLayer(lid) { it.copy(anim = a) } }, label = { Text(a.label) })
        }
    }
    Text("Preview shows text all the time. Timing and animation appear in the exported video.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    Spacer(Modifier.height(6.dp))

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        textColors.forEach { col ->
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(col))
                    .border(BorderStroke(if (sel.color == col) 3.dp else 1.dp, if (sel.color == col) MaterialTheme.colorScheme.primary else Color.Gray), RoundedCornerShape(15.dp))
                    .clickable { vm.updateLayer(lid) { it.copy(color = col) } }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = sel.bold, onClick = { vm.updateLayer(lid) { it.copy(bold = !it.bold) } }, label = { Text("Bold") })
        FilterChip(selected = sel.background, onClick = { vm.updateLayer(lid) { it.copy(background = !it.background) } }, label = { Text("Box") })
        FONT_LABELS.forEachIndexed { i, n ->
            FilterChip(selected = sel.font == i, onClick = { vm.updateLayer(lid) { it.copy(font = i) } }, label = { Text(n) })
        }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = { vm.deleteLayer(lid) }) { Text("🗑 Delete layer") }
}

@Composable
private fun CanvasPanel(vm: EditorViewModel) {
    Text("Quick presets", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { vm.applyPreset(0) }, label = { Text("Reels / Shorts / TikTok") })
        AssistChip(onClick = { vm.applyPreset(1) }, label = { Text("YouTube 1080p") })
        AssistChip(onClick = { vm.applyPreset(2) }, label = { Text("Instagram post") })
        AssistChip(onClick = { vm.applyPreset(3) }, label = { Text("WhatsApp (small)") })
    }
    Text("Aspect ratio", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Aspect.values().forEach { a ->
            FilterChip(selected = vm.aspect == a, onClick = { vm.setAspect(a) }, label = { Text(a.label) })
        }
    }
    Text("Resolution (shorter side)", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(480, 720, 1080).forEach { q ->
            FilterChip(selected = vm.quality == q, onClick = { vm.setQuality(q) }, label = { Text("${q}p") })
        }
    }
    Text("Frame rate limit", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Original", 30 to "30 fps", 24 to "24 fps").forEach { (v, label) ->
            FilterChip(selected = vm.fps == v, onClick = { vm.setFps(v) }, label = { Text(label) })
        }
    }
    Text("Video quality (bitrate)", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Auto", 1 to "Balanced", 2 to "High", 3 to "Max").forEach { (v, label) ->
            FilterChip(selected = vm.bitrateLevel == v, onClick = { vm.setBitrateLevel(v) }, label = { Text(label) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Fill frame (crop) instead of black bars", Modifier.weight(1f))
        Switch(checked = vm.fill, onCheckedChange = { vm.setFill(it) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Show safe zones (keep text out of the red areas)", Modifier.weight(1f))
        Switch(checked = vm.safeZone, onCheckedChange = { vm.safeZone = it })
    }
}

@Composable
private fun TransitionPanel(vm: EditorViewModel, c: Clip) {
    Text(
        "Transition to the next clip: fades this clip out and the next one in (dip through black).",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0L to "None", 300L to "Quick", 600L to "Medium", 1000L to "Long").forEach { (ms, label) ->
            FilterChip(selected = c.fadeOutMs == ms, onClick = { vm.setTransition(ms) }, label = { Text(label) })
        }
    }
    Text("Needs a clip after this one. Shows in the exported video. Use Fade for custom lengths.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
}

@Composable
private fun KeyframePanel(vm: EditorViewModel, c: Clip, pos: Long, sel: Int, onSel: (Int) -> Unit) {
    val frac = if (c.isImage) 0f else (pos.toFloat() / c.trimmedMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    val secs = c.outputMs / 1000f
    Text(
        "Move the playhead, add a keyframe, then change its values. The clip animates between keyframes in the exported video. Scrub the playhead to preview a keyframe.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.addKeyframe(frac); onSel(c.keyframes.size) }) { Text("◆ Add keyframe") }
        OutlinedButton(onClick = { vm.clearKeyframes(); onSel(0) }, enabled = c.keyframes.isNotEmpty()) { Text("Clear all") }
    }
    Text("Presets", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Pop in", "Slide in", "Spin in", "Slow zoom").forEachIndexed { i, n ->
            AssistChip(onClick = { vm.applyKfPreset(i); onSel(0) }, label = { Text(n) })
        }
    }
    if (c.keyframes.isEmpty()) return
    val idx = sel.coerceIn(0, c.keyframes.lastIndex)
    val k = c.keyframes[idx]
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        c.keyframes.forEachIndexed { i, kf ->
            FilterChip(
                selected = i == idx,
                onClick = { onSel(i); if (!c.isImage) vm.scrubTo(c, (kf.t * c.trimmedMs).toLong()) },
                label = { Text("◆ " + String.format(Locale.US, "%.1fs", kf.t * secs)) }
            )
        }
    }
    Slide("Time", k.t, 0f..1f, String.format(Locale.US, "%.1fs", k.t * secs)) { v -> vm.updateKeyframe(idx) { it.copy(t = v) } }
    Slide("Position X", k.x, -1.5f..1.5f, f2(k.x)) { v -> vm.updateKeyframe(idx) { it.copy(x = v) } }
    Slide("Position Y", k.y, -1.5f..1.5f, f2(k.y)) { v -> vm.updateKeyframe(idx) { it.copy(y = v) } }
    Slide("Scale", k.scale, 0.1f..3f, String.format(Locale.US, "%.2fx", k.scale)) { v -> vm.updateKeyframe(idx) { it.copy(scale = v) } }
    Slide("Rotation", k.rotation, -180f..180f, "${k.rotation.toInt()}°") { v -> vm.updateKeyframe(idx) { it.copy(rotation = v) } }
    Slide("Opacity", k.opacity, 0f..1f, "${(k.opacity * 100).toInt()}%") { v -> vm.updateKeyframe(idx) { it.copy(opacity = v) } }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Ease.values().forEach { e ->
            FilterChip(selected = k.ease == e, onClick = { vm.updateKeyframe(idx) { it.copy(ease = e) } }, label = { Text(e.label) })
        }
        OutlinedButton(onClick = { vm.deleteKeyframe(idx); onSel(0) }) { Text("🗑 Delete keyframe") }
    }
}

@Composable
private fun AudioPanel(vm: EditorViewModel, pickAudio: () -> Unit, record: () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = pickAudio) { Text("＋ Music / sound") }
        Button(
            onClick = { if (vm.recording) vm.stopVoice() else record() },
            colors = ButtonDefaults.buttonColors(containerColor = if (vm.recording) Color(0xFFB3261E) else MaterialTheme.colorScheme.primary)
        ) { Text(if (vm.recording) "⏹ Stop recording" else "🎙 Record voice") }
    }
    Text(
        "Voice recording starts from the selected clip and plays your video. Use headphones to avoid echo. Up to 4 audio tracks.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray
    )
    val total = (vm.totalMs / 1000f).coerceAtLeast(1f)
    vm.audioTracks.forEach { t ->
        val dur = (vm.waves[t.uri.toString()]?.size ?: 0) * 0.1f
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0x14FFFFFF), RoundedCornerShape(10.dp)).padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("♪ ${t.name}", Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { vm.removeAudio(t.id) }) { Text("Remove") }
            }
            Slide("Volume", t.volume, 0f..2f, "${(t.volume * 100).toInt()}%") { v -> vm.updateAudio(t.id) { it.copy(volume = v) } }
            Slide("Starts at", t.offsetMs / 1000f, 0f..total, String.format(Locale.US, "%.1fs", t.offsetMs / 1000f)) { v ->
                vm.updateAudio(t.id) { it.copy(offsetMs = (v * 10).toLong() * 100L) }
            }
            if (dur > 2f) {
                Slide("Skip first", t.trimStartMs / 1000f, 0f..(dur - 1f), String.format(Locale.US, "%.1fs", t.trimStartMs / 1000f)) { v ->
                    vm.updateAudio(t.id) { it.copy(trimStartMs = (v * 10).toLong() * 100L) }
                }
            }
            if (t.offsetMs == 0L) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Loop to fill the video", Modifier.weight(1f))
                    Switch(checked = t.loop, onCheckedChange = { l -> vm.updateAudio(t.id) { it.copy(loop = l) } })
                }
            }
        }
    }
    if (vm.audioTracks.isEmpty()) {
        Text("No audio yet. Add music or record your voice.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

/** Draws audio loudness bars for the part of the file between startMs and endMs. */
@Composable
private fun WaveBars(peaks: FloatArray?, startMs: Long, endMs: Long, color: Color, modifier: Modifier) {
    if (peaks == null || peaks.isEmpty()) return
    Canvas(modifier) {
        val s = (startMs / 100).toInt().coerceIn(0, peaks.size - 1)
        val e = (endMs / 100).toInt().coerceIn(s + 1, peaks.size)
        val n = (size.width / 3f).toInt().coerceAtLeast(1)
        val step = size.width / n
        for (i in 0 until n) {
            val idx = (s + ((i.toFloat() / n) * (e - s)).toInt()).coerceIn(0, peaks.size - 1)
            val h = (peaks[idx] * size.height).coerceAtLeast(1.5f)
            val x = i * step
            drawLine(color, Offset(x, (size.height - h) / 2f), Offset(x, (size.height + h) / 2f), strokeWidth = 2f)
        }
    }
}

/** Approximate colour look for the photo preview (the export uses the full effect chain). */
private fun imageFilter(c: Clip): ColorFilter? {
    val m = ColorMatrix()
    m.setToSaturation((1f + c.saturation).coerceAtLeast(0f))
    val v = m.values
    when (c.filter) {
        Filter.Gray, Filter.Noir -> ColorMatrix().also { it.setToSaturation(0f) }.values.copyInto(v)
        Filter.Sepia, Filter.Vintage -> floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ).copyInto(v)
        Filter.Invert -> floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ).copyInto(v)
        else -> {}
    }
    val b = 255f * c.brightness
    v[4] += b; v[9] += b; v[14] += b
    return ColorFilter.colorMatrix(m)
}
