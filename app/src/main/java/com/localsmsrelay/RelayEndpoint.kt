package com.localsmsrelay

import java.net.URI
import java.net.URISyntaxException

/**
 * 把用户填写的服务器地址规范成 WebSocket 端点。
 *
 * 允许的输入（大小写不敏感）：
 *   sms-l-relay.xxx.workers.dev          -> wss://sms-l-relay.xxx.workers.dev/ws
 *   https://sms-l-relay.xxx.workers.dev  -> wss://sms-l-relay.xxx.workers.dev/ws
 *   wss://host/ws                        -> 原样保留
 *   http://127.0.0.1:8787                -> ws://127.0.0.1:8787/ws（本地 wrangler dev）
 *
 * 无法识别时返回 null，由界面提示用户。
 */
object RelayEndpoint {
    private val SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
    private const val WS_PATH = "/ws"

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val candidate = if (SCHEME_PATTERN.containsMatchIn(trimmed)) trimmed else "https://$trimmed"

        val uri = try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            return null
        }

        val scheme = when (uri.scheme?.lowercase()) {
            "https", "wss" -> "wss"
            "http", "ws" -> "ws"
            else -> return null
        }

        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val rawPath = uri.path.orEmpty()
        val path = if (rawPath.isEmpty() || rawPath == "/") WS_PATH else rawPath

        return "$scheme://$host$port$path"
    }

    /** 只有 wss/ws 才算配置有效。 */
    fun isUsable(raw: String): Boolean = normalize(raw) != null
}
