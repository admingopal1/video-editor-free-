package com.app.videoeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun EditorScreen(
    viewModel: EditorViewModel = viewModel()
) {
    val project by viewModel.project.collectAsState()
    val playheadMs by viewModel.currentPlayheadMs.collectAsState()

    // Timeline ka zoom level (Kitne pixels = 1 second)
    val pixelsPerSecond = 60.dp 
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. VIDEO PREVIEW AREA (Top 50%)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            // Yahan aap ExoPlayer ya VideoView lagayenge jo currentPlayheadMs ke hisaab se chalega.
            // Abhi ke liye text dikha rahe hain:
            val activeClip = viewModel.getActiveClipAt(playheadMs)
            Text(
                text = if (activeClip != null) "Playing: ${activeClip.uri.lastPathSegment}" else "No Clip at Playhead",
                color = Color.White,
                fontSize = 18.sp
            )
        }

        // 2. TIMELINE AREA (Bottom)
        TimelineView(
            project = project,
            playheadMs = playheadMs,
            pixelsPerSecond = pixelsPerSecond,
            onSeek = { newTimeMs -> viewModel.seekTo(newTimeMs) }
        )

        // 3. TOOLBAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { /* TODO: Open gallery to pick video */ }) {
                Text("Add Clip")
            }
            Button(onClick = { viewModel.startExport() }) {
                Text("Export")
            }
        }
    }
}

@Composable
fun TimelineView(
    project: VideoProject,
    playheadMs: Long,
    pixelsPerSecond: androidx.compose.ui.unit.Dp,
    onSeek: (Long) -> Unit
) {
    val density = LocalDensity.current
    val clipWidthPerSecondPx = with(density) { pixelsPerSecond.toPx() }
    
    // Playhead position ko pixels mein convert karo
    val playheadPx = (playheadMs / 1000f) * clipWidthPerSecondPx

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(Color(0xFF121212))
    ) {
        // Horizontal Scrollable Timeline
        var scrollState = androidx.compose.foundation.rememberScrollState()
        
        // Jab playhead move ho, timeline ko scroll karo
        LaunchedEffect(playheadPx) {
            // Center playhead logic: screen ke center mein playhead fix rahega
            // For simplicity, abhi hum basic scroll kar rahe hain
            scrollState.scrollTo(playheadPx.toInt())
        }

        Row(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(scrollState)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Saare clips ko render karo
            project.timeline.clips.forEach { clip ->
                val clipWidth = with(density) { 
                    (clip.outputMs / 1000f * pixelsPerSecond).toDp() 
                }
                
                Box(
                    modifier = Modifier
                        .width(clipWidth)
                        .fillMaxHeight()
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2E2E2E))
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Thumbnail ya text
                    Text(
                        text = "Clip ${clip.id}",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
            
            // Extra space end mein taaki aakhri clip center mein aa sake
            Spacer(modifier = Modifier.width(200.dp)) 
        }

        // RED PLAYHEAD (Fixed in Center)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = with(density) { playheadPx.toDp() }) // Move with scroll
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.Red)
        ) {
            // Playhead ka top handle (Triangle shape)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(12.dp)
                    .background(Color.Red, shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            )
        }
    }
}
