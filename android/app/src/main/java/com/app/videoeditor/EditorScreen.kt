@file:OptIn(androidx.media3.common.util.UnstableApi::class, ExperimentalMaterial3Api::class)

package com.app.videoeditor

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

enum class Tool(val title: String) {
    Speed("Speed"), Volume("Volume"), Animate("Animation"), Fade("Fade"), Filters("Filters"),
    Adjust("Adjust"), Crop("Crop"), Transform("Transform"), Text("Text"), Stickers("Stickers"),
    Canvas("Canvas & export"), Music("Music")
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
    val clip = vm.selected
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            pos = vm.player.currentPosition
            playing = vm.player.isPlaying
            delay(100)
        }
    }
    LaunchedEffect(clip) {
        if (clip != null) { delay(120); vm.applyPreview(clip) }
    }
    LaunchedEffect(clip == null) {
        if (clip == null && tool != null && tool != Tool.Music && tool != Tool.Canvas) tool = null
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        uris.forEach { u -> runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        vm.addUris(uris)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.updateMusic(u)
        }
    }
    fun addMedia() = pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // ---------- top bar ----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Video Editor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (vm.clips.isNotEmpty()) Text(
                    "${vm.clips.size} clips · ${fmt(vm.totalMs)} · ${vm.aspect.label} · ${vm.quality}p",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray
                )
            }
            Button(onClick = { vm.startExport() }, enabled = vm.clips.isNotEmpty(), shape = RoundedCornerShape(20.dp)) {
                Text("Export")
            }
        }

        // ---------- preview ----------
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c -> PlayerView(c).apply { useController = false; player = vm.player } },
                modifier = Modifier.fillMaxSize()
            )
            if (clip != null && clip.isImage) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    vm.previewBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                rotationZ = clip.rotation.toFloat()
                                scaleX = clip.zoom * (if (clip.flipH) -1f else 1f)
                                scaleY = clip.zoom * (if (clip.flipV) -1f else 1f)
                            },
                            contentScale = ContentScale.Fit,
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
                // Social-app UI zones: caption area at the bottom, button column at the right
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.2f).background(Color(0x33FF3B30)))
                Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.12f).background(Color(0x33FF3B30)))
            }
            if (clip != null) {
                Text(
                    "${fmt(pos)} / ${fmt(clip.trimmedMs)}",
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color.White
                )
            }
        }

        // ---------- transport + timeline header ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.togglePlay() }, enabled = clip != null && !clip.isImage) {
                Text(if (playing) "⏸" else "▶", style = MaterialTheme.typography.titleLarge)
            }
            if (clip != null && !clip.isImage) {
                val dur = clip.trimmedMs.coerceAtLeast(1)
                Slider(
                    value = pos.coerceIn(0, dur).toFloat() / dur,
                    onValueChange = { vm.player.seekTo((it * dur).toLong()) },
                    modifier = Modifier.weight(1f)
                )
            } else Spacer(Modifier.weight(1f))
            TextButton(onClick = { dpPerSec = (dpPerSec / 1.4f).coerceAtLeast(12f) }) { Text("−") }
            TextButton(onClick = { dpPerSec = (dpPerSec * 1.4f).coerceAtMost(140f) }) { Text("＋") }
            TextButton(onClick = { vm.undo() }, enabled = vm.undoCount > 0) { Text("↶", style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = { vm.redo() }, enabled = vm.redoCount > 0) { Text("↷", style = MaterialTheme.typography.titleMedium) }
        }

        // ---------- timeline ----------
        Timeline(vm, pos, dpPerSec, onAdd = { addMedia() }, openTool = { tool = it })

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
                    ToolPanel(vm, tool!!, clip) { pickAudio.launch(arrayOf("audio/*")) }
                }
            }
        }
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
                    Text("${(s.progress * 100).toInt()}%  ·  keep the app open")
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
            confirmButton = { TextButton(onClick = { vm.dismissExport() }) { Text("OK") } }
        )
    }
}

