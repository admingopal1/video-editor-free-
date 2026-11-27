@file:OptIn(androidx.media3.common.util.UnstableApi::class, ExperimentalMaterial3Api::class)

package com.app.videoeditor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.view.LayoutInflater
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
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
    val pos = vm.playheadLocalMediaMs()      // time inside the clip under the playhead (media ms)
    val playing = vm.isPlaying
    val view = LocalView.current

    LaunchedEffect(vm.export, playing) {
        view.keepScreenOn = vm.export is ExportState.Running || playing
    }
    // Any edit: the live preview re-reads the project (effects, trims, order, audio tracks).
    LaunchedEffect(vm.clips, vm.audioTracks) {
        delay(100)
        vm.onProjectChanged()
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
    val pickOverlayImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.addOverlayImage(u)
        }
    }
    val pickOverlayVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.addOverlayVideo(u)
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

    AuroraBackground(animate = !playing && vm.export !is ExportState.Running) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // ---------- top bar ----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogoMark(Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).pressable(onClick = { showProjects = true })) {
                Text(
                    "${vm.projectName} ▾",
                    style = MaterialTheme.typography.titleMedium.copy(brush = Pal.cool),
                    fontWeight = FontWeight.Bold, maxLines = 1
                )
                Text(
                    if (vm.clips.isEmpty()) "Tap to open or create projects"
                    else "${vm.clips.size} clips · ${fmt(vm.totalMs)} · ${vm.aspect.label} · ${vm.quality}p",
                    style = MaterialTheme.typography.bodySmall, color = Pal.textLo, maxLines = 1
                )
            }
            TextButton(onClick = { pickOverlayImage.launch(arrayOf("image/*")) }) {
                Text("＋ PIP", color = Pal.cyan, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { pickOverlayVideo.launch(arrayOf("video/*")) }) {
                Text("＋ PIP video", color = Pal.cool, fontWeight = FontWeight.SemiBold)
            }
            GradientButton("Export", onClick = { requestExport() }, enabled = vm.clips.isNotEmpty(), shimmer = vm.clips.isNotEmpty())
        }

        // ---------- live preview of the whole timeline ----------
        PreviewStage(vm, Modifier.weight(1f), onAdd = { addMedia() })

        // ---------- transport ----------
        val totalTimeline = vm.timeline.totalMs().coerceAtLeast(1L)
        Glass(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), shape = RoundedCornerShape(26.dp)) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(playing, vm.clips.isNotEmpty()) { vm.togglePlay() }
                Slider(
                    value = (vm.timeMs.toFloat() / totalTimeline).coerceIn(0f, 1f),
                    onValueChange = { vm.seekTimeline((it * totalTimeline).toLong()) },
                    enabled = vm.clips.isNotEmpty(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White, activeTrackColor = Pal.cyan, inactiveTrackColor = Color(0x33FFFFFF),
                        disabledThumbColor = Color(0x55FFFFFF), disabledActiveTrackColor = Color(0x22FFFFFF), disabledInactiveTrackColor = Color(0x22FFFFFF)
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                )
                IconBtn(Ic.ZoomOut) { dpPerSec = (dpPerSec / 1.4f).coerceAtLeast(12f) }
                IconBtn(Ic.ZoomIn) { dpPerSec = (dpPerSec * 1.4f).coerceAtMost(140f) }
                IconBtn(Ic.Undo, vm.undoCount > 0) { vm.undo() }
                IconBtn(Ic.Redo, vm.redoCount > 0) { vm.redo() }
            }
        }

        // ---------- timeline ----------
        Glass(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), shape = RoundedCornerShape(24.dp)) {
            Timeline(vm, pos, dpPerSec, tick = { tick() }, onAdd = { addMedia() }, openTool = { tool = it })
        }

        // ---------- bottom: toolbar or tool sheet ----------
        AnimatedContent(
            targetState = tool,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 4 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(200)) { it / 4 })
            },
            label = "bottomArea"
        ) { t ->
            if (t == null) {
                Toolbar(
                    vm, clip,
                    openTool = { tick(); tool = it },
                    addMedia = { tick(); addMedia() },
                    tick = { tick() }
                )
            } else {
                ToolSheet(t, onDone = { tool = null }) {
                    ToolPanel(vm, t, clip, pos, kfSel, { kfSel = it }, { pickAudio.launch(arrayOf("audio/*")) }, { recordVoice() })
                }
            }
        }
    }
    }

    if (showProjects) ProjectsDialog(vm) { showProjects = false }

    vm.overlayClips.firstOrNull { it.id == vm.selectedOverlayId }?.let { layer ->
        AlertDialog(
            onDismissRequest = { vm.selectedOverlayId = null },
            title = { Text((if (layer.isImage) "PIP photo · " else "PIP video · ") + layer.name) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Size · ${(layer.scale * 100).toInt()}%")
                    Slider(value = layer.scale, onValueChange = { v -> vm.updateOverlay(layer.id) { it.copy(scale = v) } }, valueRange = 0.1f..0.8f)
                    Text("Horizontal · ${(layer.x * 100).toInt()}")
                    Slider(value = layer.x, onValueChange = { v -> vm.updateOverlay(layer.id) { it.copy(x = v) } }, valueRange = -1f..1f)
                    Text("Vertical · ${(layer.y * 100).toInt()}")
                    Slider(value = layer.y, onValueChange = { v -> vm.updateOverlay(layer.id) { it.copy(y = v) } }, valueRange = -1f..1f)
                    Text("Opacity · ${(layer.opacity * 100).toInt()}%")
                    Slider(value = layer.opacity, onValueChange = { v -> vm.updateOverlay(layer.id) { it.copy(opacity = v) } }, valueRange = 0f..1f)
                    Text("Rotation · ${layer.rotation.toInt()}°")
                    Slider(value = layer.rotation, onValueChange = { v -> vm.updateOverlay(layer.id) { it.copy(rotation = v) } }, valueRange = -180f..180f)
                    if (!layer.isImage) {
                        Text("Source in · ${fmt(layer.startMs)}")
                        Slider(
                            value = layer.startMs.toFloat(),
                            onValueChange = { v ->
                                vm.updateOverlay(layer.id) {
                                    val start = v.toLong().coerceIn(0L, it.endMs - 500L)
                                    it.copy(startMs = start)
                                }
                            },
                            valueRange = 0f..(layer.endMs - 500L).coerceAtLeast(0L).toFloat()
                        )
                        Text("Source out · ${fmt(layer.endMs)}")
                        Slider(
                            value = layer.endMs.toFloat(),
                            onValueChange = { v ->
                                vm.updateOverlay(layer.id) {
                                    it.copy(endMs = v.toLong().coerceIn(it.startMs + 500L, it.durationMs))
                                }
                            },
                            valueRange = (layer.startMs + 500L).coerceAtMost(layer.durationMs).toFloat()..layer.durationMs.toFloat()
                        )
                        Text("Speed · ${String.format(Locale.US, "%.2fx", layer.speed)}")
                        Slider(value = layer.speed, onValueChange = { v -> vm.updateOverlay(layer.id) { it.copy(speed = v) } }, valueRange = 0.25f..2f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Mute PIP audio", modifier = Modifier.weight(1f))
                            Switch(checked = layer.muted, onCheckedChange = { v -> vm.updateOverlay(layer.id) { it.copy(muted = v) } })
                        }
                        if (!layer.muted) {
                            Text("PIP volume · ${(layer.volume * 100).toInt()}%")
                            Slider(value = layer.volume, onValueChange = { v -> vm.updateOverlay(layer.id) { it.copy(volume = v) } }, valueRange = 0f..2f)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.selectedOverlayId = null }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { vm.removeOverlay(layer.id) }) { Text("Remove PIP", color = MaterialTheme.colorScheme.error) }
            }
        )
    }

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
            Text("${p.clipCount} clips · ${fmt(p.durationMs)}", style = MaterialTheme.typography.bodySmall, color = Pal.textLo)
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
                        Modifier.size(48.dp, 56.dp).pressable(onClick = onAdd).clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(Color(0x558B5CF6), Color(0x5522D3EE))))
                            .border(1.dp, Pal.glassBorder, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) { Ico(Ic.Plus, Color.White, 22.dp) }
                }
                // PIP photo lanes: separate from the primary clip track and existing text/audio lanes.
                if (vm.overlayClips.isNotEmpty()) {
                    val pipRows = vm.overlayClips.size.coerceAtMost(4)
                    Box(Modifier.padding(top = 4.dp).width(totalDp.dp).height((pipRows * 26).dp)) {
                        vm.overlayClips.forEachIndexed { index, layer ->
                            val x = (layer.timelineStartMs / 1000f * dpPerSec).coerceIn(0f, totalDp)
                            val remainingWidth = (totalDp - x).coerceAtLeast(24f)
                            val w = (layer.outputMs / 1000f * dpPerSec).coerceAtLeast(36f).coerceAtMost(remainingWidth)
                            Box(
                                Modifier.offset(x = x.dp, y = ((index % pipRows) * 26).dp)
                                    .width(w.dp).height(23.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Brush.horizontalGradient(listOf(Color(0xFF087E8B), Color(0xFF4F46E5))))
                                    .clickable { vm.selectedOverlayId = layer.id }
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text((if (layer.isImage) "▣ PIP photo · " else "▣ PIP video · ") + layer.name, fontSize = 10.sp, maxLines = 1, color = Color.White)
                            }
                        }
                    }
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
                                        .clip(RoundedCornerShape(8.dp)).background(Brush.horizontalGradient(listOf(Pal.violetDeep, Pal.pink)))
                                        .clickable { vm.selectClip(c.id); vm.selectedLayerId = l.id; openTool(Tool.Text) }
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
                        Modifier.padding(top = 4.dp).offset(x = x.dp).width(w.dp).height(26.dp).clip(RoundedCornerShape(8.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF0F9D75), Pal.cyan))).clickable { openTool(Tool.Audio) },
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
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Pal.cool))
            Box(
                Modifier.size(11.dp).shadow(8.dp, CircleShape, ambientColor = Pal.cyan, spotColor = Pal.cyan)
                    .clip(CircleShape).background(Color.White)
            )
        }
    }
}

