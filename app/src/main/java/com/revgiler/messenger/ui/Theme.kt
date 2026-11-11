package com.revgiler.messenger.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
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

/** Особый выбор «Системная»: тёмная или светлая палитра — как тема телефона. */
const val SYSTEM_THEME = "system"

object C {
    private const val PREFS = "ui"
    private const val KEY = "palette"

    /** Что выбрал пользователь: id палитры или [SYSTEM_THEME]. По умолчанию — как в системе. */
    var choice by mutableStateOf(SYSTEM_THEME)
        private set

    /** Тёмная ли сейчас тема телефона (обновляется из [AppTheme]). */
    var systemDark by mutableStateOf(true)

    private val systemDarkPalette get() = Palettes.first { it.id == "amethyst" }
    private val systemLightPalette get() = Palettes.first { it.id == "light" }

    val palette: Palette
        get() = if (choice == SYSTEM_THEME) {
            if (systemDark) systemDarkPalette else systemLightPalette
        } else {
            Palettes.firstOrNull { it.id == choice } ?: systemDarkPalette
        }

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
        choice = if (id == null || id == SYSTEM_THEME || Palettes.none { it.id == id }) SYSTEM_THEME else id
        systemDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO
    }

    fun choose(ctx: Context, id: String) {
        choice = id
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, id).apply()
    }
}

/** [alwaysDarkBars] — для экранов с постоянно тёмным фоном (звонок). */
@Composable
fun AppTheme(alwaysDarkBars: Boolean = false, content: @Composable () -> Unit) {
    // Тема телефона: при её смене Compose перерисует экран, и «Системная» палитра подстроится
    val sysDark = isSystemInDarkTheme()
    if (C.systemDark != sysDark) SideEffect { C.systemDark = sysDark }
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
