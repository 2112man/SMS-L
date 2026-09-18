package com.localsmsrelay

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.localsmsrelay.data.SmsHistoryRepository
import fi.iki.elonen.NanoHTTPD

/**
 * 常驻前台服务，承载两条互斥的接收链路：
 *
 *   - 云端模式（默认）：[CloudflareClient] 主动连向 Cloudflare Worker 的 WebSocket。
 *     手机不需要公网 IP，也不需要与 iPhone 同处一个 Wi-Fi。
 *   - 局域网模式（兼容保留）：内嵌 NanoHTTPD 监听 0.0.0.0，等 iPhone 直接 POST 过来。
 *
 * 模式由 AppPrefs.useCloudflareRelay 决定，切换后重启服务即可生效。
 * 两条链路共用同一套入库与通知逻辑，因此 OTP 识别、Room 历史、通知行为完全一致。
 */
class RelayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: RelayHttpServer? = null
    private var boundPort: Int? = null
    private var client: CloudflareClient? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val recentIds = RecentMessageIds()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        NotificationHelper.createChannels(this)
        val initial = NotificationHelper.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.SERVICE_NOTIFICATION_ID,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, initial)
        }
        registerNetworkCallback()
        broadcastStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppPrefs.setServiceEnabled(this, false)
            stopConnection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null && !AppPrefs.serviceEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        AppPrefs.setServiceEnabled(this, true)
        mainHandler.post { ensureConnection() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopConnection()
        unregisterNetworkCallback()
        isRunning = false
        updateStatus(RelayConnectionState.STOPPED, "")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- 网络回调

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onConnectivityChanged()
            override fun onLost(network: Network) = onConnectivityChanged()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                scheduleEnsureConnection()

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                scheduleEnsureConnection()
        }
        // 订阅"能访问互联网的网络"而不是仅 Wi-Fi：云端模式下蜂窝网络同样要能触发重连。
        // 局域网模式的 ensureLanServer() 是幂等的，多触发几次没有副作用。
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: RuntimeException) {
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // Callback may already have been removed by the system.
        }
        networkCallback = null
    }

    /** 网络切换时立即重试，不必等退避计时到点。 */
    private fun onConnectivityChanged() {
        if (!AppPrefs.useCloudflareRelay(this)) {
            ensureLanServer()
            return
        }
        val current = client
        when {
            current == null -> ensureConnection()
            !current.isConnected -> current.retryNow()
        }
    }

    private fun scheduleEnsureConnection() {
        mainHandler.removeCallbacks(ensureConnectionRunnable)
        mainHandler.postDelayed(ensureConnectionRunnable, 500)
    }

    private val ensureConnectionRunnable = Runnable { ensureConnection() }

    private fun ensureConnection() {
        if (AppPrefs.useCloudflareRelay(this)) {
            ensureCloudflareClient()
        } else {
            ensureLanServer()
        }
    }

    // ------------------------------------------------------- 云端 WebSocket 模式

    private fun ensureCloudflareClient() {
        stopLanServer()

        val endpoint = RelayEndpoint.normalize(AppPrefs.serverUrl(this))
        val token = AppPrefs.androidToken(this)
        if (endpoint == null || token.length < MIN_TOKEN_LENGTH) {
            stopCloudflareClient()
            val detail = if (endpoint == null) {
                "未配置服务器地址，请在设置中填写 Cloudflare Worker 地址"
            } else {
                "未配置 Android Token（至少 $MIN_TOKEN_LENGTH 个字符）"
            }
            updateStatus(RelayConnectionState.STOPPED, detail)
            return
        }
        if (client != null) return

        val created = CloudflareClient(
            endpoint = endpoint,
            androidToken = token,
            listener = object : CloudflareClient.Listener {
                override fun onStateChanged(state: RelayConnectionState, detail: String) {
                    updateStatus(state, detail)
                }

                override fun onFrame(frame: RelayFrame) {
                    if (frame is RelayFrame.Sms) handleCloudflareMessage(frame)
                }
            }
        )
        client = created
        created.start()
    }

    private fun stopCloudflareClient() {
        client?.shutdown()
        client = null
    }

    private fun handleCloudflareMessage(frame: RelayFrame.Sms) {
        val message = frame.message
        val otp = if (AppPrefs.otpEnabled(this)) OtpExtractor.extract(message.text) else null
        val saved = saveIncoming(message, otp)
        if (saved != null) {
            NotificationHelper.showSms(
                context = this,
                messageDatabaseId = saved.databaseId,
                notificationId = saved.notificationId,
                unreadCount = saved.unreadCount,
                sender = message.sender,
                text = message.text,
                otp = otp
            )
        }
        // 无论是否重复都回执：能走到这里说明它已经在库里（本次写入或此前写入），
        // 让服务端把它移出待确认队列，避免每次重连都白重放一遍。
        client?.sendAck(frame.messageId)
    }

    // ------------------------------------------------------------ 局域网 HTTP 模式

    private fun ensureLanServer() {
        stopCloudflareClient()

        val address = WifiIpResolver.currentWifiIpv4(this)
        val port = AppPrefs.port(this)
        if (server == null || port != boundPort) {
            stopLanServer()
            try {
                server = RelayHttpServer(BIND_ADDRESS, port, this, recentIds) onMessage@{ message ->
                    val otp = if (AppPrefs.otpEnabled(this)) OtpExtractor.extract(message.text) else null
                    val saved = saveIncoming(message, otp) ?: return@onMessage
                    NotificationHelper.showSms(
                        context = this,
                        messageDatabaseId = saved.databaseId,
                        notificationId = saved.notificationId,
                        unreadCount = saved.unreadCount,
                        sender = message.sender,
                        text = message.text,
                        otp = otp
                    )
                }.also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                boundPort = port
            } catch (_: Exception) {
                server = null
                boundPort = null
                updateStatus(RelayConnectionState.STOPPED, "启动失败：端口 $port 不可用")
                return
            }
        }

        val networkStatus = address?.let { "局域网 IP：$it" } ?: "未连接 Wi-Fi"
        updateStatus(RelayConnectionState.LAN_LISTENING, networkStatus)
    }

    private fun stopLanServer() {
        try {
            server?.stop()
        } catch (_: Exception) {
            // Stopping must never crash the service.
        }
        server = null
        boundPort = null
    }

    // ------------------------------------------------------------------ 公共部分

    private fun stopConnection() {
        stopCloudflareClient()
        stopLanServer()
    }

    /** 两条链路共用的入库逻辑：返回 null 表示重复投递，不产生通知。 */
    private fun saveIncoming(message: IncomingMessage, otp: String?) =
        SmsHistoryRepository.get(this).saveIncoming(
            message = message,
            otp = otp,
            receivedAt = System.currentTimeMillis()
        )

    private fun updateStatus(state: RelayConnectionState, detail: String) {
        connectionState = state
        connectionDetail = detail
        statusText = describe(state, detail)
        broadcastStatus()
    }

    private fun describe(state: RelayConnectionState, detail: String): String = when (state) {
        RelayConnectionState.STOPPED -> "服务已停止"
        RelayConnectionState.CONNECTING -> "正在连接云端…"
        RelayConnectionState.CONNECTED -> "已连接云端中继"
        RelayConnectionState.RECONNECTING -> if (detail.isEmpty()) "正在重连…" else "正在重连：$detail"
        RelayConnectionState.UNAUTHORIZED -> detail.ifEmpty { "认证失败" }
        RelayConnectionState.LAN_LISTENING -> "局域网监听中 · $detail"
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(packageName))
    }

    companion object {
        private const val BIND_ADDRESS = "0.0.0.0"
        private const val MIN_TOKEN_LENGTH = 24

        const val ACTION_START = "com.localsmsrelay.action.START"
        const val ACTION_STOP = "com.localsmsrelay.action.STOP"
        const val ACTION_STATUS_CHANGED = "com.localsmsrelay.action.STATUS_CHANGED"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var statusText: String = "服务已停止"
            private set

        @Volatile
        var connectionState: RelayConnectionState = RelayConnectionState.STOPPED
            private set

        @Volatile
        var connectionDetail: String = ""
            private set
    }
}
