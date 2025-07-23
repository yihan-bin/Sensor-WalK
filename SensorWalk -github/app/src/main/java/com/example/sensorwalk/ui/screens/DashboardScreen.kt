package com.example.sensorwalk.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
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

    // ★★★ 新增 [需求 2]: 免责声明弹窗 ★★★
    if (uiState.showDisclaimer) {
        DisclaimerDialog(onConfirm = { viewModel.onDisclaimerConfirmed() })
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToResultEvent.collectLatest { resultId ->
            navController.navigate("${Destinations.RESULT_DETAILS}/$resultId") {
                // 清除当前屏幕，避免返回时再次触发
                popUpTo(Destinations.DASHBOARD) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("步态分析主页") }) },
        bottomBar = { AppBottomNavBar(navController = navController, currentRoute = Destinations.DASHBOARD) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                // ★★★ 优化 [需求 4, 5]: 仅在空闲时显示腿部选择器 ★★★
                if (!uiState.isRecording && !uiState.isAnalyzing && !uiState.isWaitingForRemote) {
                    LegSelector(
                        currentSelection = uiState.legSelection,
                        onSelectionChange = { viewModel.setLegSelection(it) },
                        analysisMode = uiState.analysisMode
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }


                Text(
                    text = uiState.statusText,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    lineHeight = 30.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (uiState.isRecording && uiState.isWalking) {
                    Text("检测到走路，正在记录...", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                } else if (uiState.isRecording && !uiState.isWalking) {
                    Text("已暂停，请开始走路...", color = MaterialTheme.colorScheme.tertiary, fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.height(40.dp))

                Button(
                    onClick = { viewModel.toggleAnalysis(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    // ★★★ 优化 [需求 4.5]: 从机连接后，此按钮由主机控制 ★★★
                    enabled = !uiState.isAnalyzing && !uiState.isWaitingForRemote && !uiState.isStartButtonDisabledForClient,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (uiState.isAnalyzing || uiState.isWaitingForRemote) {
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
            }

            // ★★★ 新增 [需求 13]: 显示传感器信息 ★★★
            Text(
                text = uiState.sensorInfoText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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
        // ★★★ 优化 [需求 2]: 使用您指定的准确文本 ★★★
        text = {
            Text(
                text = "1. 本应用除了收集传感器数据用于步态分析，不会采集其他任何用户信息和上传任何东西。\n\n" +
                        "2. 由于不同手机传感器精度和采集速率的影响，本产品分析的数据，仅作为参考，没有其他实际医疗或者纠正意义。\n\n" +
                        "3. 本应用采集精度受到是否紧贴大腿的影响。\n\n" +
                        "4. 本应用为个人开发者开发，仅作参考。\n\n"+
                        "5.联机功能需要在同一个局域网下才能进行，或者一个手机给另外一个手机开热点",
                lineHeight = 22.sp // 增加行高以提高可读性
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("我已阅读并确认")
            }
        }
    )
}

@Composable
fun LegSelector(
    currentSelection: LegSide,
    onSelectionChange: (LegSide) -> Unit,
    analysisMode: AnalysisMode
) {
    val title = when (analysisMode) {
        AnalysisMode.SINGLE -> "选择要分析的腿："
        AnalysisMode.PAIRED_HOST -> "选择主机（本机）对应的腿："
        AnalysisMode.PAIRED_CLIENT -> "腿部选择由主机分配"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        // ★★★ 优化 [需求 4.7]: 从机模式下不显示选项 ★★★
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
                            onClick = { onSelectionChange(LegSide.LEFT) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (currentSelection == LegSide.LEFT),
                        onClick = { onSelectionChange(LegSide.LEFT) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("左腿")
                }
                Spacer(Modifier.width(24.dp))
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = (currentSelection == LegSide.RIGHT),
                            onClick = { onSelectionChange(LegSide.RIGHT) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (currentSelection == LegSide.RIGHT),
                        onClick = { onSelectionChange(LegSide.RIGHT) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("右腿")
                }
            }
        }
    }
}
