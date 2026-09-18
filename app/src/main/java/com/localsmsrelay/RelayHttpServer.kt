package com.localsmsrelay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class RelayHttpServer(
    host: String,
    port: Int,
    private val context: Context,
    private val recentIds: RecentMessageIds,
    private val onMessage: (IncomingMessage) -> Unit
) : NanoHTTPD(host, port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/health" ->
                    json(Response.Status.OK, JSONObject().put("ok", true).put("service", "SMS-L"))

                session.method == Method.POST && session.uri == "/sms" -> handleSms(session)
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not_found"))
            }
        } catch (_: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", "internal_error"))
        }
    }

    private fun handleSms(session: IHTTPSession): Response {
        val contentLengthHeader = session.headers["content-length"]
        val declaredLength = contentLengthHeader?.trim()?.toLongOrNull()
        if (contentLengthHeader != null && declaredLength == null) return badRequest("invalid_body")

        val contentType = session.headers["content-type"].orEmpty()
        if (!Utf8JsonBodyReader.isApplicationJson(contentType)) {
            return badRequest("invalid_content_type")
        }

        val rawBody = try {
            Utf8JsonBodyReader.read(session.inputStream, declaredLength, MAX_BODY_BYTES)
        } catch (error: Utf8JsonBodyReader.BodyReadException) {
            return badRequest(error.responseError)
        }

        val body = try {
            JSONObject(rawBody)
        } catch (_: JSONException) {
            return badRequest("invalid_json")
        }

        val suppliedToken = body.optString("token", "")
        if (!secureEquals(suppliedToken, AppPrefs.token(context))) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("ok", false).put("error", "unauthorized"))
        }

        val text = body.optString("text", "").trim()
        if (text.isEmpty()) return badRequest("text_required")
        if (text.length > MAX_TEXT_CHARS) return badRequest("text_too_long")
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d(LOG_TAG, "原始解析后的 text=[$text]")
        }

        val rawSender = body.optString("sender", "").trim()
        if (rawSender.length > MAX_METADATA_CHARS) return badRequest("sender_too_long")
        val sender = rawSender.ifEmpty { null }

        val rawTimestamp = body.optString("timestamp", "").trim()
        if (rawTimestamp.length > MAX_METADATA_CHARS) return badRequest("timestamp_too_long")
        val timestamp = rawTimestamp.ifEmpty { null }

        val rawMessageId = body.optString("messageId", "").trim()
        if (rawMessageId.length > MAX_MESSAGE_ID_CHARS) return badRequest("message_id_too_long")
        val messageId = rawMessageId.ifEmpty { null }

        if (!messageId.isNullOrBlank() && recentIds.isDuplicateAndRemember(messageId)) {
            return json(Response.Status.OK, JSONObject().put("ok", true))
        }

        onMessage(IncomingMessage(sender, text, timestamp, messageId))
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun secureEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(Charsets.UTF_8),
        right.toByteArray(Charsets.UTF_8)
    )

    private fun badRequest(error: String) =
        json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", error))

    private fun json(status: Response.IStatus, body: JSONObject): Response {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        ).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    companion object {
        private const val LOG_TAG = "LocalSmsRelay"
        private const val MAX_BODY_BYTES = 16 * 1024
        private const val MAX_TEXT_CHARS = 8_000
        private const val MAX_METADATA_CHARS = 200
        private const val MAX_MESSAGE_ID_CHARS = 256
    }
}
