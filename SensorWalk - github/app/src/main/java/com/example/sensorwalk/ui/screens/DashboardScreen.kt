// 文件: app/src/main/java/com/example/sensorwalk/ui/screens/DashboardScreen.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 核心重构(需求6, 8): 将原设置页功能（模式、腿侧选择）整合至此界面，并新增了首次启动的免责声明弹窗，全面优化了UI布局和交互逻辑。
 * 2025-07-30 - Gemini-AI - 界面与交互重构。整合原设置页功能至此屏幕，新增`AnalysisModeSelector`和`LegSelector`等Composable（需求6）。新增`DisclaimerDialog`以处理首次启动提示（需求8）。使用`AnimatedVisibility`动态展示联机面板，将所有控件置于可滚动列中，优化了整体布局和交互流畅性。
 * 2025-07-30 - Gemini-AI - [V5] 完善免责声明。根据需求文档微调了首次启动弹窗的提示文本，使其内容更完整、表述更准确。
 * 2025-07-30 - Gemini-AI - [V6] 致命错误修复 (联机超时): 彻底修复了联机模式下主机因过早点击“开始”而导致从机确认超时的BUG。通过引入明确的UI状态判断，确保“开始分析”按钮在主机模式下仅当从机连接成功后才可用，在从机模式下始终禁用，从根源上杜绝了错误时序的发生。
 */
