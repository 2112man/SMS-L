package com.localsmsrelay

import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/** Cloudflare 下发的一帧消息。 */
sealed class RelayFrame {
    data class Sms(val message: IncomingMessage, val messageId: String) : RelayFrame()

    data class Ack(val messageId: String) : RelayFrame()

    /** 无法识别或校验不通过的帧，直接丢弃。 */
    object Ignored : RelayFrame()
}

/**
 * 云端 WebSocket 协议解析。
 *
 * 严格程度对齐原先的 Utf8JsonBodyReader：超长字段一律拒绝，
 * 而不是截断后悄悄入库（截断会破坏 messageId 去重的一致性）。
 */
object RelayEnvelope {
    const val MAX_TEXT_CHARS = 8000
    const val MAX_METADATA_CHARS = 200
    const val MAX_MESSAGE_ID_CHARS = 256

    private const val TYPE_SMS = "sms"
    private const val TYPE_ACK = "ack"
    private const val FALLBACK_PREFIX = "h1-"

    fun parse(raw: String): RelayFrame {
        val json = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return RelayFrame.Ignored
        }
        return when (json.optString("type", "")) {
            TYPE_SMS -> parseSms(json)
            TYPE_ACK -> parseAck(json)
            // 未知帧类型静默忽略，便于服务端以后新增控制帧。
            else -> RelayFrame.Ignored
        }
    }

    private fun parseSms(json: JSONObject): RelayFrame {
        val text = json.optString("text", "").trim()
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) return RelayFrame.Ignored

        val sender = readOptionalString(json, "sender", MAX_METADATA_CHARS)
        val timestamp = readOptionalString(json, "timestamp", MAX_METADATA_CHARS)
        val rawMessageId = readOptionalString(json, "messageId", MAX_MESSAGE_ID_CHARS)

        val messageId = rawMessageId ?: fallbackMessageId(sender, text, timestamp)
        return RelayFrame.Sms(
            message = IncomingMessage(
                sender = sender,
                text = text,
                timestamp = timestamp,
                messageId = messageId
            ),
            messageId = messageId
        )
    }

    private fun parseAck(json: JSONObject): RelayFrame {
        val messageId = readOptionalString(json, "messageId", MAX_MESSAGE_ID_CHARS)
            ?: return RelayFrame.Ignored
        return RelayFrame.Ack(messageId)
    }

    /**
     * 读取可选的字符串字段。
     *
     * 必须在 optString 之前用 isNull 判断：org.json 对 JSON null 的 optString
     * 会返回字符串 "null"，而不是空串，直接用会把 null 当成有效值。
     */
    private fun readOptionalString(json: JSONObject, name: String, maxLength: Int): String? {
        if (json.isNull(name)) return null
        val value = json.optString(name, "").trim()
        if (value.isEmpty() || value.length > maxLength) return null
        return value
    }

    /**
     * messageId 缺失时的兜底：内容哈希。
     *
     * 输入格式必须与服务端 index.js 的 resolveMessageId 完全一致：
     * sender + NUL + text + NUL + timestamp，否则两端算出的 ID 不同，会导致去重失效。
     */
    fun fallbackMessageId(sender: String?, text: String, timestamp: String?): String {
        val material = "${sender ?: ""}\u0000$text\u0000${timestamp ?: ""}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(FALLBACK_PREFIX.length + digest.size * 2)
        hex.append(FALLBACK_PREFIX)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX_DIGITS[value ushr 4])
            hex.append(HEX_DIGITS[value and 0x0F])
        }
        return hex.toString()
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}
