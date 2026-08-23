package com.localsmsrelay

data class SmsNotificationContent(val title: String, val text: String)

object NotificationContentFormatter {
    fun format(sender: String?, text: String, otp: String?): SmsNotificationContent {
        val title = when {
            !otp.isNullOrBlank() -> "iPhone 验证码 · $otp"
            !sender.isNullOrBlank() -> "iPhone 短信 · $sender"
            else -> "iPhone 短信"
        }
        return SmsNotificationContent(title = title, text = text)
    }
}

