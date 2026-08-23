package com.localsmsrelay

object AppNavigation {
    enum class Page { MESSAGES, SETTINGS }

    val defaultPage: Page = Page.MESSAGES
    const val EMPTY_TITLE = "暂无接收记录"
    const val EMPTY_SUBTITLE = "iPhone 转发的短信将显示在这里"
}
