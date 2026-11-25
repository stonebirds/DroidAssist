package com.leefly.droidassist.webrtc

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.leefly.droidassist.DiagnosticLog
import com.leefly.droidassist.protocol.SignalEnvelope
import com.leefly.droidassist.protocol.SignalType
import com.leefly.droidassist.service.RemoteControlService
import com.leefly.droidassist.service.ScreenShareService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.*
import org.json.JSONObject
import java.nio.ByteBuffer

private data class VideoQuality(
    val adaptive: Boolean = true,
    val shortEdge: Int = 720,
    val fps: Int = 20,
)

enum class WebRtcConnectionStatus {
    IDLE,
    CAPTURE_READY,
    WAITING_FOR_CAPTURE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

object WebRtcManager {
    private const val TAG = "DroidAssistWebRTC"
    private lateinit var application: Application
    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localTrack: VideoTrack? = null
    private var remoteTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var dataChannel: DataChannel? = null
    private var capturePermission: Intent? = null
    private var sessionId: String? = null
    private var localDeviceId: String? = null
    private var peerDeviceId: String? = null
    private var controlledRole = false
    private var remoteDescriptionSet = false
    private var iceRestartAttempts = 0
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    private var signalSender: ((SignalEnvelope) -> Unit)? = null
    private var selectedQuality = VideoQuality()
    private var captureQuality = VideoQuality()
    private val _remoteVideoReady = MutableStateFlow(false)
    val remoteVideoReady: StateFlow<Boolean> = _remoteVideoReady
    private val _remoteAspectRatio = MutableStateFlow(9f / 16f)
    val remoteAspectRatio: StateFlow<Float> = _remoteAspectRatio
    private val _connectionStatus = MutableStateFlow(WebRtcConnectionStatus.IDLE)
    val connectionStatus: StateFlow<WebRtcConnectionStatus> = _connectionStatus
    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing

    @Synchronized
    fun initialize(context: Context, sender: (SignalEnvelope) -> Unit) {
        signalSender = sender
        if (::factory.isInitialized) return
        application = context.applicationContext as Application
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(application)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        DiagnosticLog.info("WebRTC", "PeerConnectionFactory initialized")
    }

    @Synchronized
    fun configureController(session: String, localDevice: String, controlledDevice: String) {
        resetPeerConnection()
        sessionId = session
        localDeviceId = localDevice
        peerDeviceId = controlledDevice
        controlledRole = false
        createPeerConnection()
        DiagnosticLog.info("WebRTC", "Controller role configured for session ${session.take(8)}")
        updateStatus(WebRtcConnectionStatus.CONNECTING, "Controller is waiting for approval and media")
    }

    @Synchronized
    fun configureControlled(session: String, localDevice: String, controllerDevice: String) {
        resetPeerConnection()
        sessionId = session
        localDeviceId = localDevice
        peerDeviceId = controllerDevice
        controlledRole = true
        DiagnosticLog.info("WebRTC", "Controlled role configured for session ${session.take(8)}")
        updateStatus(
            if (capturePermission == null) WebRtcConnectionStatus.WAITING_FOR_CAPTURE else WebRtcConnectionStatus.CONNECTING,
            "Controlled session configured",
        )
        maybeStartScreenOffer()
    }

    @Synchronized
    fun provideScreenCapturePermission(data: Intent) {
        capturePermission = Intent(data)
        DiagnosticLog.info("WebRTC", "Full-screen capture permission received")
        updateStatus(
            if (controlledRole && sessionId != null) WebRtcConnectionStatus.CONNECTING else WebRtcConnectionStatus.CAPTURE_READY,
            "Full-screen capture permission received",
        )
        maybeStartScreenOffer()
    }