@Composable
private fun ClipTile(vm: EditorViewModel, c: Clip, w: Dp, dpPerSec: Float, tick: () -> Unit, onTrimming: (Boolean) -> Unit) {
    val sel = c.id == vm.selectedId
    var dragging by remember { mutableStateOf(false) }
    val tileShape = RoundedCornerShape(12.dp)
    val glow by animateDpAsState(if (sel) 12.dp else 0.dp, tween(220), label = "glow")
    Box(
        Modifier.size(w, 56.dp).alpha(if (dragging) 0.6f else 1f)
            .shadow(glow, tileShape, ambientColor = Pal.violet, spotColor = Pal.cyan)
            .clip(tileShape).background(Color(0xFF1B1838))
            .border(if (sel) 2.dp else 1.dp, if (sel) Pal.cool else SolidColor(Color(0x26FFFFFF)), tileShape)
            .pointerInput(c.id, sel, c.trimmedMs) {
                detectTapGestures { off ->
                    if (!sel) vm.selectClip(c.id)
                    else if (!c.isImage) vm.scrubTo(c, ((off.x / size.width) * c.trimmedMs).toLong())
                }
            }
            .pointerInput(c.id) {
                var acc = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { acc = 0f; dragging = true; tick(); vm.selectClip(c.id) },
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
            Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xB30A0919)).padding(horizontal = 5.dp, vertical = 1.dp),
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
                    .background(Pal.cool, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
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
            ) { Box(Modifier.size(2.dp, 16.dp).clip(CircleShape).background(Color.White)) }
            Box(
                Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight()
                    .background(Pal.cool, RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
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
            ) { Box(Modifier.size(2.dp, 16.dp).clip(CircleShape).background(Color.White)) }
        }
    }
}

