package com.localsmsrelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localsmsrelay.AppNavigation
import com.localsmsrelay.MessageTimeFormatter
import com.localsmsrelay.RelayConnectionState
import com.localsmsrelay.RelayService
import com.localsmsrelay.data.SmsMessageEntity

@Composable
fun MessagesPage(
    state: RelayUiState,
    contentPadding: PaddingValues,
    onStartService: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.messages.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                    start = 12.dp,
                    end = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { ServiceBanner(state = state, onStartService = onStartService) }
                items(state.messages, key = { it.id }) { message ->
                    MessageCard(
                        message = message,
                        onCopyOtp = state::copyOtp,
                        onClick = { if (!message.isRead) state.markRead(message.id) }
                    )
                }
            }
        }

        // 列表为空时仍要看到服务状态
        if (state.messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        start = 12.dp,
                        end = 12.dp
                    )
            ) {
                ServiceBanner(state = state, onStartService = onStartService)
            }
        }
    }
}

@Composable
private fun ServiceBanner(state: RelayUiState, onStartService: () -> Unit) {
    val running = state.serviceRunning
    val (title, detail, container) = when {
        !running -> Triple(
            "服务未启动",
            "点右侧按钮开始接收短信",
            MaterialTheme.colorScheme.errorContainer
        )

        state.connectionState == RelayConnectionState.CONNECTED ->
            Triple("已连接云端", "短信将通过 WebSocket 实时推送", MaterialTheme.colorScheme.primaryContainer)

        state.connectionState == RelayConnectionState.UNAUTHORIZED ->
            Triple("认证失败", state.connectionDetail.ifEmpty { "请检查 Android Token" }, MaterialTheme.colorScheme.errorContainer)

        state.connectionState == RelayConnectionState.RECONNECTING ->
            Triple("重连中", state.connectionDetail.ifEmpty { "网络恢复后会自动重连" }, MaterialTheme.colorScheme.secondaryContainer)

        state.connectionState == RelayConnectionState.LAN_LISTENING ->
            Triple("局域网监听中", state.connectionDetail, MaterialTheme.colorScheme.primaryContainer)

        else -> Triple("连接中", state.connectionDetail, MaterialTheme.colorScheme.secondaryContainer)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.NotificationsActive,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (detail.isNotBlank()) {
                    Text(text = detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!running) {
                Button(onClick = onStartService) { Text("启动") }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = AppNavigation.EMPTY_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = AppNavigation.EMPTY_SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun MessageCard(
    message: SmsMessageEntity,
    onCopyOtp: (String) -> Unit,
    onClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            onClick()
            expanded = !expanded
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!message.isRead) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .padding(end = 0.dp)
                    ) {
                        Card(
                            modifier = Modifier.size(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {}
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = message.sender?.takeIf { it.isNotBlank() } ?: "iPhone 短信",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = MessageTimeFormatter.format(message.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis
            )

            val otp = message.otp
            if (!otp.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = otp,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onCopyOtp(otp) }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("复制")
                    }
                }
            }
        }
    }
}
