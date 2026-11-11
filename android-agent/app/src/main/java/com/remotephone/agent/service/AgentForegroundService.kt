package com.remotephone.agent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.remotephone.agent.AgentApplication
import com.remotephone.agent.R
import com.remotephone.agent.config.AgentConfig
import com.remotephone.agent.signaling.SupabaseSignaling
import com.remotephone.agent.ui.SetupActivity
import com.remotephone.agent.webrtc.WebRtcManager

class AgentForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.remotephone.agent.action.START"
        const val ACTION_SET_PROJECTION = "com.remotephone.agent.action.SET_PROJECTION"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        var instance: AgentForegroundService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var config: AgentConfig
    private var webRtcManager: WebRtcManager? = null
    private var signaling: SupabaseSignaling? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = AgentConfig(this)

        acquireCpuWakeLock()
        startInForeground()

        initSignalingAndWebRtc()
    }

    private fun acquireCpuWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "remotephone:agent_cpu")
        wakeLock?.acquire()
    }

    private fun startInForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, AgentApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(1001, notification, type)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun initSignalingAndWebRtc() {
        signaling = SupabaseSignaling(config) { webRtcManager }
        webRtcManager = WebRtcManager(this, config, signaling!!)
        signaling?.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_SET_PROJECTION -> {
                    val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                    }
                    if (projectionData != null) {
                        webRtcManager?.setProjectionIntent(projectionData)
                    }
                }
                ACTION_START -> {
                    if (config.isConfigured) {
                        signaling?.connect()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        signaling?.disconnect()
        webRtcManager?.close()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