// =====================================================================
// Context-sensitive toolbar
// =====================================================================
@Composable
private fun TB(ic: Ic, label: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.width(64.dp).pressable(enabled, onClick).alpha(if (enabled) 1f else 0.32f).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Glass(Modifier.size(42.dp), shape = CircleShape, strong = true, contentAlignment = Alignment.Center) {
            Ico(ic, tint, 21.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, color = Pal.textLo, maxLines = 1)
    }
}

@Composable
private fun Toolbar(vm: EditorViewModel, clip: Clip?, openTool: (Tool) -> Unit, addMedia: () -> Unit, tick: () -> Unit) {
    val has = clip != null
    val vid = has && clip?.isImage == false
    Glass(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(28.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
            TB(Ic.Plus, "Media", Pal.violet) { addMedia() }
            TB(Ic.Music, "Audio", Pal.mint) { openTool(Tool.Audio) }
            TB(Ic.Frame, "Canvas", Pal.cyan) { openTool(Tool.Canvas) }
            TB(Ic.Scissors, "Split", Pal.amber, vid) { tick(); vm.split() }
            TB(Ic.TrimL, "Trim left", Pal.amber, vid) { tick(); vm.trimToPlayhead(true) }
            TB(Ic.TrimR, "Trim right", Pal.amber, vid) { tick(); vm.trimToPlayhead(false) }
            TB(Ic.Speed, "Speed", Pal.cyan, vid) { openTool(Tool.Speed) }
            TB(Ic.Volume, "Volume", Pal.mint, vid) { openTool(Tool.Volume) }
            TB(Ic.Sparkle, "Animate", Pal.pink, has) { openTool(Tool.Animate) }
            TB(Ic.Fade, "Fade", Pal.violet, has) { openTool(Tool.Fade) }
            TB(Ic.Diamond, "Keyframes", Pal.amber, has) { openTool(Tool.Keyframes) }
            TB(Ic.Transition, "Transition", Pal.cyan, has) { openTool(Tool.Transition) }
            TB(Ic.Filters, "Filters", Pal.pink, has) { openTool(Tool.Filters) }
            TB(Ic.Sliders, "Adjust", Pal.violet, has) { openTool(Tool.Adjust) }
            TB(Ic.Crop, "Crop", Pal.cyan, has) { openTool(Tool.Crop) }
            TB(Ic.Rotate, "Transform", Pal.mint, has) { openTool(Tool.Transform) }
            TB(Ic.TextT, "Text", Pal.pink, has) { openTool(Tool.Text) }
            TB(Ic.Smile, "Stickers", Pal.amber, has) { openTool(Tool.Stickers) }
            TB(Ic.Copy, "Duplicate", Pal.violet, has) { tick(); vm.duplicate() }
            TB(Ic.ArrowL, "Move left", Pal.cyan, has) { vm.move(-1) }
            TB(Ic.ArrowR, "Move right", Pal.cyan, has) { vm.move(1) }
            TB(Ic.Trash, "Delete", Pal.danger, has) { tick(); vm.delete() }
            TB(Ic.NewDoc, "New project", Pal.violet) { vm.newProject() }
        }
    }
}

