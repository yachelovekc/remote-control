package com.remotephone.agent.signaling

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.remotephone.agent.config.AgentConfig
import com.remotephone.agent.service.RemoteAccessibilityService
import com.remotephone.agent.webrtc.WebRtcManager
import okhttp3.*
import org.webrtc.IceCandidate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SupabaseSignaling(
    private val config: AgentConfig,
    private val webRtcManagerProvider: () -> WebRtcManager?
) : WebRtcManager.SignalingCallback {

    companion object {
        private const val TAG = "SupabaseSignaling"
        private const val HEARTBEAT_INTERVAL_MS = 25000L
        private const val RECONNECT_DELAY_MS = 5000L
    }

    private val gson = Gson()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refCounter = AtomicInteger(1)
    private var isConnected = false
    private var shouldReconnect = true

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val channelTopic: String
        get() = "realtime:remotephone_${config.deviceId}"

    fun connect() {
        if (!config.isConfigured) {
            Log.w(TAG, "Config is incomplete. Cannot connect.")
            return
        }

        shouldReconnect = true
        disconnect()

        val rawUrl = config.supabaseUrl
        val host = rawUrl.replace("https://", "").replace("http://", "").removeSuffix("/")
        val wsUrl = "wss://$host/realtime/v1/websocket?apikey=${config.supabaseAnonKey}&vsn=2.0.0"

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to Supabase")
                isConnected = true
                joinChannel()
                mainHandler.post(heartbeatRunnable)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                onDisconnected()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                onDisconnected()
            }
        })
    }

    private fun onDisconnected() {
        isConnected = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        if (shouldReconnect) {
            mainHandler.postDelayed({ connect() }, RECONNECT_DELAY_MS)
        }
    }

    private fun joinChannel() {
        val ref = refCounter.incrementAndGet().toString()
        val joinMsg = JsonObject().apply {
            addProperty("topic", channelTopic)
            addProperty("event", "phx_join")
            val payload = JsonObject().apply {
                val cfg = JsonObject().apply {
                    val bcast = JsonObject().apply {
                        addProperty("self", false)
                    }
                    add("broadcast", bcast)
                }
                add("config", cfg)
            }
            add("payload", payload)
            addProperty("ref", ref)
        }
        webSocket?.send(gson.toJson(joinMsg))
    }

    private fun sendHeartbeat() {
        if (!isConnected) return
        val ref = refCounter.incrementAndGet().toString()
        val hb = JsonObject().apply {
            addProperty("topic", "phoenix")
            addProperty("event", "heartbeat")
            add("payload", JsonObject())
            addProperty("ref", hbRef(ref))
        }
        webSocket?.send(gson.toJson(hb))
    }

    private fun hbRef(r: String) = "hb_$r"

    private fun handleIncomingMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val event = json.get("event")?.asString ?: return

            if (event == "broadcast") {
                val payloadOuter = json.getAsJsonObject("payload") ?: return
                val eventType = payloadOuter.get("event")?.asString ?: ""
                if (eventType == "signal") {
                    val signalData = payloadOuter.getAsJsonObject("payload") ?: return
                    processSignalData(signalData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    private fun processSignalData(data: JsonObject) {
        // Authenticate secret
        val password = data.get("password")?.asString ?: ""
        if (config.accessPassword.isNotEmpty() && password != config.accessPassword) {
            Log.w(TAG, "Rejected unauthorized signal attempt.")
            return
        }

        val action = data.get("action")?.asString ?: return
        val rtc = webRtcManagerProvider() ?: return

        when (action) {
            "wake" -> {
                RemoteAccessibilityService.instance?.wakeUpScreen()
            }
            "offer" -> {
                val sdp = data.get("sdp")?.asString ?: return
                rtc.createPeerConnection()
                rtc.handleRemoteOffer(sdp)
            }
            "candidate" -> {
                val sdpMid = data.get("sdpMid")?.asString ?: ""
                val sdpMLineIndex = data.get("sdpMLineIndex")?.asInt ?: 0
                val candidateStr = data.get("candidate")?.asString ?: return
                rtc.handleRemoteIceCandidate(sdpMid, sdpMLineIndex, candidateStr)
            }
        }
    }

    override fun sendOffer(sdp: String) {
        sendBroadcast(JsonObject().apply {
            addProperty("action", "offer")
            addProperty("sdp", sdp)
        })
    }

    override fun sendAnswer(sdp: String) {
        sendBroadcast(JsonObject().apply {
            addProperty("action", "answer")
            addProperty("sdp", sdp)
        })
    }

    override fun sendIceCandidate(candidate: IceCandidate) {
        sendBroadcast(JsonObject().apply {
            addProperty("action", "candidate")
            addProperty("sdpMid", candidate.sdpMid)
            addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
            addProperty("candidate", candidate.sdp)
        })
    }

    private fun sendBroadcast(signalPayload: JsonObject) {
        val ref = refCounter.incrementAndGet().toString()
        signalPayload.addProperty("password", config.accessPassword)
        signalPayload.addProperty("deviceId", config.deviceId)

        val broadcastMsg = JsonObject().apply {
            addProperty("topic", channelTopic)
            addProperty("event", "broadcast")
            val payload = JsonObject().apply {
                addProperty("type", "broadcast")
                addProperty("event", "signal")
                add("payload", signalPayload)
            }
            add("payload", payload)
            addProperty("ref", ref)
        }

        webSocket?.send(gson.toJson(broadcastMsg))
    }

    fun disconnect() {
        shouldReconnect = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        isConnected = false
    }
}
