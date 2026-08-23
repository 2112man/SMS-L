package com.localsmsrelay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.localsmsrelay.data.SmsHistoryRepository
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var addressView: TextView
    private lateinit var portSummaryView: TextView
    private lateinit var endpointView: TextView
    private lateinit var tokenInput: EditText
    private lateinit var portInput: EditText
    private lateinit var messagesPage: View
    private lateinit var settingsPage: View
    private lateinit var messagesNavButton: Button
    private lateinit var settingsNavButton: Button
    private lateinit var messageServiceStatus: View
    private lateinit var historyList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var historyRepository: SmsHistoryRepository
    private lateinit var historyAdapter: MessageHistoryAdapter
    private var selectedPage = AppNavigation.defaultPage
    private var pendingAction: (() -> Unit)? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshStatus()
    }

    private val historyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshHistory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannels(this)
        AppPrefs.token(this)
        historyRepository = SmsHistoryRepository.get(this)
        historyAdapter = MessageHistoryAdapter(::copyOtp)
        setContentView(buildUi())

        selectedPage = AppNavigation.defaultPage
        showPage(selectedPage)

        if (intent.getBooleanExtra(EXTRA_RESTORE_SERVICE, false) && !RelayService.isRunning) {
            ensureNotificationPermission { startRelayService() }
        }
    }

    override fun onStart() {
        super.onStart()
        registerAppReceiver(statusReceiver, IntentFilter(RelayService.ACTION_STATUS_CHANGED))
        registerAppReceiver(historyReceiver, IntentFilter(SmsHistoryRepository.ACTION_HISTORY_CHANGED))
        registerWifiNetworkCallback()
        refreshHistory()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshHistory()
    }

    override fun onStop() {
        unregisterWifiNetworkCallback()
        unregisterReceiver(historyReceiver)
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun registerAppReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 248))
        }
        applySystemBarInsets(root)
        val content = FrameLayout(this)
        messagesPage = buildMessagesPage()
        settingsPage = buildSettingsPage()
        content.addView(messagesPage, frameMatch())
        content.addView(settingsPage, frameMatch())
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(View(this).apply { setBackgroundColor(Color.rgb(218, 222, 224)) }, fullWidth(dp(1)))
        val navigation = horizontalRow().apply {
            setBackgroundColor(Color.WHITE)
            elevation = dp(6).toFloat()
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        messagesNavButton = navButton("消息") { showPage(AppNavigation.Page.MESSAGES) }
        settingsNavButton = navButton("设置") { showPage(AppNavigation.Page.SETTINGS) }
        navigation.addView(messagesNavButton, weighted())
        navigation.addView(settingsNavButton, weighted())
        root.addView(navigation, fullWidth(dp(58)))
        return root
    }

    private fun applySystemBarInsets(root: View) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safeInsets = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(
                    initialLeft + safeInsets.left,
                    initialTop + safeInsets.top,
                    initialRight + safeInsets.right,
                    initialBottom + safeInsets.bottom
                )
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    initialLeft + insets.systemWindowInsetLeft,
                    initialTop + insets.systemWindowInsetTop,
                    initialRight + insets.systemWindowInsetRight,
                    initialBottom + insets.systemWindowInsetBottom
                )
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun buildMessagesPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 248))
        }
        val toolbar = horizontalRow().apply {
            setPadding(dp(20), dp(18), dp(8), dp(8))
        }
        toolbar.addView(text("SMS-L", 24f, Typeface.BOLD).apply {
            setTextColor(Color.rgb(14, 77, 100))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menuButton = viewButton("⋮") { anchor -> showHistoryMenu(anchor) }.apply {
            textSize = 24f
            contentDescription = "消息菜单"
            minWidth = dp(48)
            minimumWidth = dp(48)
        }
        toolbar.addView(menuButton, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(toolbar)

        messageServiceStatus = horizontalRow().apply {
            setPadding(dp(16), dp(5), dp(10), dp(5))
            setBackgroundColor(Color.rgb(255, 247, 230))
            addView(text("服务未启动", 13f, Typeface.BOLD).apply {
                setTextColor(Color.rgb(150, 76, 0))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("启动") {
                ensureNotificationPermission { startRelayService() }
            }.apply {
                minWidth = 0
                minimumWidth = 0
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        }
        root.addView(messageServiceStatus, fullWidth())

        val historyContent = FrameLayout(this)
        historyList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(8))
        }
        historyContent.addView(historyList, frameMatch())

        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(text(AppNavigation.EMPTY_TITLE, 18f, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(70, 70, 70))
            }, fullWidth())
            addView(text(AppNavigation.EMPTY_SUBTITLE, 14f).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.GRAY)
                setPadding(0, dp(8), 0, 0)
            }, fullWidth())
        }
        historyContent.addView(emptyState, frameMatch())
        root.addView(historyContent, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        return root
    }

    private fun buildSettingsPage(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(245, 247, 248)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(root)

        root.addView(text("设置", 28f, Typeface.BOLD).apply {
            setTextColor(Color.rgb(14, 77, 100))
        })
        root.addView(text("SMS-L · iPhone 短信局域网通知中继", 14f).apply {
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(20))
        })

        statusView = text("服务状态：已停止", 18f, Typeface.BOLD)
        root.addView(statusView)
        addressView = text("局域网 IP：检测中", 16f)
        root.addView(addressView, marginTop(12))
        portSummaryView = text("端口：${AppPrefs.port(this)}", 16f)
        root.addView(portSummaryView, marginTop(4))
        endpointView = text("iPhone 请求地址：不可用", 14f).apply {
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
        }
        root.addView(endpointView, marginTop(4))

        root.addView(sectionTitle("配置"))
        root.addView(text("Token（至少 24 个字符）", 14f, Typeface.BOLD))
        tokenInput = EditText(this).apply {
            setText(AppPrefs.token(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            typeface = Typeface.MONOSPACE
            setTextSize(14f)
            setSelectAllOnFocus(true)
        }
        root.addView(tokenInput, fullWidth())

        val tokenButtons = horizontalRow()
        tokenButtons.addView(button("复制 Token") {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("SMS-L Token", tokenInput.text))
            toast("Token 已复制")
        }, weighted())
        tokenButtons.addView(button("重新生成") {
            val token = AppPrefs.generateToken()
            AppPrefs.setToken(this, token)
            tokenInput.setText(token)
            toast("已生成新 Token")
        }, weighted(marginStart = 8))
        root.addView(tokenButtons, marginTop(6))

        root.addView(text("端口", 14f, Typeface.BOLD), marginTop(14))
        portInput = EditText(this).apply {
            setText(String.format(Locale.ROOT, "%d", AppPrefs.port(this@MainActivity)))
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(16f)
        }
        root.addView(portInput, fullWidth())

        root.addView(button("保存配置") {
            if (saveConfiguration()) {
                if (RelayService.isRunning) startRelayService()
                toast("配置已保存")
                refreshStatus()
            }
        }, marginTop(8))

        val actions = horizontalRow()
        actions.addView(button("启动服务") {
            if (!saveConfiguration()) return@button
            ensureNotificationPermission { startRelayService() }
        }, weighted())
        actions.addView(button("停止服务") { stopRelayService() }, weighted(marginStart = 8))
        root.addView(actions, marginTop(16))

        root.addView(button("发送测试通知") {
            ensureNotificationPermission {
                val sample = "【SMS-L】测试验证码 123456"
                val otp = if (AppPrefs.otpEnabled(this)) OtpExtractor.extract(sample) else null
                NotificationHelper.showSms(this, null, sample, otp, AppPrefs.vibrate(this))
            }
        }, marginTop(8))

        root.addView(sectionTitle("选项"))
        root.addView(switch("开机自动启动", AppPrefs.autoStart(this)) {
            AppPrefs.setAutoStart(this, it)
        })
        root.addView(switch("验证码自动识别", AppPrefs.otpEnabled(this)) {
            AppPrefs.setOtpEnabled(this, it)
        })
        root.addView(switch("收到短信时震动", AppPrefs.vibrate(this)) {
            AppPrefs.setVibrate(this, it)
        })

        root.addView(sectionTitle("后台运行说明"))
        root.addView(text("首次安装后，请打开：设置 → 应用 → SMS-L → 电池 → 不受限制。Samsung One UI 的省电策略可能会停止长期后台监听。", 14f).apply {
            setLineSpacing(0f, 1.25f)
        })
        root.addView(button("打开电池设置") { openBatterySettings() }, marginTop(10))

        return scroll
    }

    private fun showPage(page: AppNavigation.Page) {
        selectedPage = page
        messagesPage.visibility = if (page == AppNavigation.Page.MESSAGES) View.VISIBLE else View.GONE
        settingsPage.visibility = if (page == AppNavigation.Page.SETTINGS) View.VISIBLE else View.GONE
        styleNavButton(messagesNavButton, page == AppNavigation.Page.MESSAGES)
        styleNavButton(settingsNavButton, page == AppNavigation.Page.SETTINGS)
        if (page == AppNavigation.Page.MESSAGES) refreshHistory() else refreshStatus()
    }

    private fun styleNavButton(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) Color.rgb(14, 77, 100) else Color.rgb(90, 90, 90))
        button.setTypeface(button.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        button.setBackgroundColor(Color.WHITE)
    }

    private fun showHistoryMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("清空全部记录")
            setOnMenuItemClickListener {
                confirmClearHistory()
                true
            }
            show()
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setMessage("确定清空全部短信记录？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                historyRepository.clearAll { toast("短信记录已清空") }
            }
            .show()
    }

    private fun copyOtp(otp: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("验证码", otp))
        toast("验证码已复制")
    }

    private fun refreshHistory() {
        if (!::historyRepository.isInitialized) return
        historyRepository.loadAll { messages ->
            historyAdapter.submitList(messages)
            val isEmpty = messages.isEmpty()
            emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
            historyList.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun refreshStatus() {
        if (!::portInput.isInitialized) return
        val ip = WifiIpResolver.currentWifiIpv4(this)
        val port = portInput.text.toString().toIntOrNull() ?: AppPrefs.port(this)
        portSummaryView.text = "端口：$port"
        addressView.text = if (ip == null) {
            "局域网 IP：未连接 Wi-Fi"
        } else {
            "局域网 IP：$ip"
        }
        endpointView.text = if (ip == null) {
            "iPhone 请求地址：不可用"
        } else {
            "iPhone 请求地址：http://$ip:$port/sms"
        }
        if (RelayService.isRunning) {
            statusView.text = "服务状态：运行中"
            statusView.setTextColor(Color.rgb(19, 120, 80))
            messageServiceStatus.visibility = View.GONE
        } else {
            statusView.text = "服务状态：已停止"
            statusView.setTextColor(Color.rgb(180, 55, 55))
            messageServiceStatus.visibility = View.VISIBLE
        }
    }

    private fun registerWifiNetworkCallback() {
        if (wifiNetworkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshStatusOnUiThread()
            override fun onLost(network: Network) = refreshStatusOnUiThread()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                refreshStatusOnUiThread()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                refreshStatusOnUiThread()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
            wifiNetworkCallback = callback
        } catch (_: RuntimeException) {
            wifiNetworkCallback = null
        }
    }

    private fun unregisterWifiNetworkCallback() {
        val callback = wifiNetworkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // Callback may already be gone while the Activity is stopping.
        }
        wifiNetworkCallback = null
    }

    private fun refreshStatusOnUiThread() {
        runOnUiThread { refreshStatus() }
    }

    private fun saveConfiguration(): Boolean {
        val token = tokenInput.text.toString().trim()
        if (token.length < 24) {
            tokenInput.error = "Token 至少需要 24 个字符"
            return false
        }
        val port = portInput.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            portInput.error = "端口需为 1024–65535"
            return false
        }
        AppPrefs.setToken(this, token)
        AppPrefs.setPort(this, port)
        return true
    }

    private fun startRelayService() {
        val intent = Intent(this, RelayService::class.java).setAction(RelayService.ACTION_START)
        try {
            startForegroundService(intent)
            refreshStatus()
        } catch (error: RuntimeException) {
            toast("无法启动服务：${error.javaClass.simpleName}")
        }
    }

    private fun stopRelayService() {
        AppPrefs.setServiceEnabled(this, false)
        if (RelayService.isRunning) {
            startService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_STOP))
        }
        refreshStatus()
    }

    private fun ensureNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAction = action
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        val action = pendingAction
        pendingAction = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            action?.invoke()
        } else {
            toast("未授予通知权限，收到短信时无法显示通知")
        }
    }

    private fun openBatterySettings() {
        val direct = Intent("android.settings.APP_BATTERY_SETTINGS")
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(direct)
        } catch (_: Exception) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun sectionTitle(value: String) = text(value, 19f, Typeface.BOLD).apply {
        setTextColor(Color.rgb(14, 77, 100))
        setPadding(0, dp(26), 0, dp(10))
    }

    private fun text(value: String, size: Float, style: Int = Typeface.NORMAL) = TextView(this).apply {
        text = value
        textSize = size
        setTypeface(typeface, style)
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(value: String, checked: Boolean, listener: (Boolean) -> Unit) = Switch(this).apply {
        text = value
        isChecked = checked
        textSize = 16f
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, isChecked -> listener(isChecked) }
    }

    private fun viewButton(value: String, action: (View) -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener(action)
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun navButton(value: String, action: () -> Unit) = button(value, action).apply {
        textSize = 15f
        minHeight = 0
        minimumHeight = 0
    }

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun frameMatch() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun fullWidth(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        height
    )

    private fun weighted(marginStart: Int = 0) = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f
    ).apply { this.marginStart = dp(marginStart) }

    private fun marginTop(value: Int) = fullWidth().apply { topMargin = dp(value) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_RESTORE_SERVICE = "restore_service"
        private const val REQUEST_NOTIFICATIONS = 50
    }
}
