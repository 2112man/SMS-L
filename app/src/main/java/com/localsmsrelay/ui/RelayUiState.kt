package com.localsmsrelay.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.localsmsrelay.AppPrefs
import com.localsmsrelay.NotificationHelper
import com.localsmsrelay.OtpExtractor
import com.localsmsrelay.RelayConnectionState
import com.localsmsrelay.RelayEndpoint
import com.localsmsrelay.RelayService
import com.localsmsrelay.WifiIpResolver
import com.localsmsrelay.data.SmsHistoryRepository
import com.localsmsrelay.data.SmsMessageEntity

/**
 * 把既有的服务、数据库与配置接到 Compose 上。
 *
 * 这里是纯粹的「观察 + 转发」层：业务逻辑（RelayService、CloudflareClient、
 * Room、OTP、通知）全部沿用原实现，没有为了 UI 改动任何一行。
 * 状态变化仍通过原有的两个广播（连接状态、历史变更）驱动。
 */
class RelayUiState internal constructor(private val appContext: Context) {
    private val repository = SmsHistoryRepository.get(appContext)

    var serviceRunning by mutableStateOf(RelayService.isRunning)
        private set
    var connectionState by mutableStateOf(RelayService.connectionState)
        private set
    var connectionDetail by mutableStateOf(RelayService.connectionDetail)
        private set

    var messages by mutableStateOf<List<SmsMessageEntity>>(emptyList())
        private set

    var useCloudflare by mutableStateOf(AppPrefs.useCloudflareRelay(appContext))
        private set
    var serverUrl by mutableStateOf(AppPrefs.serverUrl(appContext))
        private set
    var androidToken by mutableStateOf(AppPrefs.androidToken(appContext))
        private set
    var lanToken by mutableStateOf(AppPrefs.token(appContext))
        private set
    var port by mutableStateOf(AppPrefs.port(appContext).toString())
        private set
    var otpEnabled by mutableStateOf(AppPrefs.otpEnabled(appContext))
        private set
    var autoStart by mutableStateOf(AppPrefs.autoStart(appContext))
        private set
    var vibrate by mutableStateOf(AppPrefs.vibrate(appContext))
        private set

    var wifiIpv4 by mutableStateOf(WifiIpResolver.currentWifiIpv4(appContext))
        private set

    internal fun refreshStatus() {
        serviceRunning = RelayService.isRunning
        connectionState = RelayService.connectionState
        connectionDetail = RelayService.connectionDetail
    }

    internal fun refreshMessages() {
        repository.loadAll { messages = it }
    }

    internal fun refreshLan() {
        wifiIpv4 = WifiIpResolver.currentWifiIpv4(appContext)
    }

    internal fun refreshAll() {
        refreshStatus()
        refreshMessages()
        refreshLan()
    }

    // ------------------------------------------------------------ 配置写入

    // 注意：这些方法名不能叫 setXxx —— 属性的 `private set` 已经生成了同签名的
    // setXxx(Z)V，重名会在编译期触发 "Platform declaration clash"。
    fun applyUseCloudflare(value: Boolean) {
        AppPrefs.setUseCloudflareRelay(appContext, value)
        useCloudflare = value
        refreshLan()
        restartServiceIfRunning()
    }

    fun saveCloudflare(serverUrl: String, androidToken: String): Boolean {
        val normalized = RelayEndpoint.normalize(serverUrl)
        if (normalized == null) return false
        if (androidToken.length < AppPrefs.MIN_TOKEN_LENGTH) return false
        AppPrefs.setServerUrl(appContext, serverUrl)
        AppPrefs.setAndroidToken(appContext, androidToken)
        this.serverUrl = AppPrefs.serverUrl(appContext)
        this.androidToken = AppPrefs.androidToken(appContext)
        restartServiceIfRunning()
        return true
    }

    fun saveLan(token: String, port: String): Boolean {
        val portValue = port.toIntOrNull() ?: return false
        if (token.length < AppPrefs.MIN_TOKEN_LENGTH) return false
        if (portValue !in 1024..65535) return false
        AppPrefs.setToken(appContext, token)
        AppPrefs.setPort(appContext, portValue)
        this.lanToken = token
        this.port = portValue.toString()
        restartServiceIfRunning()
        return true
    }

