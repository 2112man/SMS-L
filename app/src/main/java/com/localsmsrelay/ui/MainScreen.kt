package com.localsmsrelay.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.localsmsrelay.AppPrefs
import com.localsmsrelay.RelayService

private const val PAGE_MESSAGES = 0
private const val PAGE_SETTINGS = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: RelayUiState) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(PAGE_MESSAGES) }
    var showClearDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            state.startService()
        } else {
            state.toast("未授予通知权限，收到短信时无法显示通知")
        }
    }

    fun startServiceWithPermission() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            state.startService()
        }
    }

    // 用户此前启用过服务，但进程被系统回收或「强行停止」导致服务没在跑时，自动恢复。
    // 用户主动点过「停止服务」时 serviceEnabled 已为 false，这里不会误启动。
    LaunchedEffect(Unit) {
        if (AppPrefs.serviceEnabled(context) && !RelayService.isRunning) {
            startServiceWithPermission()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (page == PAGE_MESSAGES) "SMS-L" else "设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (page == PAGE_MESSAGES && state.messages.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空全部记录")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = page == PAGE_MESSAGES,
                    onClick = {
                        page = PAGE_MESSAGES
                        state.markAllRead()
                    },
                    icon = { Icon(Icons.Filled.Forum, contentDescription = null) },
                    label = { Text("消息") }
                )
                NavigationBarItem(
                    selected = page == PAGE_SETTINGS,
                    onClick = { page = PAGE_SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        when (page) {
            PAGE_MESSAGES -> MessagesPage(
                state = state,
                contentPadding = padding,
                onStartService = ::startServiceWithPermission
            )

            else -> SettingsPage(
                state = state,
                contentPadding = padding,
                onStartService = ::startServiceWithPermission,
                onStopService = state::stopService
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空全部记录") },
            text = { Text("确定要清空全部短信记录吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    state.clearHistory()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }
}
