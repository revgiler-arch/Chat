package com.revgiler.messenger.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Готовые обои: градиент и лёгкий узор поверх. */
data class Preset(val id: String, val title: String, val top: Color, val bottom: Color, val pattern: Int)

object Wallpapers {
    val presets = listOf(
        Preset("plain", "Без обоев", C.Bg, C.Bg, 0),
        Preset("night", "Ночь", Color(0xFF151233), Color(0xFF0B0B14), 1),
        Preset("ocean", "Океан", Color(0xFF0E2A3D), Color(0xFF081520), 2),
        Preset("forest", "Лес", Color(0xFF10291E), Color(0xFF07130D), 1),
        Preset("sunset", "Закат", Color(0xFF3A1626), Color(0xFF140A12), 2),
        Preset("graphite", "Графит", Color(0xFF24272D), Color(0xFF121418), 3),
        Preset("violet", "Аметист", Color(0xFF2A1745), Color(0xFF120A1F), 3),
        Preset("sand", "Песок", Color(0xFF332A1C), Color(0xFF15110B), 1),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("wallpapers", Context.MODE_PRIVATE)

    /** Сохранённое значение: "preset:<id>" или "photo:<путь>". Для чата — своё, иначе общее. */
    fun get(ctx: Context, chatId: String?): String {
        val p = prefs(ctx)
        return chatId?.let { p.getString("chat_$it", null) } ?: p.getString("default", null) ?: "preset:night"
    }

    fun hasOwn(ctx: Context, chatId: String) = prefs(ctx).contains("chat_$chatId")

    fun set(ctx: Context, chatId: String?, value: String?) {
        val key = chatId?.let { "chat_$it" } ?: "default"
        prefs(ctx).edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    /** Копируем выбранное фото к себе (уменьшенным), чтобы обои не пропали из галереи. */
    suspend fun importPhoto(ctx: Context, uri: Uri, chatId: String?): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val dir = File(ctx.filesDir, "wallpapers").apply { mkdirs() }
            val f = File(dir, "${chatId ?: "default"}_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            "photo:${f.absolutePath}"
        }.getOrNull()
    }
}

@Composable
fun WallpaperBackground(value: String, modifier: Modifier = Modifier) {
    when {
        value.startsWith("photo:") -> Box(modifier.background(C.Bg)) {
            AsyncImage(File(value.removePrefix("photo:")), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))) // чтобы текст читался
        }
        else -> {
            val p = Wallpapers.presets.firstOrNull { "preset:${it.id}" == value } ?: Wallpapers.presets[1]
            Canvas(modifier.background(Brush.verticalGradient(listOf(p.top, p.bottom)))) { drawPattern(p.pattern) }
        }
    }
}

/** Узоры рисуются кодом — ничего не нужно скачивать. */
private fun DrawScope.drawPattern(kind: Int) {
    val ink = Color.White.copy(alpha = 0.045f)
    val step = 34.dp.toPx()
    when (kind) {
        1 -> { // точки в шахматном порядке
            var y = 0f
            var row = 0
            while (y < size.height + step) {
                var x = if (row % 2 == 0) 0f else step / 2
                while (x < size.width + step) {
                    drawCircle(ink, radius = 2.2.dp.toPx(), center = Offset(x, y))
                    x += step
                }
                y += step * 0.8f
                row++
            }
        }
        2 -> { // волны
            var y = step / 2
            while (y < size.height + step) {
                var x = -step
                while (x < size.width + step) {
                    drawArc(ink, 0f, 180f, false, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(step, step / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    x += step
                }
                y += step * 0.9f
            }
        }
        3 -> { // диагональные линии
            var x = -size.height
            while (x < size.width) {
                drawLine(ink, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
                x += step
            }
        }
    }
}

/** Выбор обоев: общих (chatId == null) или для одного чата. */
@Composable
fun WallpaperScreen(chatId: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var hasOwn by remember { mutableStateOf(chatId != null && Wallpapers.hasOwn(ctx, chatId)) }
    val current = remember(version) { Wallpapers.get(ctx, chatId) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            Wallpapers.importPhoto(ctx, uri, chatId)?.let {
                Wallpapers.set(ctx, chatId, it)
                hasOwn = chatId != null
                version++
            }
        }
    }
    fun choose(v: String?) {
        Wallpapers.set(ctx, chatId, v)
        hasOwn = chatId != null && v != null
        version++
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text) }
            Text(if (chatId == null) "Обои для всех чатов" else "Обои этого чата", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface)
                .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Image, null, tint = C.Accent)
            Text("  Своё фото из галереи", color = C.Text, fontSize = 16.sp)
        }
        if (chatId != null && hasOwn) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface)
                    .clickable { choose(null) }.padding(14.dp),
            ) { Text("Как во всех чатах", color = C.Text, fontSize = 16.sp) }
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            if (current.startsWith("photo:")) {
                item {
                    Thumb("Фото", selected = true) { WallpaperBackground(current, Modifier.fillMaxSize()) }
                }
            }
            items(Wallpapers.presets, key = { it.id }) { p ->
                val v = "preset:${p.id}"
                Thumb(p.title, selected = current == v, onClick = { choose(v) }) {
                    WallpaperBackground(v, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun Thumb(title: String, selected: Boolean, onClick: () -> Unit = {}, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.6f).clip(shape)
                .border(if (selected) 3.dp else 1.dp, if (selected) C.Accent else C.SurfaceHi, shape)
                .clickable(onClick = onClick),
        ) {
            content()
            // мини-пузыри для наглядности
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
                Box(Modifier.fillMaxWidth(0.7f).height(12.dp).background(C.Theirs, RoundedCornerShape(6.dp)))
                Spacer(Modifier.height(5.dp))
                Box(Modifier.align(Alignment.End).fillMaxWidth(0.6f).height(12.dp).background(C.Mine, RoundedCornerShape(6.dp)))
            }
        }
        Text(title, color = if (selected) C.Accent else C.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