    @Synchronized
    fun handleSignal(signal: SignalEnvelope) {
        if (signal.sessionId != sessionId || signal.fromDeviceId != peerDeviceId) return
        Log.i(TAG, "Received ${signal.type} for session ${signal.sessionId.take(8)}")
        DiagnosticLog.info("WebRTC", "Received ${signal.type} for session ${signal.sessionId.take(8)}")
        when (signal.type) {
            SignalType.OFFER -> {
                val sdp = signal.payload.jsonObject["sdp"]?.jsonPrimitive?.content ?: return
                val connection = peerConnection ?: createPeerConnection()
                connection?.setRemoteDescription(setObserver("set remote offer") {
                    markRemoteDescriptionSet(connection)
                    connection.createAnswer(createObserver("create answer") { answer ->
                        connection.setLocalDescription(setObserver("set local answer") {
                            sendSdp(SignalType.ANSWER, answer)
                        }, answer)
                    }, MediaConstraints())
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }

            SignalType.ANSWER -> {
                val sdp = signal.payload.jsonObject["sdp"]?.jsonPrimitive?.content ?: return
                peerConnection?.setRemoteDescription(
                    setObserver("set remote answer") {
                        peerConnection?.let(::markRemoteDescriptionSet)
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, sdp)
                )
            }

            SignalType.ICE -> {
                val payload = signal.payload.jsonObject
                val candidate = IceCandidate(
                    payload["sdpMid"]?.jsonPrimitive?.content,
                    payload["sdpMLineIndex"]?.jsonPrimitive?.int ?: return,
                    payload["candidate"]?.jsonPrimitive?.content ?: return,
                )
                val connection = peerConnection
                if (connection == null || !remoteDescriptionSet) {
                    pendingRemoteIceCandidates += candidate
                    Log.d(TAG, "Queued remote ICE candidate until SDP is set")
                } else {
                    connection.addIceCandidate(candidate)
                }
            }

            SignalType.HANGUP -> stopAfterRemoteHangup()
            else -> Unit
        }
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                DiagnosticLog.info("WebRTC", "First remote video frame rendered")
            }
            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                val rotated = rotation % 180 != 0
                val displayWidth = if (rotated) height else width
                val displayHeight = if (rotated) width else height
                if (displayWidth > 0 && displayHeight > 0) {
                    _remoteAspectRatio.value = displayWidth.toFloat() / displayHeight
                    DiagnosticLog.info("WebRTC", "Remote frame resolution ${displayWidth}x$displayHeight")
                }
            }
        })
        renderer.setEnableHardwareScaler(true)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        remoteTrack?.addSink(renderer)
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) {
        remoteTrack?.removeSink(renderer)
        renderer.release()
    }

    fun sendTap(x: Float, y: Float) = sendControl(
        JSONObject().put("type", "tap").put("x", x).put("y", y).toString()
    )

    fun sendLongPress(x: Float, y: Float, duration: Long) = sendControl(
        JSONObject().put("type", "long_press").put("x", x).put("y", y).put("duration", duration)
            .toString()
    )

    fun sendSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float, duration: Long) = sendControl(
        JSONObject().put("type", "swipe")
            .put("fromX", fromX).put("fromY", fromY).put("toX", toX).put("toY", toY)
            .put("duration", duration).toString()
    )

    fun sendGlobal(action: String) = sendControl(
        JSONObject().put("type", "global").put("action", action).toString()
    )

    @Synchronized
    fun sendVideoQuality(adaptive: Boolean, shortEdge: Int, fps: Int): Boolean {
        selectedQuality = sanitizedQuality(adaptive, shortEdge, fps)
        return sendControl(qualityCommand(selectedQuality))
    }

    @Synchronized
    fun hangUp() {
        send(SignalType.HANGUP, buildJsonObject {})
        val wasSharing = screenCapturer != null
        releaseScreenCaptureResources()
        stopSession()
        if (wasSharing) application.stopService(Intent(application, ScreenShareService::class.java))
    }

    @Synchronized
    fun stopScreenCapture() {
        send(SignalType.HANGUP, buildJsonObject {})
        releaseScreenCaptureResources()
        stopSession()
    }

    private fun releaseScreenCaptureResources() {
        _isScreenSharing.value = false
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose()
        screenCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localTrack?.dispose()
        localTrack = null
        videoSource?.dispose()
        videoSource = null
        capturePermission = null
    }

    private fun stopAfterRemoteHangup() {
        val wasSharing = screenCapturer != null
        releaseScreenCaptureResources()
        stopSession()
        if (wasSharing) application.stopService(Intent(application, ScreenShareService::class.java))
    }

    @Synchronized
    fun stopSession() {
        remoteTrack = null
        _remoteVideoReady.value = false
        resetPeerConnection()
        sessionId = null
        localDeviceId = null
        peerDeviceId = null
        controlledRole = false
        updateStatus(WebRtcConnectionStatus.IDLE, "Session stopped")
    }

    private fun maybeStartScreenOffer() {
        if (!controlledRole || sessionId == null || capturePermission == null || screenCapturer != null) return
        updateStatus(WebRtcConnectionStatus.CONNECTING, "Starting screen capture and creating offer")
        val connection = createPeerConnection() ?: return
        val capturer =
            ScreenCapturerAndroid(capturePermission, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "Screen capture stopped by system")
                    DiagnosticLog.warn("Capture", "Screen capture stopped by system")
                    stopScreenCapture()
                }
            })
        val helper = SurfaceTextureHelper.create("DroidAssistCapture", eglBase.eglBaseContext)
        val source = factory.createVideoSource(true)
        capturer.initialize(helper, application, source.capturerObserver)
        val quality = captureQuality
        val (width, height) = captureDimensions(quality.shortEdge)
        try {
            capturer.startCapture(width, height, quality.fps)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start screen capture", error)
            DiagnosticLog.error("Capture", "Unable to start screen capture", error)
            capturer.dispose()
            helper.dispose()
            source.dispose()
            capturePermission = null
            updateStatus(WebRtcConnectionStatus.FAILED, "Screen capture failed")
            return
        }
        val track = factory.createVideoTrack("droidassist-screen", source)
        videoSender = connection.addTrack(track, listOf("droidassist-screen"))
        updateSenderParameters(quality)
        bindDataChannel(connection.createDataChannel("control", DataChannel.Init()))
        screenCapturer = capturer
        surfaceTextureHelper = helper
        videoSource = source
        localTrack = track
        _isScreenSharing.value = true
        DiagnosticLog.info(
            "Capture",
            "Screen capture started at ${width}x$height, ${quality.fps} fps (${qualityLabel(quality)})",
        )
        DiagnosticLog.info("Capture", "Protected camera and security surfaces may appear black by Android design")
        createAndSendOffer(connection, iceRestart = false)
    }

    private fun createPeerConnection(): PeerConnection? {
        peerConnection?.let { return it }
        val config = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19305").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory.createPeerConnection(config, peerObserver)?.also {
            peerConnection = it
            DiagnosticLog.info("WebRTC", "Peer connection created with 3 STUN endpoints")
        } ?: run {
            DiagnosticLog.error("WebRTC", "Unable to create peer connection")
            null
        }
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "ICE state: $state")
            DiagnosticLog.info("WebRTC", "ICE connection state: $state")
            when (state) {
                PeerConnection.IceConnectionState.CHECKING -> updateStatus(WebRtcConnectionStatus.CONNECTING, "ICE checking")
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> updateStatus(WebRtcConnectionStatus.CONNECTED, "ICE connected")
                PeerConnection.IceConnectionState.DISCONNECTED -> updateStatus(WebRtcConnectionStatus.DISCONNECTED, "ICE disconnected")
                PeerConnection.IceConnectionState.FAILED -> {
                    if (controlledRole && iceRestartAttempts < 2) restartIce()
                    else updateStatus(WebRtcConnectionStatus.FAILED, "ICE failed")
                }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            DiagnosticLog.info("WebRTC", "ICE gathering state: $state")
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            DiagnosticLog.info("WebRTC", "Local ICE candidate gathered")
            send(
                SignalType.ICE,
                buildJsonObject {
                    put("sdpMid", candidate.sdpMid ?: "")
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                },
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) {
            bindDataChannel(channel)
        }

        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            val track = receiver.track() as? VideoTrack ?: return
            remoteTrack = track
            _remoteVideoReady.value = true
            Log.i(TAG, "Remote video track received")
            DiagnosticLog.info("WebRTC", "Remote video track received")
        }
    }

    @Synchronized
    private fun restartIce() {
        val connection = peerConnection ?: return
        iceRestartAttempts += 1
        updateStatus(WebRtcConnectionStatus.CONNECTING, "ICE restart $iceRestartAttempts/2")
        DiagnosticLog.warn("WebRTC", "ICE failed; starting automatic restart $iceRestartAttempts/2")
        connection.restartIce()
        createAndSendOffer(connection, iceRestart = true)
    }

    private fun createAndSendOffer(connection: PeerConnection, iceRestart: Boolean) {
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        connection.createOffer(createObserver(if (iceRestart) "create ICE restart offer" else "create offer") { offer ->
            connection.setLocalDescription(setObserver("set local offer") {
                Log.i(TAG, "Local screen offer is ready")
                DiagnosticLog.info("WebRTC", if (iceRestart) "ICE restart offer is ready" else "Local screen offer is ready")
                sendSdp(SignalType.OFFER, offer)
            }, offer)
        }, constraints)
    }

    private fun sendSdp(type: SignalType, description: SessionDescription) = send(
        type,
        buildJsonObject { put("sdp", description.description) },
    )

    private fun send(type: SignalType, payload: kotlinx.serialization.json.JsonElement) {
        val session = sessionId ?: return
        val from = localDeviceId ?: return
        val to = peerDeviceId ?: return
        Log.i(TAG, "Sending $type for session ${session.take(8)}")
        DiagnosticLog.info("WebRTC", "Sending $type for session ${session.take(8)}")
        signalSender?.invoke(SignalEnvelope(session, from, to, type, payload))
    }

    private fun bindDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                Log.i(TAG, "Control channel: ${channel.state()}")
                if (!controlledRole && channel.state() == DataChannel.State.OPEN) {
                    sendControl(qualityCommand(selectedQuality))
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!controlledRole || buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val command = bytes.toString(Charsets.UTF_8)
                val json = runCatching { JSONObject(command) }.getOrNull()
                if (json?.optString("type") == "quality") {
                    applyVideoQuality(
                        adaptive = json.optString("mode") == "adaptive",
                        shortEdge = json.optInt("resolution", 1080),
                        fps = json.optInt("fps", 30),
                    )
                } else {
                    RemoteControlService.execute(command)
                }
            }
        })
    }

    @Synchronized
    private fun applyVideoQuality(adaptive: Boolean, shortEdge: Int, fps: Int) {
        val quality = sanitizedQuality(adaptive, shortEdge, fps)
        captureQuality = quality
        val capturer = screenCapturer ?: return
        val (width, height) = captureDimensions(quality.shortEdge)
        runCatching {
            capturer.changeCaptureFormat(width, height, quality.fps)
            updateSenderParameters(quality)
        }.onSuccess {
            DiagnosticLog.info(
                "Capture",
                "Video quality changed to ${width}x$height, ${quality.fps} fps (${qualityLabel(quality)})",
            )
        }.onFailure { error ->
            DiagnosticLog.error("Capture", "Unable to change video quality", error)
        }
    }

    private fun updateSenderParameters(quality: VideoQuality) {
        val sender = videoSender ?: return
        val parameters = sender.parameters
        parameters.degradationPreference = if (quality.adaptive) {
            RtpParameters.DegradationPreference.BALANCED
        } else {
            RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        }
        val maxBitrate = when (quality.shortEdge) {
            720 -> 2_500_000
            else -> 5_000_000
        }
        parameters.encodings.forEach { encoding ->
            encoding.minBitrateBps = if (quality.adaptive) 250_000 else null
            encoding.maxBitrateBps = maxBitrate
            encoding.maxFramerate = quality.fps
        }
        if (!sender.setParameters(parameters)) {
            DiagnosticLog.warn("Capture", "WebRTC rejected updated sender parameters")
        }
    }

    private fun captureDimensions(shortEdge: Int): Pair<Int, Int> {
        val metrics = application.resources.displayMetrics
        val deviceShortEdge = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        val scale = minOf(1f, shortEdge.toFloat() / deviceShortEdge)
        val width = ((metrics.widthPixels * scale).toInt().coerceAtLeast(2)) and -2
        val height = ((metrics.heightPixels * scale).toInt().coerceAtLeast(2)) and -2
        return width to height
    }

    private fun sanitizedQuality(adaptive: Boolean, shortEdge: Int, fps: Int) = VideoQuality(
        adaptive = adaptive,
        shortEdge = if (shortEdge == 720) 720 else 1080,
        fps = fps.takeIf { it in setOf(10, 15, 20, 30) } ?: 30,
    )

    private fun qualityCommand(quality: VideoQuality) = JSONObject()
        .put("type", "quality")
        .put("mode", if (quality.adaptive) "adaptive" else "manual")
        .put("resolution", quality.shortEdge)
        .put("fps", quality.fps)
        .toString()

    private fun qualityLabel(quality: VideoQuality): String =
        if (quality.adaptive) "adaptive" else "manual ${quality.shortEdge}p"

    private fun sendControl(command: String): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return channel.send(
            DataChannel.Buffer(
                ByteBuffer.wrap(command.toByteArray(Charsets.UTF_8)),
                false
            )
        )
    }

    private fun resetPeerConnection() {
        dataChannel?.close()
        dataChannel?.dispose()
        dataChannel = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        videoSender = null
        remoteDescriptionSet = false
        pendingRemoteIceCandidates.clear()
        iceRestartAttempts = 0
    }

    private fun markRemoteDescriptionSet(connection: PeerConnection) {
        synchronized(this) {
            remoteDescriptionSet = true
            pendingRemoteIceCandidates.forEach(connection::addIceCandidate)
            if (pendingRemoteIceCandidates.isNotEmpty()) {
                Log.i(TAG, "Applied ${pendingRemoteIceCandidates.size} queued remote ICE candidates")
            }
            pendingRemoteIceCandidates.clear()
        }
    }

    private fun updateStatus(status: WebRtcConnectionStatus, detail: String) {
        _connectionStatus.value = status
        Log.i(TAG, "Connection status: $status ($detail)")
        DiagnosticLog.info("WebRTC", "Connection status: $status ($detail)")
    }

    private fun setObserver(label: String, onSetSuccess: (() -> Unit)? = null) =
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onSetSuccess() {
                onSetSuccess?.invoke()
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "$label failed: $error")
                DiagnosticLog.error("WebRTC", "$label failed: $error")
            }

            override fun onSetFailure(error: String) {
                Log.e(TAG, "$label failed: $error")
                DiagnosticLog.error("WebRTC", "$label failed: $error")
            }
        }

    private fun createObserver(label: String, onCreateSuccess: (SessionDescription) -> Unit) =
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) =
                onCreateSuccess(description)

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "$label failed: $error")
                DiagnosticLog.error("WebRTC", "$label failed: $error")
            }

            override fun onSetFailure(error: String) {
                Log.e(TAG, "$label failed: $error")
                DiagnosticLog.error("WebRTC", "$label failed: $error")
            }
        }
}
