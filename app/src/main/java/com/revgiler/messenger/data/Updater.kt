package com.revgiler.messenger.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(val versionCode: Long, val apkUrl: String)

/** Проверка и установка обновлений из публичного раздела Releases на GitHub. */
object Updater {
    private const val RELEASE_API = "https://api.github.com/repos/revgiler-arch/Chat/releases/tags/latest"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun currentVersion(ctx: Context): Long = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0)

    /** Новая версия, если она есть; null — обновлений нет. */
    suspend fun check(ctx: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Messenger-updater")
            .build()
        val json = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("GitHub ответил HTTP ${r.code}")
            JSONObject(r.body?.string() ?: "{}")
        }
        val code = Regex("""versionCode:\s*(\d+)""").find(json.optString("body"))?.groupValues?.get(1)?.toLongOrNull()
            ?: return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@withContext null
        if (code > currentVersion(ctx)) UpdateInfo(code, apk.optString("browser_download_url")) else null
    }

    /** Скачать APK; [progress] — от 0 до 1. */
    suspend fun download(ctx: Context, info: UpdateInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "Messenger-${info.versionCode}.apk")
        val req = Request.Builder().url(info.apkUrl).header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Не удалось скачать: HTTP ${r.code}")
            val body = r.body ?: throw java.io.IOException("Пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: 25_000_000L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        out
    }

    /** Можно ли приложению ставить APK (Android 8+ спрашивает разрешение один раз). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

internal const val UA = "Mozilla/5.0 (Linux; Android 14) Messenger-updater"
