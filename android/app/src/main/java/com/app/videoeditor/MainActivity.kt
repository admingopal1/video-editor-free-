package com.app.videoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Pal.violet, onPrimary = Color.White,
                    secondary = Pal.cyan, onSecondary = Color(0xFF04202A),
                    tertiary = Pal.pink,
                    background = Pal.bg0, onBackground = Pal.textHi,
                    surface = Color(0xFF17133A), onSurface = Pal.textHi,
                    surfaceVariant = Color(0x22FFFFFF), onSurfaceVariant = Pal.textLo,
                    secondaryContainer = Color(0x668B5CF6), onSecondaryContainer = Color.White,
                    surfaceContainerHigh = Color(0xFF1B1740), surfaceContainerHighest = Color(0xFF221D4D),
                    outline = Color(0x44FFFFFF), outlineVariant = Color(0x22FFFFFF)
                )
            ) {
                Surface(color = Color.Transparent) {
                    EditorScreen(vm)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.pausePreview()
    }
}
