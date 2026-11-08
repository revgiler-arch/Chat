package com.revgiler.messenger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Файлы и фото из переписки: хранятся в Cloudflare R2, доступ — через наш сервер. */
object ChatFiles {
    const val MAX_BYTES = 50L * 1024 * 1024
    private const val MAX_SIDE = 1600

    class Prepared(val file: File, val name: String, val mime: String, val image: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    /** Фото сжимаем, остальное копируем как есть; больше 50 МБ не принимаем. */
    suspend fun prepare(ctx: Context, uri: Uri): Prepared = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.ifBlank { null } ?: "file"
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val dir = File(ctx.cacheDir, "outgoing").apply { mkdirs() }
        val out = File(dir, newId())
        if (mime.startsWith("image/") && mime != "image/gif" && runCatching { compress(ctx, uri, out) }.getOrDefault(false)) {
            return@withContext Prepared(out, name.substringBeforeLast('.') + ".jpg", "image/jpeg", true)
        }
        val input = cr.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
        var total = 0L
        input.use { src ->
            out.outputStream().use { dst ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) {
                        out.delete()
                        throw IOException("Файл больше 50 МБ")
                    }
                    dst.write(buf, 0, n)
                }
            }
        }
        Prepared(out, name, mime, false)
    }

    private fun compress(ctx: Context, uri: Uri, out: File): Boolean {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val rotation = runCatching {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        return out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    suspend fun upload(chatId: String, fileId: String, p: Prepared, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val (base, token) = Push.endpoint() ?: throw IOException("Сервер недоступен")
        val body = object : RequestBody() {
            override fun contentType() = p.mime.toMediaTypeOrNull()
            override fun contentLength() = p.file.length()
            override fun writeTo(sink: BufferedSink) {
                val total = contentLength().coerceAtLeast(1)
                var done = 0L
                var last = -1
                p.file.inputStream().use { src ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        done += n
                        val percent = (done * 100 / total).toInt()
                        if (percent != last) {
                            last = percent
                            progress(percent / 100f)
                        }
                    }
                }
            }
        }
        val req = Request.Builder().url("$base/files/$chatId/$fileId").put(body)
            .header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Сервер ответил HTTP ${r.code}")
        }
    }

    private fun local(ctx: Context, fileId: String, name: String): File {
        val dir = File(ctx.cacheDir, "chatfiles").apply { mkdirs() }
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").takeLast(80)
        return File(dir, "${fileId}_$safe")
    }

    /** После отправки своя копия уже на месте, повторно качать не нужно. */
    fun keepLocal(ctx: Context, fileId: String, name: String, file: File) {
        if (!file.renameTo(local(ctx, fileId, name))) file.delete()
    }

    suspend fun download(ctx: Context, chatId: String, fileId: String, name: String): File = withContext(Dispatchers.IO) {
        val target = local(ctx, fileId, name)
        if (target.exists()) return@withContext target
        val (base, token) = Push.endpoint() ?: throw IOException("Сервер недоступен")
        val req = Request.Builder().url("$base/files/$chatId/$fileId").header("Authorization", "Bearer $token").build()
        val part = File(target.parentFile, newId() + ".part")
        http.newCall(req).execute().use { r ->
            if (r.code == 404) throw IOException("Файл больше недоступен")
            if (!r.isSuccessful) throw IOException("Сервер ответил HTTP ${r.code}")
            val src = r.body ?: throw IOException("Пустой ответ")
            part.outputStream().use { src.byteStream().copyTo(it) }
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("Не удалось сохранить файл")
        }
        target
    }
}