/** Glass bottom sheet that hosts a tool panel. */
@Composable
private fun ToolSheet(tool: Tool, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Glass(
        Modifier.fillMaxWidth().height(262.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 22.dp, bottomEnd = 22.dp),
        strong = true
    ) {
        Column {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(38.dp, 4.dp)
                    .clip(CircleShape).background(Color(0x55FFFFFF))
            )
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tool.title, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(brush = Pal.cool), fontWeight = FontWeight.Bold
                )
                GradientButton("Done", onClick = onDone, brush = Pal.cool)
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
                content = content
            )
        }
    }
}

// =====================================================================
// Tool panels
// =====================================================================
@Composable
private fun Slide(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(shown, color = Pal.textLo) }
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
    if (c == null) { Text("Select a clip on the timeline first.", color = Pal.textLo); return }
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
            Text("Speed is applied when you export.", style = MaterialTheme.typography.bodySmall, color = Pal.textLo)
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
            Text("Motion for this clip (great for photos). Shows in the exported video.", style = MaterialTheme.typography.bodySmall, color = Pal.textLo)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Anim.values().forEach { a ->
                    FilterChip(selected = c.anim == a, onClick = { vm.update(id) { it.copy(anim = a) } }, label = { Text(a.label) })
                }
            }
        }
        Tool.Fade -> {
            Text("Fade through black. Use fade out + fade in on neighbouring clips for a dip transition.", style = MaterialTheme.typography.bodySmall, color = Pal.textLo)
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
            Text("Tap a sticker to add it. Edit size, position and timing in the Text tool.", style = MaterialTheme.typography.bodySmall, color = Pal.textLo)
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

/**
 * Compose overlay for a selected still photo. Video clips use Media3's TextureOverlay
 * from buildVideoEffects; still-image selection uses a Compose Image instead of PlayerView.
 */
// =====================================================================
// Live preview stage: video layers, photo layer, text/sticker overlays - all driven by the timeline clock
// =====================================================================
@Composable
private fun PreviewStage(vm: EditorViewModel, modifier: Modifier, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    val tl = vm.timeline
    val viewA = remember { (LayoutInflater.from(ctx).inflate(R.layout.player_texture, null) as PlayerView).also { it.player = tl.playerA } }
    val viewB = remember { (LayoutInflater.from(ctx).inflate(R.layout.player_texture, null) as PlayerView).also { it.player = tl.playerB } }
    val pipVideoPlayer = remember { ExoPlayer.Builder(ctx).build() }
    val pipVideoView = remember {
        (LayoutInflater.from(ctx).inflate(R.layout.player_texture, null) as PlayerView).also {
            it.player = pipVideoPlayer
            it.useController = false
            it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }
    DisposableEffect(pipVideoPlayer) {
        onDispose { pipVideoPlayer.release() }
    }
    val current = vm.clips.getOrNull(tl.currentIndex)
    val localOutMs = tl.localOutMs()
    val activeOverlayVideo = vm.overlayClips
        .filter { !it.isImage && it.isVisibleAt(vm.timeMs) }
        .maxByOrNull { it.layerOrder }

    LaunchedEffect(activeOverlayVideo?.id, activeOverlayVideo?.uriString) {
        val layer = activeOverlayVideo
        if (layer == null) {
            pipVideoPlayer.pause()
            pipVideoPlayer.clearMediaItems()
        } else {
            val item = MediaItem.Builder().setUri(Uri.parse(layer.uriString))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(layer.startMs)
                        .setEndPositionMs(layer.endMs)
                        .build()
                ).build()
            pipVideoPlayer.setMediaItem(item)
            pipVideoPlayer.setPlaybackParameters(PlaybackParameters(layer.speed))
            pipVideoPlayer.prepare()
        }
    }
    LaunchedEffect(activeOverlayVideo?.id, vm.isPlaying) {
        val layer = activeOverlayVideo ?: return@LaunchedEffect
        val local = (vm.timeMs - layer.timelineStartMs).coerceAtLeast(0L)
        pipVideoPlayer.seekTo((local * layer.speed).toLong().coerceIn(0L, layer.trimmedMs))
        if (vm.isPlaying) pipVideoPlayer.play() else pipVideoPlayer.pause()
    }
    LaunchedEffect(activeOverlayVideo?.id, vm.timeMs) {
        val layer = activeOverlayVideo ?: return@LaunchedEffect
        if (!vm.isPlaying) {
            val local = (vm.timeMs - layer.timelineStartMs).coerceAtLeast(0L)
            pipVideoPlayer.seekTo((local * layer.speed).toLong().coerceIn(0L, layer.trimmedMs))
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        val first = vm.clips.firstOrNull()
        val ratio = vm.aspect.ratio ?: first?.let { it.width.toFloat() / it.height.toFloat() }
        val canvasW: Dp
        val canvasH: Dp
        if (ratio == null) {
            canvasW = maxWidth; canvasH = maxHeight
        } else {
            val wide = maxWidth / maxHeight > ratio
            canvasW = if (wide) maxHeight * ratio else maxWidth
            canvasH = if (wide) maxHeight else maxWidth / ratio
        }
        val clipA = vm.clips.firstOrNull { it.id == tl.clipIdA }
        val clipB = vm.clips.firstOrNull { it.id == tl.clipIdB }
        val videoCurrent = current != null && !current.isImage

        val canvasShape = RoundedCornerShape(18.dp)
        Box(
            Modifier.size(canvasW, canvasH)
                .shadow(if (vm.clips.isEmpty()) 0.dp else 22.dp, canvasShape, ambientColor = Pal.violet, spotColor = Pal.cyan)
                .clip(canvasShape)
                .background(if (vm.clips.isEmpty()) Color(0x14FFFFFF) else Color.Black)
                .border(1.dp, Pal.glassBorder, canvasShape),
            contentAlignment = Alignment.Center
        ) {
            // Two video layers (double buffer): the active one is drawn with the clip's transform, the other
            // keeps the next clip prepared underneath so clip changes show no black frame.
            ClipFrame(clipA, videoCurrent && tl.activeIsA && current?.id == clipA?.id, canvasW, canvasH, vm.fill, localOutMs) { mod ->
                AndroidView(factory = { viewA }, modifier = mod)
            }
            ClipFrame(clipB, videoCurrent && !tl.activeIsA && current?.id == clipB?.id, canvasW, canvasH, vm.fill, localOutMs) { mod ->
                AndroidView(factory = { viewB }, modifier = mod)
            }
            // Opaque cover: hides the standby player (which keeps the next clip prepared) under the active layer.
            Box(Modifier.size(canvasW, canvasH).zIndex(1.5f).background(Color.Black))
            val bmp = tl.photo
            if (current != null && current.isImage && bmp != null) {
                val cropped = remember(bmp, current.cropL, current.cropR, current.cropT, current.cropB) {
                    cropBitmap(bmp, current.cropL, current.cropR, current.cropT, current.cropB)
                }
                val image = remember(cropped) { cropped.asImageBitmap() }
                ClipFrame(current, true, canvasW, canvasH, vm.fill, localOutMs, topLayer = true) { mod ->
                    Image(
                        bitmap = image, contentDescription = null, modifier = mod,
                        contentScale = ContentScale.FillBounds, colorFilter = imageFilter(current)
                    )
                }
            }
            if (activeOverlayVideo != null) {
                AndroidView(
                    factory = { pipVideoView },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = canvasW * (activeOverlayVideo.x / 2f), y = -canvasH * (activeOverlayVideo.y / 2f))
                        .size(
                            width = canvasW * activeOverlayVideo.scale.coerceIn(0.05f, 0.95f),
                            height = canvasH * activeOverlayVideo.scale.coerceIn(0.05f, 0.95f)
                        )
                        .graphicsLayer {
                            alpha = activeOverlayVideo.opacity
                            rotationZ = -activeOverlayVideo.rotation
                        }
                        .border(1.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                        .clickable { vm.selectedOverlayId = activeOverlayVideo.id }
                        .zIndex(5f)
                )
            }
            val activeOverlay = vm.overlayClips
                .filter { it.isImage && it.isVisibleAt(vm.timeMs) }
                .maxByOrNull { it.layerOrder }
            if (activeOverlay != null) {
                val overlayBitmap by produceState<Bitmap?>(initialValue = null, activeOverlay.uriString) {
                    value = withContext(Dispatchers.IO) { vm.overlayBitmap(activeOverlay.uriString) }
                }
                overlayBitmap?.let { imageBitmap ->
                    Image(
                        bitmap = imageBitmap.asImageBitmap(),
                        contentDescription = activeOverlay.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = canvasW * (activeOverlay.x / 2f), y = -canvasH * (activeOverlay.y / 2f))
                            .size(width = canvasW * activeOverlay.scale.coerceIn(0.05f, 0.95f), height = canvasH * activeOverlay.scale.coerceIn(0.05f, 0.95f))
                            .graphicsLayer {
                                alpha = activeOverlay.opacity
                                rotationZ = -activeOverlay.rotation
                            }
                            .border(1.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                            .clickable { vm.selectedOverlayId = activeOverlay.id }
                            .zIndex(5f)
                    )
                }
            }
            if (current != null) PreviewTextOverlays(current, localOutMs, canvasW, canvasH, vm.outputWidthPx())

            if (vm.clips.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp).zIndex(5f)
                ) {
                    EmptyHero(Modifier.size(170.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Create something great", style = MaterialTheme.typography.titleLarge.copy(brush = Pal.accent), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add videos and photos, then trim, style, add music and export in HD. Free, no watermark.",
                        color = Pal.textLo, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    GradientButton("＋  Add video or photo", onClick = onAdd, shimmer = true)
                }
            }
            if (vm.safeZone) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.2f).background(Color(0x33FF3B30)).zIndex(6f))
                Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.12f).background(Color(0x33FF3B30)).zIndex(6f))
            }
            if (vm.clips.isNotEmpty()) {
                Text(
                    "${fmt(vm.timeMs)} / ${fmt(tl.totalMs())}",
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).zIndex(7f)
                        .background(Color(0xB30A0919), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color.White
                )
            }
        }
    }
}

