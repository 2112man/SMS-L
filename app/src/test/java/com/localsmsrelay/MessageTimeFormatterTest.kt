package com.localsmsrelay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MessageTimeFormatterTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun todayShowsOnlyHourAndMinute() {
        val now = time(2026, Calendar.AUGUST, 24, 0, 30)
        val received = time(2026, Calendar.AUGUST, 24, 0, 18)
        assertEquals("00:18", MessageTimeFormatter.format(received, now, zone))
    }

    @Test
    fun olderMessageShowsMonthDayHourAndMinute() {
        val now = time(2026, Calendar.AUGUST, 25, 8, 0)
        val received = time(2026, Calendar.AUGUST, 24, 0, 18)
        assertEquals("08-24 00:18", MessageTimeFormatter.format(received, now, zone))
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis
}
