package com.remotephone.agent.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.remotephone.agent.config.AgentConfig
import com.remotephone.agent.files.FileManager
import com.remotephone.agent.service.RemoteAccessibilityService
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class WebRtcManager(
    private val context: Context,
    private val config: AgentConfig,
    private val signalingCallback: SignalingCallback
) {

    interface SignalingCallback {
        fun sendOffer(sdp: String)
        fun sendAnswer(sdp: String)
        fun sendIceCandidate(candidate: IceCandidate)
    }

    private val gson = Gson()
    private val eglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    // Video & Audio
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var projectionIntent: Intent? = null
    private var currentMode: String = "screen"

    companion object {
        private const val TAG = "WebRtcManager"
        private const val VIDEO_WIDTH = 720
        private const val VIDEO_HEIGHT = 1280
        private const val VIDEO_FPS = 30
    }

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true, // enableIntelVp8Encoder
            true  // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
    }

    fun setProjectionIntent(intent: Intent) {
        this.projectionIntent = intent
    }

    fun createPeerConnection(): PeerConnection? {
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())

        if (config.turnServerUrl.isNotEmpty()) {
            val turnBuilder = PeerConnection.IceServer.builder(config.turnServerUrl)
            if (config.turnUsername.isNotEmpty()) {
                turnBuilder.setUsername(config.turnUsername)
                turnBuilder.setPassword(config.turnCredential)
            }
            iceServers.add(turnBuilder.createIceServer())
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection State: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    signalingCallback.sendIceCandidate(candidate)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dc: DataChannel?) {
                Log.d(TAG, "DataChannel received from remote")
                setupDataChannel(dc)
            }

            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        // Setup local media tracks
        setupMediaTracks()

        return peerConnection
    }

    private fun setupMediaTracks() {
        val factory = peerConnectionFactory ?: return

        // Audio
        val audioConstraints = MediaConstraints()
        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
        peerConnection?.addTrack(audioTrack, listOf("ARDAMS"))

        // Video
        startStreamMode(currentMode)
    }

    fun startStreamMode(mode: String) {
        currentMode = mode
        stopCapturer()

        val factory = peerConnectionFactory ?: return
        val helper = surfaceTextureHelper ?: return

        when (mode) {
            "screen" -> {
                val intent = projectionIntent
                if (intent != null) {
                    videoCapturer = ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.d(TAG, "MediaProjection stopped")
                        }
                    })
                }
            }
            "camera_back" -> {
                videoCapturer = createCameraCapturer(isFront = false)
            }
            "camera_front" -> {
                videoCapturer = createCameraCapturer(isFront = true)
            }
            "none" -> {
                // Audio only
                return
            }
        }

        val capturer = videoCapturer
        if (capturer != null) {
            videoSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, context, videoSource?.capturerObserver)
            capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

            if (videoTrack == null) {
                videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)
                peerConnection?.addTrack(videoTrack, listOf("ARDAMS"))
            } else {
                videoTrack?.setEnabled(true)
            }
        }
    }

    private fun createCameraCapturer(isFront: Boolean): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (isFront && enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
            if (!isFront && enumerator.isBackFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    private fun stopCapturer() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupDataChannel(dc: DataChannel?) {
        dataChannel = dc
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel state: ${dataChannel?.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val jsonStr = String(bytes, StandardCharsets.UTF_8)
                handleCommandMessage(jsonStr)
            }
        })
    }

    fun sendDataChannelMessage(text: String) {
        val dc = dataChannel
        if (dc != null && dc.state() == DataChannel.ChannelState.OPEN) {
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8)), false)
            dc.send(buffer)
        }
    }

    private fun handleCommandMessage(jsonStr: String) {
        try {
            val json = gson.fromJson(jsonStr, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return

            when (type) {
                "touch" -> {
                    val action = json.get("action")?.asString ?: "tap"
                    val x = json.get("x")?.asFloat ?: 0f
                    val y = json.get("y")?.asFloat ?: 0f
                    if (action == "tap") {
                        RemoteAccessibilityService.instance?.performTap(x, y)
                    }
                }
                "swipe" -> {
                    val x1 = json.get("x1")?.asFloat ?: 0f
                    val y1 = json.get("y1")?.asFloat ?: 0f
                    val x2 = json.get("x2")?.asFloat ?: 0f
                    val y2 = json.get("y2")?.asFloat ?: 0f
                    val duration = json.get("duration")?.asLong ?: 300L
                    RemoteAccessibilityService.instance?.performSwipe(x1, y1, x2, y2, duration)
                }
                "key" -> {
                    val code = json.get("code")?.asString ?: ""
                    when (code) {
                        "back" -> RemoteAccessibilityService.instance?.performBack()
                        "home" -> RemoteAccessibilityService.instance?.performHome()
                        "recents" -> RemoteAccessibilityService.instance?.performRecents()
                        "lock" -> RemoteAccessibilityService.instance?.performLockScreen()
                        "wake" -> RemoteAccessibilityService.instance?.wakeUpScreen()
                    }
                }
                "switch_stream" -> {
                    val target = json.get("target")?.asString ?: "screen"
                    startStreamMode(target)
                }
                "file_list" -> {
                    val path = json.get("path")?.asString
                    val resp = FileManager.listDirectoryJson(path)
                    sendDataChannelMessage(resp)
                }
                "file_download_chunk" -> {
                    val path = json.get("path")?.asString ?: ""
                    val offset = json.get("offset")?.asLong ?: 0L
                    val resp = FileManager.readFileChunk(path, offset)
                    if (resp != null) {
                        sendDataChannelMessage(gson.toJson(resp))
                    }
                }
                "file_upload_chunk" -> {
                    val path = json.get("path")?.asString ?: ""
                    val offset = json.get("offset")?.asLong ?: 0L
                    val dataBase64 = json.get("data")?.asString ?: ""
                    val success = FileManager.writeFileChunk(path, offset, dataBase64)
                    val ack = JsonObject().apply {
                        addProperty("type", "file_upload_ack")
                        addProperty("path", path)
                        addProperty("offset", offset)
                        addProperty("success", success)
                    }
                    sendDataChannelMessage(ack.toString())
                }
                "file_delete" -> {
                    val path = json.get("path")?.asString ?: ""
                    val success = FileManager.deleteFile(path)
                    val ack = JsonObject().apply {
                        addProperty("type", "file_delete_ack")
                        addProperty("path", path)
                        addProperty("success", success)
                    }
                    sendDataChannelMessage(ack.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling DataChannel message", e)
        }
    }

    fun handleRemoteOffer(offerSdp: String) {
        val sdp = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                val constraints = MediaConstraints()
                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (answerDesc != null) {
                            peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    signalingCallback.sendAnswer(answerDesc.description)
                                }
                            }, answerDesc)
                        }
                    }
                }, constraints)
            }
        }, sdp)
    }

    fun handleRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdpCandidate: String) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdpCandidate)
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        stopCapturer()
        dataChannel?.close()
        peerConnection?.close()
        videoSource?.dispose()
        audioSource?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnectionFactory?.dispose()
        eglBase.release()
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "SDP error: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "SDP set error: $error")
        }
    }
}
