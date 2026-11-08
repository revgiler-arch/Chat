package com.revgiler.messenger.ui

import android.app.Activity
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class Palette(
    val id: String,
    val title: String,
    val light: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceHi: Color,
    val accent: Color,
    val mine: Color,
    val theirs: Color,
    val text: Color,
    val textDim: Color,
)

private val LightText = Color(0xFFF1F3F6)
private val LightDim = Color(0xFF9AA3AF)

val Palettes = listOf(
    Palette("amethyst", "Аметист", false, Color(0xFF0F1216), Color(0xFF181C22), Color(0xFF232933), Color(0xFF6C5CE7), Color(0xFF4B3FB8), Color(0xFF232933), LightText, LightDim),
    Palette("ocean", "Океан", false, Color(0xFF0B141E), Color(0xFF12202E), Color(0xFF1C2F42), Color(0xFF2F9BEF), Color(0xFF1B6BB0), Color(0xFF1C2F42), LightText, Color(0xFF93A7BA)),
    Palette("emerald", "Изумруд", false, Color(0xFF0A1512), Color(0xFF11211C), Color(0xFF1B3129), Color(0xFF1FB57F), Color(0xFF12795A), Color(0xFF1B3129), LightText, Color(0xFF8FAA9F)),
    Palette("sunset", "Закат", false, Color(0xFF17100F), Color(0xFF241917), Color(0xFF352521), Color(0xFFF2703D), Color(0xFFB04A26), Color(0xFF352521), LightText, Color(0xFFB39F98)),
    Palette("rose", "Роза", false, Color(0xFF170E14), Color(0xFF24161F), Color(0xFF35202D), Color(0xFFE5508E), Color(0xFFA62F63), Color(0xFF35202D), LightText, Color(0xFFB59BA9)),
    Palette("black", "Чёрная", false, Color(0xFF000000), Color(0xFF0E0E0F), Color(0xFF1C1C1E), Color(0xFFD9A441), Color(0xFF7D5C1E), Color(0xFF1C1C1E), LightText, Color(0xFF9B9B9F)),
    Palette("light", "Светлая", true, Color(0xFFEEF1F5), Color(0xFFFFFFFF), Color(0xFFE1E6ED), Color(0xFF3D6DF2), Color(0xFFD5E2FF), Color(0xFFFFFFFF), Color(0xFF1A1F27), Color(0xFF616B79)),
)

object C {
    private const val PREFS = "ui"
    private const val KEY = "palette"

    var palette by mutableStateOf(Palettes.first())
        private set

    val Bg get() = palette.bg
    val Surface get() = palette.surface
    val SurfaceHi get() = palette.surfaceHi
    val Accent get() = palette.accent
    val Mine get() = palette.mine
    val Theirs get() = palette.theirs
    val Text get() = palette.text
    val TextDim get() = palette.textDim
    val Bad = Color(0xFFE5484D)

    fun load(ctx: Context) {
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        palette = Palettes.firstOrNull { it.id == id } ?: Palettes.first()
    }

    fun choose(ctx: Context, p: Palette) {
        palette = p
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, p.id).apply()
    }
}

/** [alwaysDarkBars] — для экранов с постоянно тёмным фоном (звонок). */
@Composable
fun AppTheme(alwaysDarkBars: Boolean = false, content: @Composable () -> Unit) {
    val p = C.palette
    val view = LocalView.current
    if (!view.isInEditMode) {
        val lightBars = p.light && !alwaysDarkBars
        SideEffect {
            (view.context as? Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
    }
    val scheme = if (p.light) {
        lightColorScheme(
            primary = C.Accent, onPrimary = Color.White, background = C.Bg, onBackground = C.Text,
            surface = C.Surface, onSurface = C.Text, surfaceVariant = C.SurfaceHi, onSurfaceVariant = C.TextDim, error = C.Bad,
        )
    } else {
        darkColorScheme(
            primary = C.Accent, onPrimary = Color.White, background = C.Bg, onBackground = C.Text,
            surface = C.Surface, onSurface = C.Text, surfaceVariant = C.SurfaceHi, onSurfaceVariant = C.TextDim, error = C.Bad,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
