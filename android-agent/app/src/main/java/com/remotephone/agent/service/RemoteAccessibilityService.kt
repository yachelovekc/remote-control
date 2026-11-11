package com.remotephone.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Ensure AgentForegroundService is running
        AgentForegroundService.start(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Auto-accept MediaProjection system dialog if it appears
        val pkg = event.packageName?.toString() ?: ""
        if (pkg.contains("systemui") || pkg.contains("media")) {
            autoClickPermissionDialog(rootInActiveWindow)
        }
    }

    private fun autoClickPermissionDialog(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val targetTexts = listOf(
            "start now", "начать", "начать запись", "allow", "разрешить",
            "start screen capture", "транслировать экран"
        )

        val text = node.text?.toString()?.lowercase()
        if (text != null && targetTexts.any { text.contains(it) }) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }

        for (i in 0 until node.childCount) {
            autoClickPermissionDialog(node.getChild(i))
        }
    }

    override fun onInterrupt() {}

    fun performTap(xPercent: Float, yPercent: Float) {
        val metrics = resources.displayMetrics
        val x = (xPercent.coerceIn(0f, 1f) * metrics.widthPixels)
        val y = (yPercent.coerceIn(0f, 1f) * metrics.heightPixels)

        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, null, null)
    }

    fun performSwipe(x1P: Float, y1P: Float, x2P: Float, y2P: Float, durationMs: Long = 300) {
        val metrics = resources.displayMetrics
        val x1 = (x1P.coerceIn(0f, 1f) * metrics.widthPixels)
        val y1 = (y1P.coerceIn(0f, 1f) * metrics.heightPixels)
        val x2 = (x2P.coerceIn(0f, 1f) * metrics.widthPixels)
        val y2 = (y2P.coerceIn(0f, 1f) * metrics.heightPixels)

        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50)))
            .build()

        dispatchGesture(gesture, null, null)
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun performNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun performLockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    fun wakeUpScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) {
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "remotephone:wake"
            )
            wakeLock.acquire(3000)
            wakeLock.release()

            // Slight delay then swipe up to dismiss swipe lock screen
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                performSwipe(0.5f, 0.8f, 0.5f, 0.2f, 250)
            }, 600)
        }
    }
}
