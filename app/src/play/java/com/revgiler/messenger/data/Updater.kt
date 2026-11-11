package com.revgiler.messenger.data

import android.content.Context
import java.io.File

data class UpdateInfo(val versionCode: Long, val apkUrl: String)

/** Версия для Google Play обновляется только через Play — здесь самообновления нет совсем. */
object Updater {
    fun currentVersion(ctx: Context): Long = 0
    suspend fun check(ctx: Context): UpdateInfo? = null
    suspend fun download(ctx: Context, info: UpdateInfo, progress: (Float) -> Unit): File =
        error("Обновления приходят из Google Play")
    fun canInstall(ctx: Context): Boolean = false
    fun openInstallPermission(ctx: Context) {}
    fun install(ctx: Context, apk: File) {}
}
