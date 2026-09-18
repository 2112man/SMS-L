package com.localsmsrelay.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localsmsrelay.AppPrefs
import com.localsmsrelay.RelayConnectionState

@Composable
fun SettingsPage(
    state: RelayUiState,
    contentPadding: PaddingValues,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val context = LocalContext.current

    // 编辑态放在本地，点「保存」才写回，避免每次输入都触发服务重启
    var serverInput by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var androidTokenInput by remember(state.androidToken) { mutableStateOf(state.androidToken) }
    var lanTokenInput by remember(state.lanToken) { mutableStateOf(state.lanToken) }
    var portInput by remember(state.port) { mutableStateOf(state.port) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
                start = 12.dp,
                end = 12.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusCard(state = state)

        // ---- 接收方式 ----
        SectionTitle("接收方式")
        SettingsCard {
            SwitchRow(
                title = "使用 Cloudflare 云端中继",
                subtitle = "关闭后回落到局域网模式，iPhone 需与手机处于同一 Wi-Fi",
                checked = state.useCloudflare,
                onCheckedChange = state::applyUseCloudflare
            )
        }

        if (state.useCloudflare) {
            SectionTitle("Cloudflare 配置")
            SettingsCard {
                OutlinedTextField(
                    value = serverInput,
                    onValueChange = { serverInput = it },
                    label = { Text("服务器地址") },
                    supportingText = { Text("填 Worker 地址即可，wss:// 与 /ws 会自动补全") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = androidTokenInput,
                    onValueChange = { androidTokenInput = it },
                    label = { Text("Android Token") },
                    supportingText = { Text("必须与 Worker 的 ANDROID_TOKEN secret 完全一致") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { state.copyText("Android Token", androidTokenInput) }) {
                        Text("复制")
                    }
                    TextButton(onClick = {
                        androidTokenInput = state.regenerateAndroidToken()
                    }) {
                        Text("重新生成")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (!state.saveCloudflare(serverInput, androidTokenInput)) {
                            state.toast("地址或 Token 不合法")
                        }
                    }) {
                        Text("保存")
                    }
                }
            }
        } else {
            SectionTitle("局域网配置")
            SettingsCard {
                OutlinedTextField(
                    value = lanTokenInput,
                    onValueChange = { lanTokenInput = it },
                    label = { Text("Token") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    label = { Text("端口") },
                    supportingText = { Text("1024–65535，默认 8765") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { state.copyText("Token", lanTokenInput) }) { Text("复制 Token") }
                    TextButton(onClick = { lanTokenInput = state.regenerateLanToken() }) { Text("重新生成") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (!state.saveLan(lanTokenInput, portInput)) {
                            state.toast("Token 至少 ${AppPrefs.MIN_TOKEN_LENGTH} 位，端口需在 1024–65535")
                        }
                    }) {
                        Text("保存")
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                val endpoint = state.lanEndpoint()
                InfoRow(
                    title = "iPhone 请求地址",
                    value = endpoint ?: "不可用（未连接 Wi-Fi）",
                    onCopy = if (endpoint != null) ({ state.copyText("请求地址", endpoint) }) else null
                )
            }
        }

        // ---- 服务控制 ----
        SectionTitle("服务")
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartService,
                    modifier = Modifier.weight(1f)
                ) { Text("启动服务") }
                OutlinedButton(
                    onClick = onStopService,
                    modifier = Modifier.weight(1f)
                ) { Text("停止服务") }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            TextButton(onClick = state::sendTestNotification) { Text("发送测试通知") }
        }

        // ---- 通知 ----
        SectionTitle("通知")
        SettingsCard {
            SwitchRow(
                title = "收到短信时震动",
                checked = state.vibrate,
                onCheckedChange = state::applyVibrate
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            SwitchRow(
                title = "验证码自动识别",
                subtitle = "从短信正文中提取 4–8 位验证码",
                checked = state.otpEnabled,
                onCheckedChange = state::applyOtpEnabled
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            TextButton(onClick = { openNotificationSettings(context) }) {
                Text("打开系统通知设置")
            }
        }

        // ---- 后台运行 ----
        SectionTitle("后台运行")
        SettingsCard {
            SwitchRow(
                title = "开机自动启动",
                subtitle = "设备重启后自动恢复服务；未开启时打开 App 也会自动恢复",
                checked = state.autoStart,
                onCheckedChange = state::applyAutoStart
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text(
                text = "HyperOS / MIUI 的省电策略可能中断后台连接。建议把本应用的「自启动」打开、" +
                    "省电策略设为「无限制」，否则息屏后连接可能被切断。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { openBatterySettings(context) }) {
                Text("打开电池设置")
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(state: RelayUiState) {
    val (title, container) = when {
        !state.serviceRunning -> "服务未启动" to MaterialTheme.colorScheme.errorContainer
        state.connectionState == RelayConnectionState.CONNECTED ->
            "已连接云端" to MaterialTheme.colorScheme.primaryContainer
        state.connectionState == RelayConnectionState.UNAUTHORIZED ->
            "认证失败" to MaterialTheme.colorScheme.errorContainer
        state.connectionState == RelayConnectionState.RECONNECTING ->
            "重连中" to MaterialTheme.colorScheme.secondaryContainer
        state.connectionState == RelayConnectionState.LAN_LISTENING ->
            "局域网监听中" to MaterialTheme.colorScheme.primaryContainer
        else -> "连接中" to MaterialTheme.colorScheme.secondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (state.connectionDetail.isNotBlank()) {
                Text(
                    text = state.connectionDetail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (!state.useCloudflare && state.wifiIpv4 != null) {
                Text(
                    text = "局域网 IP：${state.wifiIpv4}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(title: String, value: String, onCopy: (() -> Unit)? = null) {
    Column {
        Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            if (onCopy != null) {
                TextButton(onClick = onCopy) { Text("复制") }
            }
        }
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { openAppDetails(context) }
}

private fun openBatterySettings(context: android.content.Context) {
    val direct = Intent("android.settings.APP_BATTERY_SETTINGS")
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }
        .onFailure { openAppDetails(context) }
}

private fun openAppDetails(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
