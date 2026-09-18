package com.localsmsrelay

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Android 主动连向 Cloudflare Worker 的 WebSocket 客户端。
 *
 * 设计要点：
 *   - 只负责传输与重连，不碰数据库和通知，业务处理全部交给 [Listener]。
 *   - 断线后按指数退避重连（1s→60s 上限，带 ±20% 抖动避免多端同步重连）。
 *   - Token 只放在 Authorization 头里，绝不进 URL、不进日志、不进状态文案。
 *   - 回调发生在 OkHttp 的后台线程上；单条连接的 onMessage 是串行的，
 *     因此上层可以安全地按到达顺序处理，不会出现去重竞态。
 */
class CloudflareClient(
    private val endpoint: String,
    private val androidToken: String,
    private val listener: Listener
) {
    interface Listener {
        fun onStateChanged(state: RelayConnectionState, detail: String)
        fun onFrame(frame: RelayFrame)
    }

    private val scheduler = Handler(Looper.getMainLooper())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 长连接必须关闭读超时，否则会被 OkHttp 主动断开。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 定期发 WebSocket ping，维持 NAT 映射并防止边缘节点回收空闲连接。
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var stopped = false

    @Volatile
    var isConnected: Boolean = false
        private set

    private var attempt = 0

    private val reconnectTask = Runnable { openSocket() }

    fun start() {
        stopped = false
        attempt = 0
        scheduler.removeCallbacks(reconnectTask)
        openSocket()
    }

    fun shutdown() {
        stopped = true
        scheduler.removeCallbacks(reconnectTask)
        isConnected = false
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    /** 网络切换后立即重试，跳过退避等待。 */
    fun retryNow() {
        if (stopped) return
        scheduler.removeCallbacks(reconnectTask)
        attempt = 0
        socket?.cancel()
        socket = null
        openSocket()
    }

    /** 消息成功写入 Room 后回执，让服务端把它移出待确认队列。 */
    fun sendAck(messageId: String) {
        val frame = JSONObject()
            .put("type", "ack")
            .put("messageId", messageId)
            .toString()
        socket?.send(frame)
    }

    private fun openSocket() {
        if (stopped) return

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $androidToken")
            .build()

        listener.onStateChanged(
            if (attempt == 0) RelayConnectionState.CONNECTING else RelayConnectionState.RECONNECTING,
            ""
        )
        socket = httpClient.newWebSocket(request, SocketListener())
    }

    private fun scheduleReconnect(detail: String, unauthorized: Boolean) {
        if (stopped) return
        socket = null
        isConnected = false

        val delay = if (unauthorized) UNAUTHORIZED_RETRY_MILLIS else nextBackoffMillis()
        listener.onStateChanged(
            if (unauthorized) RelayConnectionState.UNAUTHORIZED else RelayConnectionState.RECONNECTING,
            detail
        )

        scheduler.removeCallbacks(reconnectTask)
        scheduler.postDelayed(reconnectTask, delay)
    }

    /** 指数退避 + 抖动，避免网络恢复瞬间所有客户端同时重连。 */
    private fun nextBackoffMillis(): Long {
        val exponential = BASE_BACKOFF_MILLIS shl min(attempt, MAX_BACKOFF_SHIFT)
        attempt += 1
        val capped = min(exponential, MAX_BACKOFF_MILLIS)
        val jitter = (capped * JITTER_RATIO).toLong()
        return capped - jitter + Random.nextLong(jitter * 2 + 1)
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            isConnected = true
            listener.onStateChanged(RelayConnectionState.CONNECTED, "")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val frame = RelayEnvelope.parse(text)) {
                is RelayFrame.Ignored -> Unit
                else -> listener.onFrame(frame)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect("连接已关闭", unauthorized = false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            val unauthorized = code == 401 || code == 403
            val detail = when {
                unauthorized -> "服务器拒绝连接：ANDROID_TOKEN 无效"
                code != null -> "握手失败（HTTP $code）"
                else -> "网络不可用（${t.javaClass.simpleName}）"
            }
            scheduleReconnect(detail, unauthorized)
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val PING_INTERVAL_SECONDS = 30L
        const val BASE_BACKOFF_MILLIS = 1000L
        const val MAX_BACKOFF_MILLIS = 60_000L
        const val MAX_BACKOFF_SHIFT = 6
        const val UNAUTHORIZED_RETRY_MILLIS = 60_000L
        const val JITTER_RATIO = 0.2
    }
}
