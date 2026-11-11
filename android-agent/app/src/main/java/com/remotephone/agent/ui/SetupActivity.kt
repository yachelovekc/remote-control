package com.remotephone.agent.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.remotephone.agent.R
import com.remotephone.agent.config.AgentConfig
import com.remotephone.agent.service.AgentForegroundService
import com.remotephone.agent.service.RemoteAccessibilityService

class SetupActivity : AppCompatActivity() {

    private lateinit var config: AgentConfig
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var etSupabaseUrl: EditText
    private lateinit var etSupabaseKey: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etDevicePassword: EditText
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnSaveAndStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        config = AgentConfig(this)

        initViews()
        loadSavedConfig()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun initViews() {
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        etSupabaseUrl = findViewById(R.id.etSupabaseUrl)
        etSupabaseKey = findViewById(R.id.etSupabaseKey)
        etDeviceId = findViewById(R.id.etDeviceId)
        etDevicePassword = findViewById(R.id.etDevicePassword)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnSaveAndStart = findViewById(R.id.btnSaveAndStart)

        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnSaveAndStart.setOnClickListener {
            saveAndStart()
        }
    }

    private fun loadSavedConfig() {
        etSupabaseUrl.setText(config.supabaseUrl)
        etSupabaseKey.setText(config.supabaseAnonKey)
        etDeviceId.setText(config.deviceId)
        etDevicePassword.setText(config.accessPassword)
    }

    private fun updateStatus() {
        val isA11yActive = RemoteAccessibilityService.instance != null
        if (isA11yActive) {
            tvAccessibilityStatus.text = "✓ Accessibility Service: Активен (Готов к кликам)"
            tvAccessibilityStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnEnableAccessibility.isEnabled = false
            btnEnableAccessibility.text = "Accessibility Service уже включен"
        } else {
            tvAccessibilityStatus.text = "✗ Accessibility Service: Не включен"
            tvAccessibilityStatus.setTextColor(Color.parseColor("#FF5252"))
            btnEnableAccessibility.isEnabled = true
            btnEnableAccessibility.text = "Включить Accessibility Service в настройках"
        }

        if (config.isConfigured) {
            tvConnectionStatus.text = "Конфигурация задана. ID: ${config.deviceId}"
            tvConnectionStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvConnectionStatus.text = "Требуется заполнить Supabase URL и Anon Key"
            tvConnectionStatus.setTextColor(Color.parseColor("#FFA500"))
        }
    }

    private fun saveAndStart() {
        val url = etSupabaseUrl.text.toString().trim()
        val key = etSupabaseKey.text.toString().trim()
        val devId = etDeviceId.text.toString().trim()
        val pass = etDevicePassword.text.toString().trim()

        if (url.isEmpty() || key.isEmpty() || devId.isEmpty()) {
            Toast.makeText(this, "Пожалуйста, заполните URL, ключ и ID устройства", Toast.LENGTH_SHORT).show()
            return
        }

        config.supabaseUrl = url
        config.supabaseAnonKey = key
        config.deviceId = devId
        config.accessPassword = pass

        Toast.makeText(this, "Настройки сохранены. Запуск фонового агента...", Toast.LENGTH_SHORT).show()

        // Start Foreground Service
        AgentForegroundService.start(applicationContext)

        // Request initial MediaProjection token
        MediaProjectionPermissionActivity.requestPermission(this)

        // Close and remove from recents
        finishAndRemoveTask()
    }
}
