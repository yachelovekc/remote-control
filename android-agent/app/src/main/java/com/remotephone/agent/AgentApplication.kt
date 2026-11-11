package com.remotephone.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.webrtc.PeerConnectionFactory

class AgentApplication : Application() {

    companion object {
        const val CHANNEL_ID = "remote_phone_agent_channel"
        lateinit var instance: AgentApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()
        initWebRtc()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновый сервис синхронизации"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initWebRtc() {
        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }
}