// =====================================================================
// Timeline: main track (clips with trim handles) + text/sticker track + music track
// =====================================================================
@Composable
private fun Timeline(vm: EditorViewModel, pos: Long, dpPerSec: Float, onAdd: () -> Unit, openTool: (Tool) -> Unit) {
    fun widthOf(c: Clip): Dp = maxOf(((c.outputMs / 1000f) * dpPerSec).dp, 52.dp)
    val totalW = vm.clips.fold(0.dp) { acc, c -> acc + widthOf(c) + 4.dp }

    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column {
            // main track
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                vm.clips.forEach { c ->
                    key(c.id) { ClipTile(vm, c, widthOf(c), pos, dpPerSec) }
                }
                Box(
                    Modifier.size(48.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A35)).clickable { onAdd() },
                    contentAlignment = Alignment.Center
                ) { Text("＋", style = MaterialTheme.typography.titleLarge) }
            }
            // text + sticker track
            if (vm.clips.any { it.layers.isNotEmpty() }) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    vm.clips.forEach { c ->
                        Row(Modifier.width(widthOf(c)).height(22.dp)) {
                            c.layers.forEach { l ->
                                Box(
                                    Modifier.padding(end = 2.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF3B5BDB))
                                        .clickable { vm.selectedId = c.id; vm.selectedLayerId = l.id; openTool(Tool.Text) }
                                        .padding(horizontal = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text(l.text.take(8), fontSize = 10.sp, maxLines = 1, color = Color.White) }
                            }
                        }
                    }
                }
            }
            // music track
            if (vm.music != null) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.width(maxOf(totalW - 4.dp, 80.dp)).height(22.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF1F8A4C)).clickable { openTool(Tool.Music) }.padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) { Text("♪ ${vm.music?.lastPathSegment?.takeLast(24) ?: "Music"}", fontSize = 10.sp, maxLines = 1, color = Color.White) }
            }
        }
    }
}

@Composable
private fun ClipTile(vm: EditorViewModel, c: Clip, w: Dp, pos: Long, dpPerSec: Float) {
    val sel = c.id == vm.selectedId
    Box(
        Modifier.size(w, 56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF22222B))
            .border(BorderStroke(if (sel) 2.dp else 0.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Transparent), RoundedCornerShape(8.dp))
            .pointerInput(c.id, sel, c.trimmedMs) {
                detectTapGestures { off ->
                    if (!sel) vm.selectedId = c.id
                    else if (!c.isImage) vm.player.seekTo(((off.x / size.width) * c.trimmedMs).toLong())
                }
            }
    ) {
        vm.thumbs[c.id]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
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
            val frac = if (c.isImage) 0f else (pos.toFloat() / c.trimmedMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            if (!c.isImage) Box(Modifier.offset(x = w * frac).width(2.dp).fillMaxHeight().background(Color.White))
            // trim handles
            Box(
                Modifier.align(Alignment.CenterStart).width(16.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .pointerInput(c.id, c.speed, dpPerSec) {
                        detectHorizontalDragGestures { change, dx ->
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
                        detectHorizontalDragGestures { change, dx ->
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
        TB("🎵", "Music") { openTool(Tool.Music) }
        TB("🖼", "Canvas") { openTool(Tool.Canvas) }
        TB("✂", "Split", vid) { tick(); vm.split() }
        TB("⇤", "Trim left", vid) { tick(); vm.trimToPlayhead(true) }
        TB("⇥", "Trim right", vid) { tick(); vm.trimToPlayhead(false) }
        TB("⏩", "Speed", vid) { openTool(Tool.Speed) }
        TB("🔊", "Volume", vid) { openTool(Tool.Volume) }
        TB("✨", "Animate", has) { openTool(Tool.Animate) }
        TB("🌗", "Fade", has) { openTool(Tool.Fade) }
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
private fun ToolPanel(vm: EditorViewModel, tool: Tool, c: Clip?, pickMusic: () -> Unit) {
    if (tool == Tool.Canvas) { CanvasPanel(vm); return }
    if (tool == Tool.Music) { MusicPanel(vm, pickMusic); return }
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
        Tool.Stickers -> {
            Text("Tap a sticker to add it. Edit size and position in the Text tool.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
    Text("Aspect ratio", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Aspect.values().forEach { a ->
            FilterChip(selected = vm.aspect == a, onClick = { vm.aspect = a }, label = { Text(a.label) })
        }
    }
    Text("Quality (shorter side)", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(480, 720, 1080).forEach { q ->
            FilterChip(selected = vm.quality == q, onClick = { vm.quality = q }, label = { Text("${q}p") })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Fill frame (crop) instead of black bars", Modifier.weight(1f))
        Switch(checked = vm.fill, onCheckedChange = { vm.fill = it })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Show safe zones (keep text out of the red areas)", Modifier.weight(1f))
        Switch(checked = vm.safeZone, onCheckedChange = { vm.safeZone = it })
    }
}

@Composable
private fun MusicPanel(vm: EditorViewModel, pickMusic: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = pickMusic) { Text(if (vm.music == null) "Add music" else "Change music") }
        if (vm.music != null) OutlinedButton(onClick = { vm.updateMusic(null) }) { Text("Remove") }
    }
    if (vm.music != null) {
        Slide("Music volume", vm.musicVolume, 0f..2f, "${(vm.musicVolume * 100).toInt()}%") { vm.musicVolume = it }
        Text("Music loops to fit the whole video.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
