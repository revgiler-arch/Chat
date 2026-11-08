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
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Одно соединение WebRTC: микрофон, камера, обмен «предложением/ответом» и сетевыми кандидатами.
 * Все колбэки [Listener] приходят из потоков WebRTC — UI-код должен сам перейти в главный поток.
 */
class CallSession(
    private val ctx: Context,
    private val video: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onLocalCandidate(c: Candidate)
        fun onRemoteVideo(track: VideoTrack)
        fun onState(state: PeerConnection.PeerConnectionState)
        fun onError(message: String)
        /** Камера не включилась; звонок продолжается со звуком. */
        fun onCameraProblem(message: String)
    }

    val egl: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    var localVideo: VideoTrack? = null
        private set
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var frontCamera = true
    private val pendingRemote = mutableListOf<IceCandidate>()
    private var remoteSet = false
    private var answerApplied = false
    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            listener.onCameraProblem("Не удалось включить камеру")
        }
        override fun onCameraDisconnected() {}
        override fun onCameraFreezed(errorDescription: String?) {}
        override fun onCameraOpening(cameraName: String?) {}
        override fun onFirstFrameAvailable() {}
        override fun onCameraClosed() {}
    }

    init {
        initOnce(ctx)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun start(servers: List<IceServerInfo>) {
        val ice = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = factory.createPeerConnection(config, observer) ?: run {
            listener.onError("Не удалось создать соединение")
            return
        }

        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("audio0", audioSource).also { pc?.addTrack(it, listOf("s0")) }

        if (video) startCamera()
    }

    private fun startCamera() {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onCameraProblem("Нет доступа к камере: звонок только со звуком")
            return
        }
        runCatching { openCamera() }.onFailure { listener.onCameraProblem("Не удалось включить камеру") }
    }

    private fun openCamera() {
        val enumerator = Camera2Enumerator(ctx)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull()
            ?: error("На устройстве нет камеры")
        frontCamera = enumerator.isFrontFacing(name)
        val cap = enumerator.createCapturer(name, cameraEvents) ?: error("Камера недоступна")
        val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
        val source = factory.createVideoSource(false)
        cap.initialize(helper, ctx, source.capturerObserver)
        cap.startCapture(1280, 720, 30)
        capturer = cap
        surfaceHelper = helper
        videoSource = source
        localVideo = factory.createVideoTrack("video0", source).also { pc?.addTrack(it, listOf("s0")) }
    }

    // ---------------------------------------------------------------- предложение / ответ

    fun createOffer(onReady: (String) -> Unit) {
        val p = pc ?: return
        p.createOffer(sdp(onCreate = { desc ->
            p.setLocalDescription(sdp(onSet = { onReady(desc.description) }), desc)
        }), constraints())
    }

    fun acceptOffer(offerSdp: String, onAnswer: (String) -> Unit) {
        val p = pc ?: return
        p.setRemoteDescription(sdp(onSet = {
            markRemoteSet()
            p.createAnswer(sdp(onCreate = { desc ->
                p.setLocalDescription(sdp(onSet = { onAnswer(desc.description) }), desc)
            }), constraints())
        }), SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    fun setAnswer(answerSdp: String) {
        val p = pc ?: return
        if (answerApplied) return // документ звонка обновляется несколько раз
        answerApplied = true
        p.setRemoteDescription(sdp(onSet = { markRemoteSet() }), SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    /** Кандидаты могут прийти раньше ответа — тогда копим и добавляем после. */
    fun addRemoteCandidate(c: Candidate) {
        val ice = IceCandidate(c.sdpMid, c.sdpMLineIndex, c.sdp)
        synchronized(pendingRemote) {
            if (!remoteSet) { pendingRemote += ice; return }
        }
        pc?.addIceCandidate(ice)
    }

    private fun markRemoteSet() {
        val list = synchronized(pendingRemote) {
            remoteSet = true
            pendingRemote.toList().also { pendingRemote.clear() }
        }
        list.forEach { pc?.addIceCandidate(it) }
    }

    private fun constraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false"))
    }

    // ---------------------------------------------------------------- управление

    fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    fun setCameraOn(on: Boolean) {
        localVideo?.setEnabled(on)
        runCatching { if (on) capturer?.startCapture(1280, 720, 30) else capturer?.stopCapture() }
    }

    fun switchCamera(onDone: (front: Boolean) -> Unit) {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                frontCamera = isFrontCamera
                onDone(isFrontCamera)
            }
            override fun onCameraSwitchError(errorDescription: String?) {}
        })
    }

    val isFrontCamera get() = frontCamera

    fun attachLocal(view: SurfaceViewRenderer) {
        localVideo?.addSink(view)
    }

    fun dispose() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { localVideo?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
        runCatching { factory.dispose() }
        runCatching { egl.release() }
    }

    // ---------------------------------------------------------------- колбэки WebRTC

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            listener.onLocalCandidate(Candidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            listener.onState(newState)
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) listener.onRemoteVideo(track)
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

    private fun sdp(
        onCreate: (SessionDescription) -> Unit = {},
        onSet: () -> Unit = {},
    ) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = onCreate(desc)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String?) = listener.onError("Ошибка соединения: $error")
        override fun onSetFailure(error: String?) = listener.onError("Ошибка соединения: $error")
    }

    companion object {
        @Volatile
        private var initialized = false

        private fun initOnce(ctx: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions(),
                )
                initialized = true
            }
        }
    }
}
