package com.remotephone.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.remotephone.agent.config.AgentConfig
import com.remotephone.agent.service.AgentForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED == action ||
            "android.intent.action.QUICKBOOT_POWERON" == action ||
            "com.htc.intent.action.QUICKBOOT_POWERON" == action
        ) {
            val config = AgentConfig(context)
            if (config.isConfigured) {
                AgentForegroundService.start(context)
            }
        }
    }
}