package com.example.sensorwalk.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.sensorwalk.connectivity.ConnectionState
import com.example.sensorwalk.ui.Destinations
import com.example.sensorwalk.viewmodel.AnalysisMode
import com.example.sensorwalk.viewmodel.LegSide
import com.example.sensorwalk.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.showDisclaimer) {
        DisclaimerDialog(onConfirm = { viewModel.onDisclaimerConfirmed() })
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToResultEvent.collectLatest { resultId ->
            navController.navigate("${Destinations.RESULT_DETAILS}/$resultId") {
                popUpTo(Destinations.DASHBOARD)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("步态分析") }) },
        bottomBar = { AppBottomNavBar(navController = navController, currentRoute = Destinations.DASHBOARD) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // --- 核心操作区 ---
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = uiState.statusText,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
                lineHeight = 36.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.isRecording) {
                if (uiState.isWalking) {
                    Text("检测到行走，正在记录...", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("请开始行走以记录数据...", color = MaterialTheme.colorScheme.tertiary, fontSize = 16.sp)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            // ★★★ 核心修复: 按钮启用/禁用逻辑重构 ★★★
            val isActionEnabled = when {
                // 如果正在分析或等待，始终禁用
                uiState.isAnalyzing || uiState.isWaitingForPeer -> false
                // 如果正在录制，则可以停止，所以启用
                uiState.isRecording -> true
                // 如果是从机模式，从机不能主动开始，始终禁用
                uiState.analysisMode == AnalysisMode.PAIRED_CLIENT -> false
                // 如果是主机模式，仅当有客户端连接时才可开始
                uiState.analysisMode == AnalysisMode.PAIRED_HOST -> uiState.connectionState is ConnectionState.Connected
                // 如果是单机模式，随时可以开始
                uiState.analysisMode == AnalysisMode.SINGLE -> true
                // 其他情况默认为禁用
                else -> false
            }

            Button(
                onClick = { viewModel.toggleAnalysis() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                enabled = isActionEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                if (uiState.isAnalyzing || uiState.isWaitingForPeer) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("处理中...")
                } else {
                    Text(
                        text = if (uiState.isRecording) "停止并分析" else "开始分析",
                        fontSize = 18.sp
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            // --- 设置区 ---
            val isUiEnabled = !uiState.isRecording && !uiState.isAnalyzing && !uiState.isWaitingForPeer

            // 分析模式选择
            Text("1. 选择分析模式", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            AnalysisModeSelector(
                currentMode = uiState.analysisMode,
                onModeChange = { viewModel.setAnalysisMode(it) },
                enabled = isUiEnabled
            )

            Spacer(Modifier.height(24.dp))

            // 腿侧选择
            Text("2. 选择腿侧", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            LegSelector(
                currentSelection = uiState.legSelection,
                onSelectionChange = { viewModel.setLegSelection(it) },
                analysisMode = uiState.analysisMode,
                enabled = isUiEnabled
            )

            // 联机面板 (仅在联机模式下显示)
            AnimatedVisibility(
                visible = uiState.analysisMode != AnalysisMode.SINGLE,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(24.dp))
                    Text("3. 联机操作", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    ConnectionPanel(
                        connectionState = uiState.connectionState,
                        onHostClick = { viewModel.startHosting() },
                        onJoinClick = { viewModel.startJoining() },
                        onDisconnectClick = { viewModel.disconnect() },
                        isRecordingOrAnalyzing = uiState.isRecording || uiState.isAnalyzing
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                text = uiState.sensorInfoText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun DisclaimerDialog(onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 不允许通过点击外部来关闭 */ },
        title = { Text(text = "重要提示", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. 本应用不会收集任何个人消息，只做传感器数据采集和分析。")
                Text("2. 本应用仿写医院步态采集系统，通过手机传感器数据来进行分析。")
                Text("3. 分析结果只能作为参考，由于佩戴和传感器限制，采集精度有限。")
                Text("4. 尽量贴合身体进行采集。")
                Text("5. 联机功能需在同一局域网（WiFi）下，或由一部手机为另一部开启热点。")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("我已阅读并确认")
            }
        }
    )
}

@Composable
fun AnalysisModeSelector(currentMode: AnalysisMode, onModeChange: (AnalysisMode) -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .selectable(
                    selected = (currentMode == AnalysisMode.SINGLE),
                    onClick = { onModeChange(AnalysisMode.SINGLE) },
                    enabled = enabled
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = (currentMode == AnalysisMode.SINGLE),
                onClick = { onModeChange(AnalysisMode.SINGLE) },
                enabled = enabled
            )
            Spacer(Modifier.width(8.dp))
            Text("单机模式")
        }
        Spacer(Modifier.width(24.dp))
        Row(
            modifier = Modifier
                .selectable(
                    selected = (currentMode != AnalysisMode.SINGLE),
                    onClick = { onModeChange(AnalysisMode.PAIRED_HOST) }, // 默认切到主机
                    enabled = enabled
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = (currentMode != AnalysisMode.SINGLE),
                onClick = { onModeChange(AnalysisMode.PAIRED_HOST) },
                enabled = enabled
            )
            Spacer(Modifier.width(8.dp))
            Text("联机模式")
        }
    }
}


@Composable
fun LegSelector(
    currentSelection: LegSide,
    onSelectionChange: (LegSide) -> Unit,
    analysisMode: AnalysisMode,
    enabled: Boolean
) {
    val title = when (analysisMode) {
        AnalysisMode.SINGLE -> "选择要分析的腿："
        AnalysisMode.PAIRED_HOST -> "选择主机（本机）对应的腿："
        AnalysisMode.PAIRED_CLIENT -> "腿部选择由主机分配"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))

        if (analysisMode != AnalysisMode.PAIRED_CLIENT) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = (currentSelection == LegSide.LEFT),
                            onClick = { onSelectionChange(LegSide.LEFT) },
                            enabled = enabled
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (currentSelection == LegSide.LEFT),
                        onClick = { onSelectionChange(LegSide.LEFT) },
                        enabled = enabled
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("左腿")
                }
                Spacer(Modifier.width(24.dp))
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = (currentSelection == LegSide.RIGHT),
                            onClick = { onSelectionChange(LegSide.RIGHT) },
                            enabled = enabled
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (currentSelection == LegSide.RIGHT),
                        onClick = { onSelectionChange(LegSide.RIGHT) },
                        enabled = enabled
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("右腿")
                }
            }
        }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val (statusText, statusColor) = when (connectionState) {
                is ConnectionState.Connected -> (if (connectionState.isHost) "已作为主机连接" else "已连接到主机") to MaterialTheme.colorScheme.primary
                is ConnectionState.Connecting -> connectionState.message to MaterialTheme.colorScheme.secondary
                is ConnectionState.Disconnected -> connectionState.reason to MaterialTheme.colorScheme.error
                is ConnectionState.Discovering -> connectionState.message to MaterialTheme.colorScheme.secondary
                is ConnectionState.Error -> "错误: ${connectionState.message}" to MaterialTheme.colorScheme.error
                ConnectionState.Idle -> "请选择一个角色" to LocalContentColor.current
                ConnectionState.StartingServer -> "正在启动主机..." to MaterialTheme.colorScheme.secondary
                is ConnectionState.WaitingForClient -> connectionState.message to MaterialTheme.colorScheme.primary
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            val buttonsEnabled = !isRecordingOrAnalyzing

            if (connectionState is ConnectionState.Idle || connectionState is ConnectionState.Disconnected || connectionState is ConnectionState.Error) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onHostClick, enabled = buttonsEnabled, modifier = Modifier.weight(1f)) {
                        Text("我当主机")
                    }
                    Button(onClick = onJoinClick, enabled = buttonsEnabled, modifier = Modifier.weight(1f)) {
                        Text("我当从机")
                    }
                }
            } else {
                Button(
                    onClick = onDisconnectClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("断开连接")
                }
            }
        }
    }
}
