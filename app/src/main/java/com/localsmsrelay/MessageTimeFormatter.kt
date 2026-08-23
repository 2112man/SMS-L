package com.localsmsrelay

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MessageTimeFormatter {
    fun format(
        receivedAt: Long,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val received = Calendar.getInstance(timeZone).apply { timeInMillis = receivedAt }
        val current = Calendar.getInstance(timeZone).apply { timeInMillis = now }
        val sameDay = received.get(Calendar.ERA) == current.get(Calendar.ERA) &&
            received.get(Calendar.YEAR) == current.get(Calendar.YEAR) &&
            received.get(Calendar.DAY_OF_YEAR) == current.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).apply {
            this.timeZone = timeZone
        }.format(Date(receivedAt))
    }
}
