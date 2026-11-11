package com.revgiler.messenger.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.ListenerRegistration
import com.revgiler.messenger.App
import com.revgiler.messenger.Notifier
import com.revgiler.messenger.data.CallSignal
import com.revgiler.messenger.data.Candidate
import com.revgiler.messenger.data.Push
import com.revgiler.messenger.data.Repo
import com.revgiler.messenger.ui.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Экран звонка (аудио и видео). Исходящий: создаём «предложение» и ждём ответа.
 * Входящий: показываем «Ответить / Отклонить», по нажатию отвечаем на предложение.
 */
class CallActivity : ComponentActivity() {

    private enum class Phase { Ringing, Calling, Connecting, Connected, Ended }

    private var phase by mutableStateOf(Phase.Connecting)
    private var title by mutableStateOf("")
    private var status by mutableStateOf("")
    private var video by mutableStateOf(false)
    private var muted by mutableStateOf(false)
    private var cameraOn by mutableStateOf(true)
    private var speaker by mutableStateOf(false)
    private var connectedAt by mutableLongStateOf(0L)
    private var remoteTrack by mutableStateOf<VideoTrack?>(null)

    private var incoming = false
    private var callId = ""
    private var chatId = ""
    private var otherUid = ""
    private var session by mutableStateOf<CallSession?>(null)
    private var localTrack by mutableStateOf<VideoTrack?>(null)
    private val regs = mutableListOf<ListenerRegistration>()
    private var watchJob: Job? = null
    private var timeoutJob: Job? = null
    private var closeJob: Job? = null
    private val earlyCandidates = mutableListOf<Candidate>()
    private var callDocReady = false
    private var proximity: PowerManager.WakeLock? = null
    private var logged = false
    private var pendingAccept: (() -> Unit)? = null
    private var controlsVisible by mutableStateOf(true)
    private var controlsTick by mutableLongStateOf(0L)
    private var ringtone: Ringtone? = null
    private var vibrating = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        // В ответе есть только то, что спрашивали сейчас, поэтому смотрим фактическое состояние.
        // Без камеры звонок всё равно идёт (только звук), без микрофона — нет.
        val then = pendingAccept
        pendingAccept = null
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            then?.invoke()
        } else {
            endWith("Нет доступа к микрофону. Разрешите его в настройках приложения")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // «Назад» не сбрасывает звонок случайно — только кнопка (на Android 16 работает только так)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (phase == Phase.Ended) finish() else moveTaskToBack(true)
            }
        })
        showOverLockScreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AppTheme(alwaysDarkBars = true) { CallUi() } }
        begin(intent)
    }

    /** Запуск звонка по интенту: и при создании экрана, и когда экран ещё не успел закрыться. */
    private fun begin(intent: Intent) {
        closeJob?.cancel()
        closeJob = null
        muted = false
        connectedAt = 0L
        logged = false
        callId = ""
        chatId = ""
        otherUid = ""
        synchronized(earlyCandidates) {
            earlyCandidates.clear()
            callDocReady = false
        }
        incoming = intent.getBooleanExtra(EXTRA_INCOMING, false)
        video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        speaker = video
        cameraOn = video

        if (incoming) {
            callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
            callDocReady = true // документ звонка уже создан звонящим
            App.activeCallId = callId
            Notifier.clearCall(this, callId)
            phase = Phase.Ringing
            status = if (video) "Входящий видеозвонок" else "Входящий звонок"
            watchCall()
            startRinging() // уведомление снято выше, и его мелодия остановилась
            when (intent.getStringExtra(EXTRA_ACTION)) {
                ACTION_ACCEPT -> accept()
                ACTION_DECLINE -> decline()
            }
        } else {
            otherUid = intent.getStringExtra(EXTRA_OTHER_UID).orEmpty()
            chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
            phase = Phase.Calling
            status = "Соединяю…"
            withPermissions { startOutgoing() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // прошлый звонок завершён, а экран ещё закрывается — начинаем новый здесь же
        if (phase == Phase.Ended) {
            begin(intent)
            return
        }
        // нажали «Ответить» / «Отклонить» в уведомлении, когда экран уже открыт
        if (intent.getStringExtra(EXTRA_CALL_ID) == callId) {
            when (intent.getStringExtra(EXTRA_ACTION)) {
                ACTION_ACCEPT -> accept()
                ACTION_DECLINE -> decline()
            }
        }
    }

    // ---------------------------------------------------------------- исходящий

    private fun startOutgoing() {
        lifecycleScope.launch {
            try {
                callId = CallSignal.newCallId()
                App.activeCallId = callId
                val s = newSession()
                s.start(Push.iceServers())
                localTrack = s.localVideo
                s.createOffer { offer ->
                    lifecycleScope.launch {
                        runCatching {
                            CallSignal.create(callId, otherUid, chatId, video, offer)
                            flushEarlyCandidates()
                            regs += CallSignal.candidates(callId, CallSignal.CALLEE) { session?.addRemoteCandidate(it) }
                            Push.notify(chatId, "call", callId, video)
                            status = "Вызываю…"
                            watchCall()
                            timeoutJob = lifecycleScope.launch {
                                delay(45_000)
                                if (phase == Phase.Calling) {
                                    CallSignal.setStatus(callId, "missed")
                                    Push.notify(chatId, "cancel", callId)
                                    log(missed = true)
                                    endWith("Не отвечает")
                                }
                            }
                        }.onFailure { endWith("Не удалось позвонить: ${it.message}") }
                    }
                }
            } catch (e: Exception) {
                endWith("Не удалось позвонить: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- входящий

    private fun accept() {
        if (phase != Phase.Ringing) return
        stopRinging()
        withPermissions {
            phase = Phase.Connecting
            status = "Соединяю…"
            startConnectTimeout()
            lifecycleScope.launch {
                try {
                    val doc = CallSignal.get(callId) ?: return@launch endWith("Звонок уже завершён")
                    if (doc.status != "ringing") return@launch endWith("Звонок уже завершён")
                    chatId = doc.chatId
                    otherUid = doc.from
                    val s = newSession()
                    s.start(Push.iceServers())
                    localTrack = s.localVideo
                    regs += CallSignal.candidates(callId, CallSignal.CALLER) { session?.addRemoteCandidate(it) }
                    s.acceptOffer(doc.offerSdp ?: return@launch endWith("Пустой звонок")) { answer ->
                        lifecycleScope.launch {
                            runCatching { CallSignal.answer(callId, answer) }
                                .onFailure { endWith("Ошибка: ${it.message}") }
                        }
                    }
                } catch (e: Exception) {
                    endWith("Ошибка: ${e.message}")
                }
            }
        }
    }

    private fun decline() {
        if (callId.isNotEmpty()) CallSignal.setStatus(callId, "declined")
        endWith("Отклонён")
    }

    // ---------------------------------------------------------------- общее

    private fun newSession(): CallSession {
        val s = CallSession(this, video, object : CallSession.Listener {
            override fun onLocalCandidate(c: Candidate) {
                // Правила базы примут кандидата только когда документ звонка уже создан
                val ready = synchronized(earlyCandidates) {
                    if (!callDocReady) earlyCandidates += c
                    callDocReady
                }
                if (ready) sendCandidate(c)
            }
            override fun onCameraProblem(message: String) {
                runOnUiThread {
                    cameraOn = false
                    Toast.makeText(this@CallActivity, message, Toast.LENGTH_LONG).show()
                }
            }
            override fun onRemoteVideo(track: VideoTrack) {
                runOnUiThread { remoteTrack = track }
            }
            override fun onState(state: PeerConnection.PeerConnectionState) {
                runOnUiThread {
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            timeoutJob?.cancel()
                            if (phase != Phase.Connected) {
                                phase = Phase.Connected
                                connectedAt = System.currentTimeMillis()
                            }
                            status = ""
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> if (phase == Phase.Connected) status = "Связь прервалась, восстанавливаю…"
                        PeerConnection.PeerConnectionState.FAILED -> hangUp("Связь потеряна")
                        else -> {}
                    }
                }
            }
            override fun onError(message: String) {
                runOnUiThread { endWith(message) }
            }
        })
        session = s
        setupAudio()
        return s
    }

    private fun sendCandidate(c: Candidate) {
        CallSignal.addCandidate(callId, if (incoming) CallSignal.CALLEE else CallSignal.CALLER, c)
    }

    private fun flushEarlyCandidates() {
        val early = synchronized(earlyCandidates) {
            callDocReady = true
            earlyCandidates.toList().also { earlyCandidates.clear() }
        }
        early.forEach { sendCandidate(it) }
    }

    /** Если за это время связь не установилась, не висим на «Соединяю…» бесконечно. */
    private fun startConnectTimeout() {
        timeoutJob?.cancel()
        timeoutJob = lifecycleScope.launch {
            delay(35_000)
            if (phase == Phase.Connecting) hangUp("Не удалось соединиться: сеть не пропускает звонок")
        }
    }

    private fun watchCall() {
        watchJob?.cancel()
        watchJob = lifecycleScope.launch {
            CallSignal.watch(callId).catch { }.collect { doc ->
                if (!incoming && doc.answerSdp != null) session?.setAnswer(doc.answerSdp)
                if (!incoming && doc.status == "accepted" && phase == Phase.Calling) {
                    phase = Phase.Connecting
                    status = "Соединяю…"
                    startConnectTimeout()
                }
                when (doc.status) {
                    "declined" -> { if (!incoming) log(missed = true); endWith("Отклонён") }
                    "ended" -> { if (!incoming) log(missed = phase != Phase.Connected); endWith("Звонок завершён") }
                    "missed" -> endWith(if (incoming) "Пропущенный звонок" else "Не отвечает")
                }
            }
        }
    }

    private fun hangUp(reason: String = "Звонок завершён") {
        if (callId.isNotEmpty() && phase != Phase.Ended) {
            CallSignal.setStatus(callId, "ended")
            if (!incoming) {
                if (phase == Phase.Calling) Push.notify(chatId, "cancel", callId)
                log(missed = phase != Phase.Connected)
            }
        }
        endWith(reason)
    }

    /** Запись о звонке в переписке — пишет только звонящий, чтобы не было двух записей. */
    private fun log(missed: Boolean) {
        if (logged || incoming || chatId.isEmpty()) return
        logged = true
        val dur = if (connectedAt > 0) System.currentTimeMillis() - connectedAt else 0L
        Repo.logCall(chatId, video, dur, missed)
    }

    private fun endWith(text: String) {
        if (phase == Phase.Ended) return
        phase = Phase.Ended
        status = text
        timeoutJob?.cancel()
        release()
        closeJob = lifecycleScope.launch {
            delay(1500)
            finish()
        }
    }

    private fun release() {
        stopRinging()
        watchJob?.cancel()
        regs.forEach { it.remove() }
        regs.clear()
        localTrack = null
        remoteTrack = null
        session?.dispose()
        session = null
        restoreAudio()
        runCatching { proximity?.release() }
        if (App.activeCallId == callId) App.activeCallId = null
    }

    override fun onDestroy() {
        if (phase != Phase.Ended) hangUp()
        release()
        super.onDestroy()
    }


    private fun hasPermission(name: String) =
        ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED

    private fun withPermissions(then: () -> Unit) {
        val need = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filterNot { hasPermission(it) }
        if (need.isEmpty()) then() else {
            pendingAccept = then
            permissions.launch(need.toTypedArray())
        }
    }

    // ---------------------------------------------------------------- звук

    private val audio get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun startRinging() {
        if (ringtone != null || vibrating) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = runCatching {
            RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.getOrNull()
        if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            runCatching {
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                    .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
                vibrating = true
            }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        if (vibrating) {
            runCatching { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() }
            vibrating = false
        }
    }

    private fun setupAudio() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeaker()
        if (!video) {
            // во время аудиозвонка экран гаснет, когда телефон у уха
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "chat:call").apply { acquire(3 * 3_600_000L) }
            }
        }
    }

    private fun applySpeaker() {
        val am = audio
        if (Build.VERSION.SDK_INT >= 31) {
            if (speaker) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = speaker
        }
    }

    private fun restoreAudio() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- интерфейс

    @Composable
    private fun CallUi() {
        Box(Modifier.fillMaxSize().background(Color(0xFF101318))) {
            val s = session
            val remote = remoteTrack
            val local = localTrack
            // Во время видеозвонка кнопки уезжают вниз; нажатие по экрану возвращает их
            val autoHide = video && remote != null && phase == Phase.Connected
            LaunchedEffect(autoHide, controlsVisible, controlsTick) {
                if (!autoHide) {
                    controlsVisible = true
                } else if (controlsVisible) {
                    delay(4000)
                    controlsVisible = false
                }
            }
            if (video && remote != null && s != null && phase == Phase.Connected) {
                VideoView(s, remote, mirror = false, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { controlsVisible = !controlsVisible },
                )
            }
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(40.dp))
                if (!(video && remote != null && phase == Phase.Connected)) {
                    Box(Modifier.size(110.dp).background(Color(0xFF6C5CE7), CircleShape), Alignment.Center) {
                        Text(title.take(1).uppercase().ifBlank { "?" }, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(18.dp))
                }
                Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (phase == Phase.Connected && status.isEmpty()) Timer(connectedAt)
                else Text(status, color = Color(0xFFB8C0CC), fontSize = 16.sp)
                Spacer(Modifier.weight(1f))

                when (phase) {
                    Phase.Ringing -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(Icons.Filled.CallEnd, "Отклонить", Color(0xFFE5484D)) { decline() }
                        RoundButton(if (video) Icons.Filled.Videocam else Icons.Filled.Call, "Ответить", Color(0xFF30A46C)) { accept() }
                    }
                    Phase.Ended -> {}
                    else -> AnimatedVisibility(
                        visible = controlsVisible,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            SmallToggle(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (muted) "Микрофон выкл." else "Микрофон", muted) {
                                muted = !muted
                                session?.setMuted(muted)
                            }
                            SmallToggle(Icons.AutoMirrored.Filled.VolumeUp, "Динамик", speaker) {
                                speaker = !speaker
                                applySpeaker()
                            }
                            if (video) {
                                SmallToggle(if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Камера", !cameraOn) {
                                    cameraOn = !cameraOn
                                    session?.setCameraOn(cameraOn)
                                }
                                SmallToggle(Icons.Filled.Cameraswitch, "Сменить", false) { session?.switchCamera { } }
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        RoundButton(Icons.Filled.CallEnd, "Завершить", Color(0xFFE5484D)) { hangUp() }
                    } }
                }
                Spacer(Modifier.height(24.dp))
            }
            // своё видео — маленьким окошком в углу
            if (video && s != null && local != null && cameraOn && phase != Phase.Ended && phase != Phase.Ringing) {
                Box(Modifier.safeDrawingPadding().padding(16.dp).align(Alignment.TopEnd)) {
                    VideoView(
                        s, local, mirror = s.isFrontCamera, overlay = true,
                        modifier = Modifier.size(width = 110.dp, height = 160.dp).clip(RoundedCornerShape(14.dp)),
                    )
                }
            }
        }
    }

    @Composable
    private fun Timer(since: Long) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(since) {
            while (true) { now = System.currentTimeMillis(); delay(1000) }
        }
        val sec = ((now - since) / 1000).coerceAtLeast(0)
        Text(
            if (sec >= 3600) String.format("%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
            else String.format("%02d:%02d", sec / 60, sec % 60),
            color = Color(0xFFB8C0CC), fontSize = 16.sp,
        )
    }

    @Composable
    private fun VideoView(s: CallSession, track: VideoTrack, mirror: Boolean, modifier: Modifier, overlay: Boolean = false) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(s.egl.eglBaseContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setEnableHardwareScaler(true)
                    if (overlay) setZOrderMediaOverlay(true)
                    setMirror(mirror)
                    track.addSink(this)
                }
            },
            update = { it.setMirror(mirror) },
            onRelease = { view ->
                runCatching { track.removeSink(view) }
                view.release()
            },
            modifier = modifier,
        )
    }

    @Composable
    private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onClick, modifier = Modifier.size(72.dp).background(color, CircleShape)) {
                Icon(icon, label, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(label, color = Color.White, fontSize = 13.sp)
        }
    }

    @Composable
    private fun SmallToggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
            IconButton(
                onClick = {
                    controlsTick++ // пока нажимают кнопки, панель не прячем
                    onClick()
                },
                modifier = Modifier.size(56.dp).background(if (active) Color.White else Color(0x33FFFFFF), CircleShape),
            ) {
                Icon(icon, label, tint = if (active) Color(0xFF101318) else Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_INCOMING = "incoming"
        const val EXTRA_VIDEO = "video"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ACTION = "action"
        const val EXTRA_OTHER_UID = "otherUid"
        const val EXTRA_CHAT_ID = "chatIdForCall"
        const val ACTION_ACCEPT = "accept"
        const val ACTION_DECLINE = "decline"

        fun outgoing(ctx: Context, otherUid: String, chatId: String, title: String, video: Boolean): Intent =
            Intent(ctx, CallActivity::class.java)
                .putExtra(EXTRA_OTHER_UID, otherUid)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_VIDEO, video)

        fun incoming(ctx: Context, callId: String, title: String, video: Boolean): Intent =
            Intent(ctx, CallActivity::class.java)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_INCOMING, true)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_VIDEO, video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
