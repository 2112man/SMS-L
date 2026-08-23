package com.localsmsrelay

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CopyOtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val otp = intent.getStringExtra(EXTRA_OTP)?.takeIf { it.matches(Regex("\\d{4,8}")) } ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("验证码", otp))
        Toast.makeText(context, "验证码已复制", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_OTP = "otp"
    }
}

