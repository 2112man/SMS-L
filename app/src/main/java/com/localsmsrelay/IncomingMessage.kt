package com.localsmsrelay

/**
 * 一条待展示的短信。
 *
 * 该模型原先定义在 RelayHttpServer.kt 中，因为局域网 HTTP 与云端 WebSocket
 * 两条链路都要用它，所以抽成独立文件。
 */
data class IncomingMessage(
    val sender: String?,
    val text: String,
    val timestamp: String?,
    val messageId: String?
)
