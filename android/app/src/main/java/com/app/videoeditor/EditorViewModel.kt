package com.app.videoeditor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EditorViewModel : ViewModel() {

    // App ka main Project State (Timeline, Clips, Music sab isme hai)
    private val _project = MutableStateFlow(VideoProject())
    val project: StateFlow<VideoProject> = _project.asStateFlow()

    // Export ki state (Idle, Running, Done, Failed)
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    // Timeline Playhead (Current time in ms)
    private val _currentPlayheadMs = MutableStateFlow(0L)
    val currentPlayheadMs: StateFlow<Long> = _currentPlayheadMs.asStateFlow()

    // Naya Clip add karne ke liye
    fun addClip(uri: Uri, durationMs: Long, width: Int, height: Int, isImage: Boolean = false) {
        val currentClips = _project.value.timeline.clips
        val newId = (currentClips.maxOfOrNull { it.id } ?: 0L) + 1L
        
        val newClip = Clip(
            id = newId,
            uri = uri,
            durationMs = durationMs,
            width = width,
            height = height,
            isImage = isImage
        )

        val updatedClips = currentClips + newClip
        _project.value = _project.value.copy(
            timeline = _project.value.timeline.copy(clips = updatedClips)
        )
    }

    // Clip ko timeline se remove karne ke liye
    fun removeClip(clipId: Long) {
        val updatedClips = _project.value.timeline.clips.filter { it.id != clipId }
        _project.value = _project.value.copy(
            timeline = _project.value.timeline.copy(clips = updatedClips)
        )
        // Agar playhead hataye gaye clip ke aage chala gaya, toh use reset karo
        if (_currentPlayheadMs.value > _project.value.timeline.totalDurationMs) {
            _currentPlayheadMs.value = _project.value.timeline.totalDurationMs
        }
    }

    // Playhead ko move karne ke liye (Scrubbing)
    fun seekTo(timeMs: Long) {
        val maxTime = _project.value.timeline.totalDurationMs
        _currentPlayheadMs.value = timeMs.coerceIn(0L, maxTime)
    }

    // Timeline par jo current time chal raha hai, us clip ko dhoondhne ke liye
    fun getActiveClipAt(timeMs: Long): Clip? {
        var accumulatedTime = 0L
        for (clip in _project.value.timeline.clips) {
            val clipEnd = accumulatedTime + clip.outputMs
            if (timeMs >= accumulatedTime && timeMs < clipEnd) {
                return clip
            }
            accumulatedTime = clipEnd
        }
        return null // Agar koi clip nahi mili (jaise end par)
    }

    // Export start karne ka function (Isko aap apne ExportService se jodna)
    fun startExport() {
        viewModelScope.launch {
            _exportState.value = ExportState.Running(0f)
            try {
                // TODO: Yahan aap apna purana Export code call karo
                // Simulate export for now:
                kotlinx.coroutines.delay(2000) 
                _exportState.value = ExportState.Done(Uri.parse("content://dummy_video.mp4"))
            } catch (e: Exception) {
                _exportState.value = ExportState.Failed(e.message ?: "Unknown error")
            }
        }
    }
}