/**
 * Places one clip (video view or photo) in the canvas exactly like the exporter: crop, then rotate/flip/zoom,
 * then clip motion and keyframes, then fit/fill into the canvas. Inactive layers sit underneath an opaque cover but stay
 * attached, so their prepared frame is ready for the next clip.
 */
@Composable
private fun ClipFrame(
    clip: Clip?, active: Boolean, canvasW: Dp, canvasH: Dp, fill: Boolean, localOutMs: Long,
    topLayer: Boolean = false, content: @Composable (Modifier) -> Unit
) {
    val c = if (active) clip else null
    val frame = if (c == null) canvasW.value to canvasH.value else fitFrame(c.frameAspect(), canvasW.value, canvasH.value, fill)
    val rot90 = c != null && c.rotation % 180 != 0
    val xf = c?.let { clipTransform(it.look(), localOutMs * 1000L) }
    val fw = frame.first.dp
    val fh = frame.second.dp

    Box(
        Modifier.size(canvasW, canvasH).zIndex(if (c == null) 1f else if (topLayer) 3f else 2f),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(fw, fh).clipToBounds(), contentAlignment = Alignment.Center) {
            content(
                Modifier
                    .requiredSize(if (rot90) fh else fw, if (rot90) fw else fh)
                    .graphicsLayer {   // keyframes (applied last)
                        if (xf != null) {
                            translationX = xf.kfX * fw.toPx() / 2f
                            translationY = -xf.kfY * fh.toPx() / 2f
                            scaleX = xf.kfScale; scaleY = xf.kfScale
                            rotationZ = -xf.kfRotation
                            alpha = xf.alpha
                        }
                    }
                    .graphicsLayer {   // clip motion preset (zoom / pan / pulse)
                        if (xf != null) {
                            translationX = xf.animDx * fw.toPx() / 2f
                            scaleX = xf.animScale; scaleY = xf.animScale
                        }
                    }
                    .graphicsLayer {   // static zoom, flip and rotation (applied first)
                        if (xf != null) {
                            scaleX = xf.srScaleX; scaleY = xf.srScaleY
                            rotationZ = -xf.srRotation
                        }
                    }
            )
        }
    }
}

