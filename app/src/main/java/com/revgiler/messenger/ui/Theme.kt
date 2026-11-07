package com.revgiler.messenger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Bg = Color(0xFF0F1216)
    val Surface = Color(0xFF181C22)
    val SurfaceHi = Color(0xFF232933)
    val Accent = Color(0xFF6C5CE7)
    val Mine = Color(0xFF4B3FB8)
    val Theirs = Color(0xFF232933)
    val Text = Color(0xFFF1F3F6)
    val TextDim = Color(0xFF9AA3AF)
    val Bad = Color(0xFFE5484D)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = Color.White,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Surface,
            onSurface = C.Text,
            surfaceVariant = C.SurfaceHi,
            onSurfaceVariant = C.TextDim,
            error = C.Bad,
        ),
        content = content,
    )
}
