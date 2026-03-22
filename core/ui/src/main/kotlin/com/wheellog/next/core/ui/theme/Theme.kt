package com.wheellog.next.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// -- Brand colors --
val HudGreen = Color(0xFF00FF41)
val DarkBackground = Color(0xFF0D0D0D)
val DarkSurface = Color(0xFF1A1A1A)
val WarningRed = Color(0xFFFF1744)
val AccentCyan = Color(0xFF00E5FF)

private val DarkScheme = darkColorScheme(
    primary = HudGreen,
    onPrimary = Color.Black,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    error = WarningRed,
    onError = Color.White,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White,
)

@Composable
fun RideFluxTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
