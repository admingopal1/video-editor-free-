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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

enum class Tool(val label: String) {
    Trim("✂ Trim"), Split("🔪 Split"), Speed("⏩ Speed"), Audio("🔊 Audio"), Transform("🔄 Transform"),
    Adjust("🎚 Adjust"), Filter("🎨 Filters"), Text("🅣 Text"), Canvas("🖼 Canvas"),
    Music("🎵 Music"), Clip("🧩 Clip")
}

private val textColors = listOf(
    "White" to 0xFFFFFFFF.toInt(), "Yellow" to 0xFFFFE600.toInt(), "Red" to 0xFFFF3B30.toInt(),
    "Cyan" to 0xFF00E5FF.toInt(), "Black" to 0xFF000000.toInt()
)

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    var tool by remember { mutableStateOf(Tool.Trim) }
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

    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addUris(it) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.updateMusic(u) }
    fun addVideos() = pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Video Editor", style = MaterialTheme.typography.titleMedium)
                if (vm.clips.isNotEmpty()) Text("${vm.clips.size} clips · ${fmt(vm.totalMs)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Button(onClick = { vm.startExport() }, enabled = vm.clips.isNotEmpty()) { Text("Export") }
        }

        // Preview
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(factory = { c -> PlayerView(c).apply { useController = false; player = vm.player } }, modifier = Modifier.fillMaxSize())
            if (vm.clips.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Start your project", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { addVideos() }) { Text("＋ Add videos") }
                }
            }
        }

        // Transport
        if (clip != null) {
            val dur = clip.trimmedMs.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.togglePlay() }) { Text(if (playing) "⏸" else "▶", style = MaterialTheme.typography.titleLarge) }
                Slider(
                    value = (pos.coerceIn(0, dur)).toFloat() / dur,
                    onValueChange = { vm.player.seekTo((it * dur).toLong()) },
                    modifier = Modifier.weight(1f)
                )
                Text("${fmt(pos)} / ${fmt(dur)}", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Timeline
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(vm.clips, key = { it.id }) { c ->
                val sel = c.id == vm.selectedId
                Box(
                    Modifier.size(width = 84.dp, height = 56.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF22222B))
                        .border(BorderStroke(if (sel) 2.dp else 0.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Transparent), RoundedCornerShape(8.dp))
                        .clickable { vm.selectedId = c.id }
                ) {
                    vm.thumbs[c.id]?.let {
                        Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(
                        fmt(c.outputMs), Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            item {
                Box(
                    Modifier.size(width = 56.dp, height = 56.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF22222B)).clickable { addVideos() },
                    contentAlignment = Alignment.Center
                ) { Text("＋", style = MaterialTheme.typography.titleLarge) }
            }
        }

        // Tool tabs
        ScrollableTabRow(selectedTabIndex = tool.ordinal, edgePadding = 8.dp, divider = {}) {
            Tool.values().forEach { t ->
                Tab(selected = tool == t, onClick = { tool = t }, text = { Text(t.label, maxLines = 1) })
            }
        }

        // Tool panel
        Column(Modifier.fillMaxWidth().height(190.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
            ToolPanel(vm, tool, clip) { pickAudio.launch(arrayOf("audio/*")) }
        }
    }

    // Export dialog
    when (val s = vm.export) {
        is ExportState.Idle -> {}
        is ExportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(s.progress * 100).toInt()}%")
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

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f)); Text(shown, color = Color.Gray) }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ToolPanel(vm: EditorViewModel, tool: Tool, c: Clip?, pickMusic: () -> Unit) {
    // Tools that don't need a selected clip
    if (tool == Tool.Canvas) {
        Text("Aspect ratio")
        Row(Modifier.horizontalScroll2(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Aspect.values().forEach { a ->
                FilterChip(selected = vm.aspect == a, onClick = { vm.aspect = a }, label = { Text(a.label) })
            }
        }
        Text("Quality (shorter side)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(480, 720, 1080).forEach { q ->
                FilterChip(selected = vm.quality == q, onClick = { vm.quality = q }, label = { Text("${q}p") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fill frame (crop) instead of black bars", Modifier.weight(1f))
            Switch(checked = vm.fill, onCheckedChange = { vm.fill = it })
        }
        return
    }
    if (tool == Tool.Music) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = pickMusic) { Text(if (vm.music == null) "Add music" else "Change music") }
            if (vm.music != null) OutlinedButton(onClick = { vm.updateMusic(null) }) { Text("Remove") }
        }
        if (vm.music != null) {
            LabeledSlider("Music volume", vm.musicVolume, 0f..2f, "${(vm.musicVolume * 100).toInt()}%") { vm.musicVolume = it }
            Text("Music loops to fit the whole video.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        return
    }
    if (c == null) { Text("Add a video to start editing.", color = Color.Gray); return }
    val id = c.id

    when (tool) {
        Tool.Trim -> {
            Text("Trim: ${fmt(c.startMs)} → ${fmt(c.endMs)}  (${fmt(c.trimmedMs)})")
            RangeSlider(
                value = c.startMs.toFloat()..c.endMs.toFloat(),
                valueRange = 0f..c.durationMs.toFloat(),
                onValueChange = { r ->
                    val s = r.start.toLong(); val e = r.endInclusive.toLong()
                    if (e - s >= 500) vm.update(id) { it.copy(startMs = s, endMs = e) }
                }
            )
        }
        Tool.Split -> {
            Text("Move the playhead where you want to cut, then tap Split.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.split() }) { Text("✂ Split at playhead") }
        }
        Tool.Speed -> {
            LabeledSlider("Speed", c.speed, 0.25f..4f, String.format(Locale.US, "%.2fx", c.speed)) { v -> vm.update(id) { it.copy(speed = (Math.round(v * 20) / 20f)) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1f, 1.5f, 2f, 3f).forEach { s -> AssistChip(onClick = { vm.update(id) { it.copy(speed = s) } }, label = { Text("${s}x") }) }
            }
        }
        Tool.Audio -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mute original audio", Modifier.weight(1f))
                Switch(checked = c.muted, onCheckedChange = { m -> vm.update(id) { it.copy(muted = m) } })
            }
            LabeledSlider("Volume", c.volume, 0f..2f, "${(c.volume * 100).toInt()}%") { v -> vm.update(id) { it.copy(volume = v) } }
        }
        Tool.Transform -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.update(id) { it.copy(rotation = (it.rotation + 90) % 360) } }) { Text("⟳ Rotate") }
                OutlinedButton(onClick = { vm.update(id) { it.copy(flipH = !it.flipH) } }) { Text("⇋ Flip H") }
                OutlinedButton(onClick = { vm.update(id) { it.copy(flipV = !it.flipV) } }) { Text("⇅ Flip V") }
            }
            LabeledSlider("Zoom", c.zoom, 1f..3f, String.format(Locale.US, "%.2fx", c.zoom)) { v -> vm.update(id) { it.copy(zoom = v) } }
        }
        Tool.Adjust -> {
            LabeledSlider("Brightness", c.brightness, -0.5f..0.5f, String.format(Locale.US, "%.2f", c.brightness)) { v -> vm.update(id) { it.copy(brightness = v) } }
            LabeledSlider("Contrast", c.contrast, -0.5f..0.5f, String.format(Locale.US, "%.2f", c.contrast)) { v -> vm.update(id) { it.copy(contrast = v) } }
            LabeledSlider("Saturation", c.saturation, -1f..1f, String.format(Locale.US, "%.2f", c.saturation)) { v -> vm.update(id) { it.copy(saturation = v) } }
            LabeledSlider("Warmth", c.warmth, -1f..1f, String.format(Locale.US, "%.2f", c.warmth)) { v -> vm.update(id) { it.copy(warmth = v) } }
            OutlinedButton(onClick = { vm.update(id) { it.copy(brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f) } }) { Text("Reset") }
        }
        Tool.Filter -> {
            Row(Modifier.horizontalScroll2(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.values().forEach { f ->
                    FilterChip(selected = c.filter == f, onClick = { vm.update(id) { it.copy(filter = f) } }, label = { Text(f.label) })
                }
            }
        }
        Tool.Text -> {
            OutlinedTextField(
                value = c.text, onValueChange = { t -> vm.update(id) { it.copy(text = t) } },
                label = { Text("Text on this clip") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            LabeledSlider("Size", c.textScale, 0.3f..3f, String.format(Locale.US, "%.1fx", c.textScale)) { v -> vm.update(id) { it.copy(textScale = v) } }
            LabeledSlider("Position (down ↔ up)", c.textY, -0.9f..0.9f, String.format(Locale.US, "%.2f", c.textY)) { v -> vm.update(id) { it.copy(textY = v) } }
            Row(Modifier.horizontalScroll2(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                textColors.forEach { (n, col) ->
                    FilterChip(selected = c.textColor == col, onClick = { vm.update(id) { it.copy(textColor = col) } }, label = { Text(n) })
                }
            }
        }
        Tool.Clip -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.move(-1) }) { Text("◀ Move") }
                OutlinedButton(onClick = { vm.move(1) }) { Text("Move ▶") }
                OutlinedButton(onClick = { vm.duplicate() }) { Text("⧉ Duplicate") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.delete() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("🗑 Delete clip") }
        }
        else -> {}
    }
}

@Composable
private fun Modifier.horizontalScroll2(): Modifier = this.then(Modifier.horizontalScrollCompat())

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))
