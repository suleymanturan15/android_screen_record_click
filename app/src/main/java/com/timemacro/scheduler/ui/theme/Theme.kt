package com.timemacro.scheduler.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    secondary = Color(0xFF009688),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FA8DA),
    secondary = Color(0xFF80CBC4),
)

@Composable
fun TimeMacroTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? android.app.Activity
            if (activity != null) {
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            }
        }
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

