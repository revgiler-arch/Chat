package com.revgiler.messenger.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.revgiler.messenger.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Запись голосового: AAC моно 24 кбит/с — около 180 КБ в минуту. */
class VoiceRecorder(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null
    val elapsedMs get() = if (recorder != null) System.currentTimeMillis() - startedAt else 0L

    fun start(): Boolean {
        stopSilently()
        val f = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22050)
            r.setAudioEncodingBitRate(24_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            false
        }
    }

    /** Остановить и вернуть запись (null — слишком короткая или ошибка). */
    fun stop(): Pair<ByteArray, Long>? {
        val r = recorder ?: return null
        val dur = System.currentTimeMillis() - startedAt
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val f = file ?: return null
        file = null
        val bytes = if (ok && dur >= 700) runCatching { f.readBytes() }.getOrNull() else null
        f.delete()
        return bytes?.let { it to dur }
    }

    fun cancel() = stopSilently()

    private fun stopSilently() {
        recorder?.let { r -> runCatching { r.stop() }; runCatching { r.release() } }
        recorder = null
        file?.delete()
        file = null
    }

    companion object {
        /** Документ Firestore — до 1 МБ, поэтому ограничиваем длину. */
        const val MAX_MS = 4 * 60_000L
    }
}

/** Проигрыватель голосовых: одно сообщение за раз, файлы кешируются на телефоне. */
object VoicePlayer {
    var currentId by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var loadingId by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    fun toggle(ctx: Context, chatId: String, mediaId: String) {
        if (currentId == mediaId && player != null) {
            val p = player!!
            if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true; tick() }
            return
        }
        stop()
        loadingId = mediaId
        scope.launch {
            val file = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(ctx.filesDir, "voice").apply { mkdirs() }
                    val f = File(dir, "$mediaId.m4a")
                    if (!f.exists() || f.length() == 0L) f.writeBytes(Repo.loadMedia(chatId, mediaId))
                    f
                }
            }.getOrNull()
            loadingId = null
            if (file == null) return@launch
            val p = MediaPlayer()
            runCatching {
                p.setDataSource(file.absolutePath)
                p.prepare()
                p.setOnCompletionListener { stop() }
                p.start()
            }.onFailure { p.release(); return@launch }
            player = p
            currentId = mediaId
            playing = true
            tick()
        }
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                runCatching { if (p.duration > 0) progress = p.currentPosition.toFloat() / p.duration }
                if (!playing) break
                delay(100)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        currentId = null
        playing = false
        progress = 0f
    }
}