private fun cropBitmap(b: Bitmap, l: Float, r: Float, t: Float, bt: Float): Bitmap {
    if (l <= 0f && r <= 0f && t <= 0f && bt <= 0f) return b
    val x = (b.width * l).toInt().coerceIn(0, b.width - 1)
    val y = (b.height * t).toInt().coerceIn(0, b.height - 1)
    val w = (b.width * (1f - l - r)).toInt().coerceIn(1, b.width - x)
    val h = (b.height * (1f - t - bt)).toInt().coerceIn(1, b.height - y)
    return Bitmap.createBitmap(b, x, y, w, h)
}

/** Text and sticker layers at their own time range, with the same animation maths as the exporter. */
@Composable
private fun PreviewTextOverlays(c: Clip, localOutMs: Long, canvasW: Dp, canvasH: Dp, outputWidthPx: Int) {
    val density = LocalDensity.current
    val totalUs = (c.outputMs * 1000L).coerceAtLeast(1L)
    val tUs = (localOutMs * 1000L).coerceIn(0L, totalUs)
    // The exporter draws text at 72 px per scale unit on the output frame; keep the same proportion here.
    val pxPerOutputPx = with(density) { canvasW.toPx() } / outputWidthPx.coerceAtLeast(1)
    Box(Modifier.size(canvasW, canvasH).zIndex(4f)) {
        c.layers.filter { it.text.isNotBlank() }.forEach { layer ->
            val r = textRender(layer, totalUs, tUs)
            if (r.visible) {
                val family = remember(layer.font, layer.bold) {
                    FontFamily(Typeface.create(FONT_NAMES[layer.font.coerceIn(0, FONT_NAMES.lastIndex)], if (layer.bold) Typeface.BOLD else Typeface.NORMAL))
                }
                val sizePx = 72f * r.scale * pxPerOutputPx
                val align = when (layer.align) { 0 -> TextAlign.Start; 2 -> TextAlign.End; else -> TextAlign.Center }
                val shadow = if (layer.shadow) Shadow(Color(0xCC000000), Offset(4f * r.scale * pxPerOutputPx, 4f * r.scale * pxPerOutputPx), 8f * r.scale * pxPerOutputPx) else null
                val base = TextStyle(
                    color = Color(layer.color), fontFamily = family, textAlign = align, shadow = shadow,
                    fontSize = with(density) { sizePx.toSp() }
                )
                Box(
                    Modifier
                        .offset(
                            x = canvasW * ((layer.x.coerceIn(-1f, 1f) + 1f) / 2f),
                            y = canvasH * ((1f - r.y.coerceIn(-1f, 1f)) / 2f)
                        )
                        .graphicsLayer {
                            translationX = -size.width / 2f
                            translationY = -size.height / 2f
                            rotationZ = -layer.rotation       // export rotates counter-clockwise
                            alpha = r.alpha
                        }
                        .then(if (layer.background) Modifier.background(Color(0xB3000000)) else Modifier)
                ) {
                    if (layer.outline) {
                        Text(
                            text = layer.text,
                            style = base.copy(
                                color = Color.Black, shadow = null,
                                drawStyle = Stroke(width = OUTLINE_PX * r.scale * pxPerOutputPx, join = StrokeJoin.Round)
                            )
                        )
                    }
                    Text(text = layer.text, style = base)
                }
            }
        }
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
        Text("Add a text layer, or pick one above to edit.", style = MaterialTheme.typography.bodySmall, color = Pal.textLo)
        return
    }
    val lid = sel.id
    OutlinedTextField(
        value = sel.text, onValueChange = { t -> vm.updateLayer(lid) { it.copy(text = t) } },
        label = { Text("Text (use Enter for a new line)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3
    )
    Slide("Size", sel.scale, 0.3f..4f, String.format(Locale.US, "%.1fx", sel.scale)) { v -> vm.updateLayer(lid) { it.copy(scale = v) } }
    Slide("Horizontal", sel.x, -0.9f..0.9f, f2(sel.x)) { v -> vm.updateLayer(lid) { it.copy(x = v) } }
    Slide("Vertical (down ↔ up)", sel.y, -0.9f..0.9f, f2(sel.y)) { v -> vm.updateLayer(lid) { it.copy(y = v) } }
    Slide("Rotation", sel.rotation, -180f..180f, "${sel.rotation.toInt()}°") { v -> vm.updateLayer(lid) { it.copy(rotation = v) } }

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
    Text("The preview follows the timeline, so timing and entry animation show while you play or scrub.", style = MaterialTheme.typography.bodySmall, color = Pal.textLo)
    Spacer(Modifier.height(6.dp))

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        textColors.forEach { col ->
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(col))
                    .border(BorderStroke(if (sel.color == col) 3.dp else 1.dp, if (sel.color == col) MaterialTheme.colorScheme.primary else Pal.textLo), RoundedCornerShape(15.dp))
                    .clickable { vm.updateLayer(lid) { it.copy(color = col) } }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = sel.bold, onClick = { vm.updateLayer(lid) { it.copy(bold = !it.bold) } }, label = { Text("Bold") })
        FilterChip(selected = sel.background, onClick = { vm.updateLayer(lid) { it.copy(background = !it.background) } }, label = { Text("Box") })
        FilterChip(selected = sel.shadow, onClick = { vm.updateLayer(lid) { it.copy(shadow = !it.shadow) } }, label = { Text("Shadow") })
        FilterChip(selected = sel.outline, onClick = { vm.updateLayer(lid) { it.copy(outline = !it.outline) } }, label = { Text("Outline") })
        FONT_LABELS.forEachIndexed { i, n ->
            FilterChip(selected = sel.font == i, onClick = { vm.updateLayer(lid) { it.copy(font = i) } }, label = { Text(n) })
        }
    }
    Text("Alignment (multi-line text)", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Left", 1 to "Centre", 2 to "Right").forEach { (v, label) ->
            FilterChip(selected = sel.align == v, onClick = { vm.updateLayer(lid) { it.copy(align = v) } }, label = { Text(label) })
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
            FilterChip(selected = vm.aspect == a, onClick = { vm.changeAspect(a) }, label = { Text(a.label) })
        }
    }
    Text("Resolution (shorter side)", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(480, 720, 1080).forEach { q ->
            FilterChip(selected = vm.quality == q, onClick = { vm.changeQuality(q) }, label = { Text("${q}p") })
        }
    }
    Text("Frame rate limit", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Original", 30 to "30 fps", 24 to "24 fps").forEach { (v, label) ->
            FilterChip(selected = vm.fps == v, onClick = { vm.changeFps(v) }, label = { Text(label) })
        }
    }
    Text("Video quality (bitrate)", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Auto", 1 to "Balanced", 2 to "High", 3 to "Max").forEach { (v, label) ->
            FilterChip(selected = vm.bitrateLevel == v, onClick = { vm.changeBitrateLevel(v) }, label = { Text(label) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Fill frame (crop) instead of black bars", Modifier.weight(1f))
        Switch(checked = vm.fill, onCheckedChange = { vm.changeFill(it) })
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
        style = MaterialTheme.typography.bodySmall, color = Pal.textLo
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0L to "None", 300L to "Quick", 600L to "Medium", 1000L to "Long").forEach { (ms, label) ->
            FilterChip(selected = c.fadeOutMs == ms, onClick = { vm.setTransition(ms) }, label = { Text(label) })
        }
    }
    Text("Needs a clip after this one. Shows in the exported video. Use Fade for custom lengths.", style = MaterialTheme.typography.bodySmall, color = Pal.textLo)
}

