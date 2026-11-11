package com.remotephone.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.remotephone.agent.service.AgentForegroundService

class MediaProjectionPermissionActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_CAPTURE = 2001

        fun requestPermission(context: Context) {
            val intent = Intent(context, MediaProjectionPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CAPTURE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, AgentForegroundService::class.java).apply {
                action = AgentForegroundService.ACTION_SET_PROJECTION
                putExtra(AgentForegroundService.EXTRA_PROJECTION_DATA, data)
            }
            startService(serviceIntent)
        }
        finish()
    }
}
