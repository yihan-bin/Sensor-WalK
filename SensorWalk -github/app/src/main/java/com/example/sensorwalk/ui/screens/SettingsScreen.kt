package com.example.sensorwalk.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.sensorwalk.connectivity.ConnectionState
import com.example.sensorwalk.ui.Destinations
import com.example.sensorwalk.viewmodel.AnalysisMode
import com.example.sensorwalk.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    // ★★★ 核心修复：移除了对不存在的 `ensureConnectionManagerInitialized` 函数的调用。 ★★★
    // ConnectionManager 是一个由 Hilt 管理的单例，其生命周期与应用相同，无需手动“初始化”。

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置与联机") }) },
        bottomBar = { AppBottomNavBar(navController = navController, currentRoute = Destinations.SETTINGS) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("分析模式", style = MaterialTheme.typography.titleLarge)
            Column {
                ModeSelector(
                    label = "单机模式 (单腿分析)",
                    isSelected = uiState.analysisMode == AnalysisMode.SINGLE,
                    // 优化: 只有在未采集中才能切换
                    enabled = !uiState.isRecording && !uiState.isAnalyzing
                ) {
                    viewModel.setAnalysisMode(AnalysisMode.SINGLE)
                }
                ModeSelector(
                    label = "联机模式 (双腿对比)",
                    isSelected = uiState.analysisMode != AnalysisMode.SINGLE,
                    enabled = !uiState.isRecording && !uiState.isAnalyzing
                ) {
                    // 默认为主机模式
                    viewModel.setAnalysisMode(AnalysisMode.PAIRED_HOST)
                }
            }

            HorizontalDivider()

            if (uiState.analysisMode != AnalysisMode.SINGLE) {
                ConnectionPanel(
                    connectionState = uiState.connectionState,
                    onHostClick = { viewModel.startHosting() },
                    onJoinClick = { viewModel.startJoining() },
                    onDisconnectClick = { viewModel.disconnect() },
                    isRecordingOrAnalyzing = uiState.isRecording || uiState.isAnalyzing
                )
            }
        }
    }
}

@Composable
fun ModeSelector(label: String, isSelected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onClick,
                enabled = enabled
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            enabled = enabled
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}

@Composable
fun ConnectionPanel(
    connectionState: ConnectionState,
    onHostClick: () -> Unit,
    onJoinClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    isRecordingOrAnalyzing: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("联机状态", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Text(
            text = when(connectionState) {
                is ConnectionState.Connected -> if(connectionState.isHost) "已作为主机连接" else "已连接到主机"
                is ConnectionState.Connecting -> connectionState.message
                is ConnectionState.Disconnected -> connectionState.reason
                is ConnectionState.Discovering -> connectionState.message
                is ConnectionState.Error -> "错误: ${connectionState.message}"
                ConnectionState.Idle -> "请选择一个角色"
                ConnectionState.StartingServer -> "正在启动主机..."
                is ConnectionState.WaitingForClient -> connectionState.message
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(24.dp))

        if (connectionState is ConnectionState.Idle || connectionState is ConnectionState.Disconnected || connectionState is ConnectionState.Error) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onHostClick, enabled = !isRecordingOrAnalyzing, modifier = Modifier.weight(1f)) {
                    Text("我当主机")
                }
                Button(onClick = onJoinClick, enabled = !isRecordingOrAnalyzing, modifier = Modifier.weight(1f)) {
                    Text("我当从机")
                }
            }
        } else {
            Button(
                onClick = onDisconnectClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRecordingOrAnalyzing
            ) {
                Text("断开连接")
            }
        }
    }
}
