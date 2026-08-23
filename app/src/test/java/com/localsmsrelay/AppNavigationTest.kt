package com.localsmsrelay

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test
    fun appAlwaysStartsOnMessagesPage() {
        assertEquals(AppNavigation.Page.MESSAGES, AppNavigation.defaultPage)
    }

    @Test
    fun emptyStateUsesRequiredFriendlyCopy() {
        assertEquals("暂无接收记录", AppNavigation.EMPTY_TITLE)
        assertEquals("iPhone 转发的短信将显示在这里", AppNavigation.EMPTY_SUBTITLE)
    }
}
