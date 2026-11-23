package com.revgiler.messenger.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.ListenerRegistration
import com.revgiler.messenger.App
import com.revgiler.messenger.Notifier
import com.revgiler.messenger.data.Candidate
import com.revgiler.messenger.data.GroupCallDoc
import com.revgiler.messenger.data.GroupCallSignal
import com.revgiler.messenger.data.LinkDoc
import com.revgiler.messenger.data.Push
import com.revgiler.messenger.data.Repo
import com.revgiler.messenger.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.UUID

/**
 * Групповой звонок (аудио или видео) до 8 человек. Каждый соединён с каждым напрямую.
 * Звонок предлагает тот, чей uid меньше, — так пара соединяется ровно один раз,
 * даже если люди входят одновременно.
 */
class GroupCallActivity : ComponentActivity() {

    private enum class Phase { Ringing, Joining, InCall, Ended }

    private var phase by mutableStateOf(Phase.Joining)
    private var title by mutableStateOf("")
    private var status by mutableStateOf("")
    private var video by mutableStateOf(false)
    private var muted by mutableStateOf(false)
    private var cameraOn by mutableStateOf(true)
    private var speaker by mutableStateOf(false)
    private var startedAt by mutableLongStateOf(0L)
    private var session by mutableStateOf<GroupCallSession?>(null)
    private var localTrack by mutableStateOf<VideoTrack?>(null)

    /** Кто в звонке, кроме меня (порядок входа). */
    private var others by mutableStateOf<List<String>>(emptyList())
    private val names = mutableStateMapOf<String, String>()
    private val videos = mutableStateMapOf<String, VideoTrack>()
    private val states = mutableStateMapOf<String, PeerConnection.PeerConnectionState>()