    fun regenerateAndroidToken(): String {
        val token = AppPrefs.generateToken()
        AppPrefs.setAndroidToken(appContext, token)
        androidToken = token
        return token
    }

    fun regenerateLanToken(): String {
        val token = AppPrefs.generateToken()
        AppPrefs.setToken(appContext, token)
        lanToken = token
        return token
    }

    fun applyOtpEnabled(value: Boolean) {
        AppPrefs.setOtpEnabled(appContext, value)
        otpEnabled = value
    }

    fun applyAutoStart(value: Boolean) {
        AppPrefs.setAutoStart(appContext, value)
        autoStart = value
    }

    fun applyVibrate(value: Boolean) {
        AppPrefs.setVibrate(appContext, value)
        vibrate = value
        NotificationHelper.updateIncomingSmsVibration(appContext)
    }

    // ------------------------------------------------------------ 服务控制

    fun startService() {
        AppPrefs.setServiceEnabled(appContext, true)
        val intent = Intent(appContext, RelayService::class.java)
            .setAction(RelayService.ACTION_START)
        try {
            appContext.startForegroundService(intent)
        } catch (_: RuntimeException) {
            toast("无法启动服务")
        }
        refreshStatus()
    }

    fun stopService() {
        AppPrefs.setServiceEnabled(appContext, false)
        if (RelayService.isRunning) {
            appContext.startService(
                Intent(appContext, RelayService::class.java).setAction(RelayService.ACTION_STOP)
            )
        }
        refreshStatus()
    }

    private fun restartServiceIfRunning() {
        if (RelayService.isRunning) startService()
    }

    // ------------------------------------------------------------ 消息操作

    fun markRead(messageId: Long) {
        val notificationId = messages.firstOrNull { it.id == messageId }?.notificationId
        if (notificationId != null) NotificationHelper.cancelIncomingSms(appContext, notificationId)
        repository.markRead(messageId) { refreshMessages() }
    }

    fun markAllRead() {
        NotificationHelper.cancelAllIncomingSms(appContext)
        repository.markAllRead { ids ->
            ids.forEach { NotificationHelper.cancelIncomingSms(appContext, it) }
            NotificationHelper.cancelAllIncomingSms(appContext)
            refreshMessages()
        }
    }

    fun clearHistory() {
        NotificationHelper.cancelAllIncomingSms(appContext)
        repository.clearAll { ids ->
            ids.forEach { NotificationHelper.cancelIncomingSms(appContext, it) }
            NotificationHelper.cancelAllIncomingSms(appContext)
            toast("短信记录已清空")
            refreshMessages()
        }
    }

    fun copyOtp(otp: String) {
        val clipboard = appContext.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("验证码", otp))
        toast("验证码已复制")
    }

    fun copyText(label: String, value: String) {
        val clipboard = appContext.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        toast("$label 已复制")
    }

    fun sendTestNotification() {
        val sample = "【SMS-L】测试验证码 123456"
        val otp = if (otpEnabled) OtpExtractor.extract(sample) else null
        NotificationHelper.showTestSms(appContext, sample, otp)
    }

    fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    /** 局域网模式下拼给 iPhone 用的完整地址。 */
    fun lanEndpoint(): String? {
        val ip = wifiIpv4 ?: return null
        return "http://$ip:${port.ifBlank { "8765" }}/sms"
    }
}

@Composable
fun rememberRelayUiState(): RelayUiState {
    val context = LocalContext.current
    val state = remember { RelayUiState(context.applicationContext) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    RelayService.ACTION_STATUS_CHANGED -> state.refreshStatus()
                    SmsHistoryRepository.ACTION_HISTORY_CHANGED -> state.refreshMessages()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(RelayService.ACTION_STATUS_CHANGED)
            addAction(SmsHistoryRepository.ACTION_HISTORY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        state.refreshAll()
        onDispose { context.unregisterReceiver(receiver) }
    }

    return state
}
