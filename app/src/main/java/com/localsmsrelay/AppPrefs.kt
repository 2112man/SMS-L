package com.localsmsrelay

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

object AppPrefs {
    private const val PREFS_NAME = "local_sms_relay"
    private const val KEY_TOKEN = "token"
    private const val KEY_PORT = "port"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_OTP_ENABLED = "otp_enabled"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_SERVICE_ENABLED = "service_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun token(context: Context): String {
        val current = prefs(context).getString(KEY_TOKEN, null)
        if (!current.isNullOrBlank()) return current
        return generateToken().also { setToken(context, it) }
    }

    fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun setToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TOKEN, value).apply()
    }

    fun port(context: Context): Int = prefs(context).getInt(KEY_PORT, 8765)

    fun setPort(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_PORT, value).apply()
    }

    fun autoStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START, false)
    fun setAutoStart(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_START, value).apply()

    fun otpEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_OTP_ENABLED, true)
    fun setOtpEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_OTP_ENABLED, value).apply()

    fun vibrate(context: Context): Boolean = prefs(context).getBoolean(KEY_VIBRATE, true)
    fun setVibrate(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_VIBRATE, value).apply()

    fun serviceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, false)

    fun setServiceEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()
}

