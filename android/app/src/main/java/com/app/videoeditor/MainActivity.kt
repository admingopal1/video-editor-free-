package com.app.videoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7C9CFF),
                    background = Color(0xFF0E0E12),
                    surface = Color(0xFF16161D)
                )
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    EditorScreen(vm)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.player.pause()
    }
}
