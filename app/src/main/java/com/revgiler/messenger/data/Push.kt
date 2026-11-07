package com.revgiler.messenger.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Связь с нашим сервером уведомлений (Cloudflare Worker, папка worker/ в репозитории).
 * Адрес сервера лежит в файле push-url.txt репозитория — его записывает сборка сервера,
 * поэтому приложение не нужно пересобирать, если адрес поменяется.
 */
object Push {
    private const val URL_FILE = "https://raw.githubusercontent.com/revgiler-arch/Chat/main/push-url.txt"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var baseUrl: String? = null
    @Volatile
    private var urlCheckedAt = 0L

    private fun serverUrl(): String? {
        val cached = baseUrl
        if (cached != null && System.currentTimeMillis() - urlCheckedAt < 6 * 3_600_000L) return cached
        val fresh = runCatching {
            http.newCall(Request.Builder().url(URL_FILE).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.trim()?.takeIf { it.startsWith("https://") } else null
            }
        }.getOrNull()
        if (fresh != null) {
            baseUrl = fresh.trimEnd('/')
            urlCheckedAt = System.currentTimeMillis()
        }
        return baseUrl
    }

    private suspend fun idToken(): String? =
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token

    /** Разбудить телефон собеседника. Ошибки молча игнорируем: сообщение всё равно уже в базе. */
    fun notify(chatId: String, kind: String = "message", callId: String = "", video: Boolean = false) {
        scope.launch {
            runCatching {
                val base = serverUrl() ?: return@launch
                val token = idToken() ?: return@launch
                val body = JSONObject()
                    .put("chatId", chatId).put("kind", kind).put("callId", callId).put("video", video)
                    .toString().toRequestBody("application/json".toMediaType())
                http.newCall(
                    Request.Builder().url("$base/notify").post(body).header("Authorization", "Bearer $token").build(),
                ).execute().close()
            }
        }
    }

    /** Серверы для соединения звонка: STUN и (если настроен) TURN Cloudflare. */
    suspend fun iceServers(): List<IceServerInfo> = withContext(Dispatchers.IO) {
        val fallback = listOf(IceServerInfo(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"), null, null))
        runCatching {
            val base = serverUrl() ?: return@withContext fallback
            val token = idToken() ?: return@withContext fallback
            val json = http.newCall(
                Request.Builder().url("$base/turn").header("Authorization", "Bearer $token").build(),
            ).execute().use { r -> JSONObject(r.body?.string() ?: "{}") }
            val arr = json.optJSONArray("iceServers") ?: return@withContext fallback
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val urls = when (val u = o.opt("urls")) {
                    is JSONArray -> (0 until u.length()).map { u.getString(it) }
                    is String -> listOf(u)
                    else -> emptyList()
                }
                if (urls.isEmpty()) null
                else IceServerInfo(urls, o.optString("username").ifBlank { null }, o.optString("credential").ifBlank { null })
            }.ifEmpty { fallback }
        }.getOrDefault(fallback)
    }
}

data class IceServerInfo(val urls: List<String>, val username: String?, val credential: String?)
