package com.revgiler.messenger.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.revgiler.messenger.data.Candidate
import com.revgiler.messenger.data.IceServerInfo
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Групповой звонок: один микрофон и одна камера, отдельное соединение WebRTC с каждым участником.
 * Колбэки [Listener] приходят из потоков WebRTC.
 */
class GroupCallSession(private val ctx: Context, private val video: Boolean, private val listener: Listener) {

    interface Listener {
        fun onLocalCandidate(peer: String, c: Candidate)
        fun onRemoteVideo(peer: String, track: VideoTrack)
        fun onState(peer: String, state: PeerConnection.PeerConnectionState)
        fun onError(peer: String?, message: String)
        fun onCameraProblem(message: String)
    }

    private class Peer(val pc: PeerConnection) {
        val pending = mutableListOf<IceCandidate>()
        var remoteSet = false
        var answerApplied = false
    }

    val egl: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var ice: List<PeerConnection.IceServer> = emptyList()
    private val peers = HashMap<String, Peer>()
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    var localVideo: VideoTrack? = null
        private set
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    var isFrontCamera = true
        private set

    init {
        CallSession.ensureInitialized(ctx)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun start(servers: List<IceServerInfo>) {
        ice = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("audio0", audioSource)
        if (video) startCamera()
    }

    private fun startCamera() {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onCameraProblem("Нет доступа к камере: вы в звонке только со звуком")
            return
        }
        runCatching {
            val enumerator = Camera2Enumerator(ctx)
            val names = enumerator.deviceNames
            val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: error("Нет камеры")
            isFrontCamera = enumerator.isFrontFacing(name)
            val cap = enumerator.createCapturer(name, null) ?: error("Камера недоступна")
            val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
            val source = factory.createVideoSource(false)
            cap.initialize(helper, ctx, source.capturerObserver)
            // в группе картинка меньше — бережём сеть и батарею
            cap.startCapture(640, 480, 24)
            capturer = cap
            surfaceHelper = helper
            videoSource = source
            localVideo = factory.createVideoTrack("video0", source)
        }.onFailure { listener.onCameraProblem("Не удалось включить камеру") }
    }

    // ---------------------------------------------------------------- соединения с участниками

    @Synchronized
    private fun newPeer(uid: String): Peer? {
        close(uid)
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(config, observer(uid)) ?: run {
            listener.onError(uid, "Не удалось создать соединение")
            return null
        }
        audioTrack?.let { pc.addTrack(it, listOf("s0")) }
        localVideo?.let { pc.addTrack(it, listOf("s0")) }
        return Peer(pc).also { peers[uid] = it }
    }

    fun hasPeer(uid: String) = synchronized(this) { uid in peers }

    fun createOffer(uid: String, onReady: (String) -> Unit) {
        val p = newPeer(uid) ?: return
        p.pc.createOffer(sdp(uid, onCreate = { desc ->
            p.pc.setLocalDescription(sdp(uid, onSet = { onReady(desc.description) }), desc)
        }), constraints())
    }

    fun acceptOffer(uid: String, offer: String, onAnswer: (String) -> Unit) {
        val p = newPeer(uid) ?: return
        p.pc.setRemoteDescription(sdp(uid, onSet = {
            markRemoteSet(p)
            p.pc.createAnswer(sdp(uid, onCreate = { desc ->
                p.pc.setLocalDescription(sdp(uid, onSet = { onAnswer(desc.description) }), desc)
            }), constraints())
        }), SessionDescription(SessionDescription.Type.OFFER, offer))
    }

    fun setAnswer(uid: String, answer: String) {
        val p = synchronized(this) { peers[uid] } ?: return
        if (p.answerApplied) return
        p.answerApplied = true
        p.pc.setRemoteDescription(sdp(uid, onSet = { markRemoteSet(p) }), SessionDescription(SessionDescription.Type.ANSWER, answer))
    }

    fun addRemoteCandidate(uid: String, c: Candidate) {
        val p = synchronized(this) { peers[uid] } ?: return
        val ice = IceCandidate(c.sdpMid, c.sdpMLineIndex, c.sdp)
        synchronized(p.pending) {
            if (!p.remoteSet) { p.pending += ice; return }
        }
        p.pc.addIceCandidate(ice)
    }

    private fun markRemoteSet(p: Peer) {
        val list = synchronized(p.pending) {
            p.remoteSet = true
            p.pending.toList().also { p.pending.clear() }
        }
        list.forEach { p.pc.addIceCandidate(it) }
    }

    @Synchronized
    fun close(uid: String) {
        val p = peers.remove(uid) ?: return
        runCatching { p.pc.close() }
        runCatching { p.pc.dispose() }
    }

    private fun constraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false"))
    }

    // ---------------------------------------------------------------- управление

    fun setMuted(muted: Boolean) { audioTrack?.setEnabled(!muted) }

    fun setCameraOn(on: Boolean) {
        localVideo?.setEnabled(on)
        runCatching { if (on) capturer?.startCapture(640, 480, 24) else capturer?.stopCapture() }
    }

    fun switchCamera(onDone: (Boolean) -> Unit) {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) { isFrontCamera = front; onDone(front) }
            override fun onCameraSwitchError(errorDescription: String?) {}
        })
    }

    fun dispose() {
        synchronized(this) { peers.keys.toList() }.forEach { close(it) }
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { localVideo?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { factory.dispose() }
        runCatching { egl.release() }
    }

    private fun observer(uid: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) =
            listener.onLocalCandidate(uid, Candidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = listener.onState(uid, newState)
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) listener.onRemoteVideo(uid, track)
        }
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dataChannel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun sdp(uid: String, onCreate: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = onCreate(desc)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String?) = listener.onError(uid, "Ошибка соединения: $error")
        override fun onSetFailure(error: String?) = listener.onError(uid, "Ошибка соединения: $error")
    }
}