@Composable
private fun KeyframePanel(vm: EditorViewModel, c: Clip, pos: Long, sel: Int, onSel: (Int) -> Unit) {
    val frac = if (c.isImage) 0f else (pos.toFloat() / c.trimmedMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    val secs = c.outputMs / 1000f
    Text(
        "Move the playhead, add a keyframe, then change its values. The clip animates between keyframes in the exported video. Scrub the playhead to preview a keyframe.",
        style = MaterialTheme.typography.bodySmall, color = Pal.textLo
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
        style = MaterialTheme.typography.bodySmall, color = Pal.textLo
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
        Text("No audio yet. Add music or record your voice.", style = MaterialTheme.typography.bodySmall, color = Pal.textLo)
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

/** Compose-side photo colour preview. Export uses Media3's equivalent effect chain. */
private fun imageFilter(c: Clip): ColorFilter {
    fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (r in 0..3) {
            for (col in 0..3) {
                for (k in 0..3) out[r * 5 + col] += a[r * 5 + k] * b[k * 5 + col]
            }
            out[r * 5 + 4] = a[r * 5 + 4]
            for (k in 0..3) out[r * 5 + 4] += a[r * 5 + k] * b[k * 5 + 4]
        }
        return out
    }
    fun scale(r: Float, g: Float, b: Float) = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
    fun saturation(amount: Float): FloatArray {
        val s = amount.coerceAtLeast(0f)
        val inv = 1f - s
        val r = 0.213f * inv; val g = 0.715f * inv; val b = 0.072f * inv
        return floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }
    fun contrast(amount: Float): FloatArray {
        val f = (1f + amount).coerceAtLeast(0f)
        val offset = 128f * (1f - f)
        return floatArrayOf(
            f, 0f, 0f, 0f, offset,
            0f, f, 0f, 0f, offset,
            0f, 0f, f, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    }
    fun hue(degrees: Float): FloatArray {
        val rad = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        return floatArrayOf(
            0.213f + cos * 0.787f - sin * 0.213f, 0.715f - cos * 0.715f - sin * 0.715f, 0.072f - cos * 0.072f + sin * 0.928f, 0f, 0f,
            0.213f - cos * 0.213f + sin * 0.143f, 0.715f + cos * 0.285f + sin * 0.140f, 0.072f - cos * 0.072f - sin * 0.283f, 0f, 0f,
            0.213f - cos * 0.213f - sin * 0.787f, 0.715f - cos * 0.715f + sin * 0.715f, 0.072f + cos * 0.928f + sin * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }
    fun brightness(amount: Float): FloatArray {
        val offset = 255f * amount
        return floatArrayOf(
            1f, 0f, 0f, 0f, offset,
            0f, 1f, 0f, 0f, offset,
            0f, 0f, 1f, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    }
    var m = identity()
    fun apply(next: FloatArray) { m = multiply(next, m) }

    // Keep the same effect order as buildVideoEffects() in Export.kt.
    if (c.brightness != 0f) apply(brightness(c.brightness))
    if (c.contrast != 0f) apply(contrast(c.contrast))
    if (c.saturation != 0f) apply(saturation(1f + c.saturation))
    if (c.hue != 0f) apply(hue(c.hue * 180f))
    if (c.warmth != 0f) apply(scale(1f + 0.3f * c.warmth, 1f, 1f - 0.3f * c.warmth))
    when (c.filter) {
        Filter.None -> {}
        Filter.Gray -> apply(saturation(0f))
        Filter.Noir -> { apply(saturation(0f)); apply(contrast(0.3f)) }
        Filter.Invert -> apply(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f
        ))
        Filter.Sepia -> apply(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        Filter.Vintage -> {
            apply(saturation(0.7f)); apply(scale(1.1f, 1f, 0.85f))
        }
        Filter.Cinema -> {
            apply(scale(1.08f, 0.97f, 1.05f)); apply(contrast(0.2f)); apply(saturation(1.1f))
        }
        Filter.Warm -> apply(scale(1.15f, 1f, 0.88f))
        Filter.Cool -> apply(scale(0.88f, 1f, 1.15f))
        Filter.Vivid -> { apply(saturation(1.4f)); apply(contrast(0.15f)) }
        Filter.Fade -> { apply(contrast(-0.25f)); apply(brightness(0.08f)); apply(saturation(0.8f)) }
        Filter.Pop -> { apply(saturation(1.6f)); apply(contrast(0.2f)) }
    }
    return ColorFilter.colorMatrix(ColorMatrix(m))
}
