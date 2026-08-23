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

class RelayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: RelayHttpServer? = null
    private var boundPort: Int? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val recentIds = RecentMessageIds()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        NotificationHelper.createChannels(this)
        val initial = NotificationHelper.serviceNotification(this, "正在启动…")
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
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null && !AppPrefs.serviceEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        AppPrefs.setServiceEnabled(this, true)
        mainHandler.post { ensureServer() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        unregisterNetworkCallback()
        isRunning = false
        statusText = "服务已停止"
        broadcastStatus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleEnsureServer()
            override fun onLost(network: Network) = scheduleEnsureServer()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                scheduleEnsureServer()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                scheduleEnsureServer()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
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

    private fun scheduleEnsureServer() {
        mainHandler.removeCallbacks(ensureServerRunnable)
        mainHandler.postDelayed(ensureServerRunnable, 500)
    }

    private val ensureServerRunnable = Runnable { ensureServer() }

    private fun ensureServer() {
        val address = WifiIpResolver.currentWifiIpv4(this)
        val port = AppPrefs.port(this)
        if (server == null || port != boundPort) {
            stopServer()
            try {
                server = RelayHttpServer(BIND_ADDRESS, port, this, recentIds) { message ->
                    val otp = if (AppPrefs.otpEnabled(this)) OtpExtractor.extract(message.text) else null
                    SmsHistoryRepository.get(this).saveIncoming(
                        message = message,
                        otp = otp,
                        receivedAt = System.currentTimeMillis()
                    )
                    NotificationHelper.showSms(
                        this,
                        message.sender,
                        message.text,
                        otp,
                        AppPrefs.vibrate(this)
                    )
                }.also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                boundPort = port
            } catch (_: Exception) {
                server = null
                boundPort = null
                updateStatus("启动失败：端口 $port 不可用")
                return
            }
        }

        val networkStatus = address?.let { "局域网 IP：$it" } ?: "未连接 Wi-Fi"
        updateStatus("服务运行中 · $networkStatus")
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (_: Exception) {
            // Stopping must never crash the service.
        }
        server = null
        boundPort = null
    }

    private fun updateStatus(value: String) {
        statusText = value
        val notification = NotificationHelper.serviceNotification(this, value)
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        broadcastStatus()
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(packageName))
    }

    companion object {
        private const val BIND_ADDRESS = "0.0.0.0"
        const val ACTION_START = "com.localsmsrelay.action.START"
        const val ACTION_STOP = "com.localsmsrelay.action.STOP"
        const val ACTION_STATUS_CHANGED = "com.localsmsrelay.action.STATUS_CHANGED"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var statusText: String = "服务已停止"
            private set
    }
}
