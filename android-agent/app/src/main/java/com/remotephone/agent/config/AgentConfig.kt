package com.remotephone.agent.config

import android.content.Context
import android.content.SharedPreferences

class AgentConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("remote_phone_agent_prefs", Context.MODE_PRIVATE)

    var supabaseUrl: String
        get() = prefs.getString(KEY_SUPABASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUPABASE_URL, value.trim().removeSuffix("/")).apply()

    var supabaseAnonKey: String
        get() = prefs.getString(KEY_SUPABASE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUPABASE_KEY, value.trim()).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "phone2") ?: "phone2"
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value.trim()).apply()

    var accessPassword: String
        get() = prefs.getString(KEY_ACCESS_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACCESS_PASSWORD, value.trim()).apply()

    var turnServerUrl: String
        get() = prefs.getString(KEY_TURN_URL, "stun:stun.l.google.com:19302") ?: "stun:stun.l.google.com:19302"
        set(value) = prefs.edit().putString(KEY_TURN_URL, value.trim()).apply()

    var turnUsername: String
        get() = prefs.getString(KEY_TURN_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TURN_USER, value.trim()).apply()

    var turnCredential: String
        get() = prefs.getString(KEY_TURN_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TURN_PASS, value.trim()).apply()

    val isConfigured: Boolean
        get() = supabaseUrl.isNotEmpty() && supabaseAnonKey.isNotEmpty() && deviceId.isNotEmpty()

    companion object {
        private const val KEY_SUPABASE_URL = "supabase_url"
        private const val KEY_SUPABASE_KEY = "supabase_key"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCESS_PASSWORD = "access_password"
        private const val KEY_TURN_URL = "turn_url"
        private const val KEY_TURN_USER = "turn_user"
        private const val KEY_TURN_PASS = "turn_pass"
    }
}