    private var me = ""
    private var callId = ""
    private var chatId = ""
    private var startedByMe = false
    private var anyoneJoined = false
    private val gens = HashMap<String, String>()          // текущее «поколение» связи с каждым
    private val answeredGens = HashMap<String, String>()   // на какие предложения уже ответили
    private val iceRegs = HashMap<String, ListenerRegistration>()
    private val regs = mutableListOf<ListenerRegistration>()
    private var ringTimeout: Job? = null
    private var closeJob: Job? = null
    private var ringtone: Ringtone? = null
    private var vibrating = false
    private var pendingAfterPermissions: (() -> Unit)? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val then = pendingAfterPermissions
        pendingAfterPermissions = null
        if (has(Manifest.permission.RECORD_AUDIO)) then?.invoke()
        else endWith("Нет доступа к микрофону. Разрешите его в настройках приложения")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (phase == Phase.Ended) finish() else moveTaskToBack(true)
            }
        })
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AppTheme(alwaysDarkBars = true) { CallUi() } }

        me = Repo.uid ?: return finish()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        cameraOn = video
        speaker = true // в группе удобнее громкая связь

        if (intent.getBooleanExtra(EXTRA_INCOMING, false) && callId.isNotEmpty()) {
            App.activeCallId = callId
            Notifier.clearCall(this, callId)
            phase = Phase.Ringing
            status = if (video) "Групповой видеозвонок" else "Групповой звонок"
            startRinging()
            watchWhileRinging()
            when (intent.getStringExtra(EXTRA_ACTION)) {
                ACTION_ACCEPT -> accept()
                ACTION_DECLINE -> decline()
            }
        } else {
            phase = Phase.Joining
            status = "Подключаюсь…"
            withPermissions { startOrJoin() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(EXTRA_CALL_ID) == callId) {
            when (intent.getStringExtra(EXTRA_ACTION)) {
                ACTION_ACCEPT -> accept()
                ACTION_DECLINE -> decline()
            }
        }
    }

    // ---------------------------------------------------------------- вход в звонок

    /** Кнопка звонка в группе: если звонок уже идёт — присоединяемся, иначе начинаем новый. */
    private fun startOrJoin() {
        lifecycleScope.launch {
            try {
                val existing = if (callId.isEmpty()) GroupCallSignal.active(chatId) else null
                if (callId.isEmpty() && existing == null) {
                    callId = GroupCallSignal.start(chatId, video)
                    startedByMe = true
                    App.activeCallId = callId
                    Push.notify(chatId, "gcall", callId, video)
                    Repo.logCall(chatId, video, 0, missed = false, group = true)
                    status = "Вызываю участников…"
                    ringTimeout = lifecycleScope.launch {
                        delay(60_000)
                        if (!anyoneJoined && phase != Phase.Ended) {
                            Push.notify(chatId, "gcancel", callId)
                            hangUp("Никто не ответил")
                        }
                    }
                } else if (existing != null) {
                    callId = existing.id
                    video = existing.video
                    cameraOn = video
                }
                join()
            } catch (e: Exception) {
                endWith("Не удалось позвонить: ${e.message}")
            }
        }
    }

    private fun accept() {
        if (phase != Phase.Ringing) return
        stopRinging()
        regs.forEach { it.remove() }
        regs.clear()
        phase = Phase.Joining
        status = "Подключаюсь…"
        withPermissions { lifecycleScope.launch { runCatching { join() }.onFailure { endWith("Ошибка: ${it.message}") } } }
    }

    private fun decline() = endWith("Отклонён")

    private suspend fun join() {
        val doc = GroupCallSignal.get(callId)
        if (doc == null || doc.ended || doc.participants.isEmpty()) {
            // отметка «идёт звонок» могла остаться после сбоя — убираем
            if (chatId.isNotEmpty()) runCatching { GroupCallSignal.end(callId, chatId) }
            return endWith("Звонок уже завершён")
        }
        if (me !in doc.participants && doc.participants.size >= GroupCallSignal.MAX_PARTICIPANTS) {
            return endWith("В звонке уже ${GroupCallSignal.MAX_PARTICIPANTS} человек — это максимум")
        }
        chatId = doc.chatId
        App.activeCallId = callId
        if (title.isBlank()) title = Repo.groupName(chatId) ?: "Группа"

        val s = GroupCallSession(this, video, sessionListener)
        session = s
        s.start(Push.iceServers())
        localTrack = s.localVideo
        setupAudio()

        GroupCallSignal.join(callId)
        regs += GroupCallSignal.links(callId) { link -> runOnUiThread { onLink(link) } }
        regs += GroupCallSignal.watch(callId) { d -> runOnUiThread { onDoc(d) } }
        phase = Phase.InCall
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
    }

    /** Пока звонит: если звонок закончился раньше, чем ответили, — перестаём звонить. */
    private fun watchWhileRinging() {
        regs += GroupCallSignal.watch(callId) { d ->
            runOnUiThread {
                if (phase == Phase.Ringing && (d == null || d.ended || d.participants.isEmpty())) endWith("Пропущенный звонок")
                if (d != null) chatId = d.chatId
            }
        }
        ringTimeout = lifecycleScope.launch {
            delay(60_000)
            if (phase == Phase.Ringing) endWith("Пропущенный звонок")
        }
    }

    // ---------------------------------------------------------------- участники и связи

    private fun onDoc(doc: GroupCallDoc?) {
        if (phase == Phase.Ended) return
        if (doc == null || doc.ended) return endWith("Звонок завершён")
        val now = doc.participants.filter { it != me }
        val gone = others.filter { it !in now }
        gone.forEach { dropPeer(it) }
        now.filter { it !in names }.forEach { uid ->
            names[uid] = "…"
            lifecycleScope.launch { names[uid] = runCatching { Repo.profile(uid)?.name }.getOrNull() ?: "Участник" }
        }
        others = now
        if (now.isNotEmpty() && !anyoneJoined) {
            anyoneJoined = true
            ringTimeout?.cancel()
        }
        status = if (now.isEmpty()) (if (anyoneJoined) "Все вышли — ждём участников" else "Вызываю участников…") else ""
        // соединяемся с новыми: предлагает тот, у кого uid меньше
        now.filter { me < it && session?.hasPeer(it) != true }.forEach { offerTo(it) }
    }

    private fun offerTo(uid: String) {
        val s = session ?: return
        val gen = UUID.randomUUID().toString().take(8)
        gens[uid] = gen
        listenIce(uid, gen)
        s.createOffer(uid) { sdp ->
            lifecycleScope.launch { runCatching { GroupCallSignal.offer(callId, uid, gen, sdp) } }
        }
    }

    private fun onLink(link: LinkDoc) {
        val s = session ?: return
        val other = if (link.from == me) link.to else link.from
        if (link.to == me) {
            // мне предлагают соединение (или переподключение — новое поколение)
            val offer = link.offer ?: return
            if (answeredGens[other] == link.gen) return
            answeredGens[other] = link.gen
            gens[other] = link.gen
            videos.remove(other)
            listenIce(other, link.gen)
            s.acceptOffer(other, offer) { answer ->
                lifecycleScope.launch { runCatching { GroupCallSignal.answer(callId, link.id, link.gen, answer) } }
            }
        } else if (link.answer != null && gens[other] == link.gen) {
            s.setAnswer(other, link.answer)
        }
    }

    private fun listenIce(uid: String, gen: String) {
        iceRegs.remove(uid)?.remove()
        iceRegs[uid] = GroupCallSignal.candidates(callId, GroupCallSignal.linkId(me, uid), uid, gen) { c ->
            session?.addRemoteCandidate(uid, c)
        }
    }

    private fun dropPeer(uid: String) {
        session?.close(uid)
        iceRegs.remove(uid)?.remove()
        videos.remove(uid)
        states.remove(uid)
        gens.remove(uid)
        answeredGens.remove(uid)
    }

    private val sessionListener = object : GroupCallSession.Listener {
        override fun onLocalCandidate(peer: String, c: Candidate) {
            val gen = gens[peer] ?: return
            GroupCallSignal.addCandidate(callId, GroupCallSignal.linkId(me, peer), gen, c)
        }
        override fun onRemoteVideo(peer: String, track: VideoTrack) { runOnUiThread { videos[peer] = track } }
        override fun onState(peer: String, state: PeerConnection.PeerConnectionState) {
            runOnUiThread {
                states[peer] = state
                // связь с одним участником оборвалась — переподключаемся (предлагает меньший uid)
                if (state == PeerConnection.PeerConnectionState.FAILED && peer in others && me < peer) {
                    lifecycleScope.launch {
                        delay(1500)
                        if (peer in others && phase == Phase.InCall) offerTo(peer)
                    }
                }
            }
        }
        override fun onError(peer: String?, message: String) {
            runOnUiThread { if (peer == null) endWith(message) else states.remove(peer) }
        }
        override fun onCameraProblem(message: String) {
            runOnUiThread {
                cameraOn = false
                Toast.makeText(this@GroupCallActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------- выход

    private fun hangUp(reason: String = "Вы вышли из звонка") {
        // сам выход из звонка в базе делает endWith
        if (phase == Phase.InCall && startedByMe && !anyoneJoined && callId.isNotEmpty()) Push.notify(chatId, "gcancel", callId)
        endWith(reason)
    }

    private fun endWith(text: String) {
        if (phase == Phase.Ended) return
        val wasIn = phase == Phase.InCall
        if (wasIn && callId.isNotEmpty()) {
            val id = callId
            val chat = chatId
            // экран закрывается — выходим из звонка в фоне
            CoroutineScope(Dispatchers.IO).launch { runCatching { GroupCallSignal.leave(id, chat) } }
        }
        phase = Phase.Ended
        status = text
        release()
        closeJob = lifecycleScope.launch {
            delay(1500)
            finish()
        }
    }

    private fun release() {
        stopRinging()
        ringTimeout?.cancel()
        regs.forEach { it.remove() }
        regs.clear()
        iceRegs.values.forEach { it.remove() }
        iceRegs.clear()
        videos.clear()
        localTrack = null
        session?.dispose()
        session = null
        restoreAudio()
        if (App.activeCallId == callId) App.activeCallId = null
    }

    override fun onDestroy() {
        if (phase != Phase.Ended) hangUp()
        release()
        super.onDestroy()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun withPermissions(then: () -> Unit) {
        val need = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filterNot { has(it) }
        if (need.isEmpty()) then() else {
            pendingAfterPermissions = then
            permissions.launch(need.toTypedArray())
        }
    }

    // ---------------------------------------------------------------- звук

    private val audio get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun startRinging() {
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
    }

    private fun applySpeaker() {
        val am = audio
        if (Build.VERSION.SDK_INT >= 31) {
            if (speaker) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = speaker
        }
    }

    private fun restoreAudio() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    // ---------------------------------------------------------------- интерфейс

    @Composable
    private fun CallUi() {
        Box(Modifier.fillMaxSize().background(Color(0xFF101318))) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            status.isNotEmpty() -> Text(status, color = Color(0xFFB8C0CC), fontSize = 14.sp)
                            phase == Phase.InCall -> Row {
                                Timer(startedAt)
                                Text("  ·  ${others.size + 1} в звонке", color = Color(0xFFB8C0CC), fontSize = 14.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (phase == Phase.InCall || phase == Phase.Joining) Tiles()
                    else Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Box(Modifier.size(120.dp).background(Color(0xFFF27A1A), CircleShape), Alignment.Center) {
                            Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                when (phase) {
                    Phase.Ringing -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(Icons.Filled.CallEnd, "Отклонить", Color(0xFFE5484D)) { decline() }
                        RoundButton(if (video) Icons.Filled.Videocam else Icons.Filled.Call, "Присоединиться", Color(0xFF30A46C)) { accept() }
                    }
                    Phase.Ended -> {}
                    else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        SmallToggle(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, muted) {
                            muted = !muted
                            session?.setMuted(muted)
                        }
                        SmallToggle(Icons.AutoMirrored.Filled.VolumeUp, speaker) {
                            speaker = !speaker
                            applySpeaker()
                        }
                        if (video) {
                            SmallToggle(if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, !cameraOn) {
                                cameraOn = !cameraOn
                                session?.setCameraOn(cameraOn)
                            }
                            SmallToggle(Icons.Filled.Cameraswitch, false) { session?.switchCamera { } }
                        }
                        IconButton(onClick = { hangUp() }, modifier = Modifier.size(60.dp).background(Color(0xFFE5484D), CircleShape)) {
                            Icon(Icons.Filled.CallEnd, "Выйти", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    /** Плитки участников: 1–2 — друг под другом, больше — сеткой в 2 столбца. */
    @Composable
    private fun Tiles() {
        val all = listOf(me) + others
        val rows = if (all.size <= 2) all.map { listOf(it) } else all.chunked(2)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { uid -> Tile(uid, Modifier.weight(1f).fillMaxSize()) }
                    if (row.size == 1 && rows.size > 1 && all.size > 2) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun Tile(uid: String, modifier: Modifier) {
        val s = session
        val isMe = uid == me
        val track = if (isMe) localTrack?.takeIf { cameraOn } else videos[uid]
        val name = if (isMe) "Вы" else names[uid] ?: "…"
        val state = states[uid]
        Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C2129))) {
            if (video && s != null && track != null) {
                VideoView(s, track, mirror = isMe && s.isFrontCamera, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Box(Modifier.size(72.dp).background(Color(0xFFF27A1A), CircleShape), Alignment.Center) {
                        Text(name.take(1).uppercase(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isMe && muted) {
                    Icon(Icons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(name, color = Color.White, fontSize = 13.sp, maxLines = 1)
                if (!isMe && state != PeerConnection.PeerConnectionState.CONNECTED) {
                    Text("  соединяю…", color = Color(0xFFB8C0CC), fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun Timer(since: Long) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
        val sec = ((now - since) / 1000).coerceAtLeast(0)
        Text(
            if (sec >= 3600) String.format("%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
            else String.format("%02d:%02d", sec / 60, sec % 60),
            color = Color(0xFFB8C0CC), fontSize = 14.sp,
        )
    }

    @Composable
    private fun VideoView(s: GroupCallSession, track: VideoTrack, mirror: Boolean, modifier: Modifier) {
        androidx.compose.runtime.key(track) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(s.egl.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
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
    private fun SmallToggle(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(52.dp).background(if (active) Color.White else Color(0x33FFFFFF), CircleShape),
        ) { Icon(icon, null, tint = if (active) Color(0xFF101318) else Color.White) }
    }

    companion object {
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_CHAT_ID = "groupChatId"
        const val EXTRA_INCOMING = "incoming"
        const val EXTRA_VIDEO = "video"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ACTION = "action"
        const val ACTION_ACCEPT = "accept"
        const val ACTION_DECLINE = "decline"

        /** Кнопка звонка в группе: начать новый или присоединиться к идущему. */
        fun start(ctx: Context, chatId: String, title: String, video: Boolean): Intent =
            Intent(ctx, GroupCallActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_VIDEO, video)

        /** Присоединиться к конкретному звонку (баннер «идёт звонок»). */
        fun join(ctx: Context, chatId: String, callId: String, title: String, video: Boolean): Intent =
            start(ctx, chatId, title, video).putExtra(EXTRA_CALL_ID, callId)

        fun incoming(ctx: Context, chatId: String, callId: String, title: String, video: Boolean, action: String? = null): Intent =
            Intent(ctx, GroupCallActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_INCOMING, true)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_VIDEO, video)
                .apply { if (action != null) putExtra(EXTRA_ACTION, action) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
