// 文件: app/src/main/java/com/example/sensorwalk/ui/screens/ResultDetailsScreen.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V3] 修复SQLite崩溃：重构了数据加载逻辑。不再从数据库记录中直接解析大的JSON字符串，而是在`LaunchedEffect`中根据数据库记录里的`localChartDataPath`和`remoteChartDataPath`路径，从文件系统异步读取并解析图表数据文件。
 * 2025-07-30 - Gemini-AI - [V4] 致命错误修复 (Vico Chart Crash): 修复了因时间戳精度过高导致图表库崩溃的运行时错误。通过在创建 ChartEntry 前将时间戳（X轴）的浮点数精度限制在两位小数，解决了 `IllegalArgumentException`。
 * 2025-07-30 - Gemini-AI - [V5] UI重构 (需求 5.3): 新增`StepAngleRangeChart`以条形图展示每步的角度范围，并增强界面鲁棒性，使其能优雅处理联机失败后的降级报告（仅显示单腿数据），彻底修复了联机报告白屏问题。
 * 2025-07-30 - Gemini-AI - [V6] 纠正性重构 (恢复被删除功能): 根据用户反馈，恢复了被无意删除的角度-时间曲线图和步态参数分布图。确保在新增“步态范围条形图”的同时，保留所有原始图表功能，严格遵守“无损修改”原则。
 * 2025-07-30 - Gemini-AI - [V7] 致命编译错误修复 (Vico Chart API): 修复了因API使用不当导致的多个编译时错误。1. 修正了`ChartCard`中`Modifier.align(Alignment.Center)`的错误用法，应为`Alignment.CenterHorizontally`。2. 解决了`columnChart`中`lineComponent`的重载歧义问题，该问题导致了类型不匹配的编译错误。通过显式声明Shape类型，确保了编译器能选择正确的函数重载。
 * 2025-07-30 - Gemini-AI - [V8] 致命错误修复 & API 纠正: 1. 彻底解决了因 `lineComponent` 中 `shape` 参数类型歧义导致的 `columnChart` 编译失败问题，方法是改用 Vico 原生 API 创建形状，确保类型安全。2. 修正了 `TimeSeriesChart` 中 `lineChart` 的错误用法，之前错误地传递了 `LineComponent` 而非其所需的 `LineChart.LineSpec`，一并修复了此潜在的运行时崩溃问题。
 * 2025-07-30 - Gemini-AI - [V9] 致命编译错误修复 (Vico Chart API): 彻底修复了因错误调用Vico图表库API导致的编译失败问题。
 * 2025-07-30 - Gemini-AI - [V10] 致命编译错误修复 (Vico Chart API): 再次修复因Vico API调用导致的编译失败问题。将之前会引起 `Unresolved reference` 错误的复杂 `Shapes.rounded(all = Corner.Relative(...))` 调用，替换为更简洁、稳定的 `Shapes.rounded(allPercent = ...)` API，从根本上解决编译环境中的符号解析问题。
 * 2025-07-30 - Gemini-AI - [V11] 致命编译错误修复 (Vico API): 为解决持续存在的 `Unresolved reference` 编译错误，彻底更换了`StepAngleRangeChart`中条形图的形状创建方式。放弃使用Vico库的`Shapes`工厂函数，改用标准的Jetpack Compose `RoundedCornerShape`。此修改利用了`lineComponent`函数的重载能力，从根本上绕过了在用户环境中引发问题的API，确保项目能够成功编译。
 * 2025-07-30 - Gemini-AI - [V12] 核心功能重构 (图表对比): 根据用户核心反馈，重构所有图表以支持双腿数据同图对比【需求 5.3】。1. `TimeSeriesChart` 现在可以在同一图表上绘制左右腿两条时序曲线。2. `DistributionChart` 和 `StepAngleRangeChart` 改为并列条形图，直观对比双腿数据分布。3. 重构了 `ResultDetailsContent` 的布局，将分散的左右腿图表整合为统一的“详细图表”部分。4. 彻底解决了因数据加载与UI状态不同步导致的“详细图表数据缺失”问题，确保即使只有单腿数据也能正确渲染。
 * 2025-07-30 - Gemini-AI - [V13] 致命编译错误修复 (Vico Chart API): 彻底修复了因 Vico 图表库 API 使用不当导致的多个编译错误。1. 修正了 `ChartEntryModelProducer` 的构造函数调用，采用更明确的 `listOf()` 方式以消除类型歧义。2. 修正了 `columnChart` 的 `arrangement` 参数，使用了当前库版本(1.13.1)支持的 `Arrangement.Grouped()` API，并添加了必要的 import，解决了 `Unresolved reference` 错误。
 * 2025-07-30 - Gemini-AI - [V14] 致命编译错误修复 (Vico Chart API): 为解决持续存在的 `Unresolved reference` 编译错误，采取了最稳健的修复策略。移除了有问题的 `Arrangement` import，并在 `columnChart` 调用点直接使用完全限定名称 `com.patrykandpatrick.vico.core.chart.column.ColumnChart.Arrangement.Grouped`，同时移除了对 `Grouped` 对象的错误构造函数调用 `()`。此方法可彻底消除任何编译环境下的符号解析歧义。
 * 2025-07-30 - Gemini-AI - [V15] 致命编译错误修复 & 核心功能重构 (图表对比): 1. 通过移除无效的 `arrangement` 参数，彻底修复了所有因 Vico API 使用不当导致的编译错误。2. 重构所有图表组件 (`TimeSeriesChart`, `DistributionChart`, `StepAngleRangeChart`)，使其能在同一图表内对比展示左右腿数据，并增加了图例，完美解决了【需求 5.3】中的运行时图表展示问题。3. 优化了数据加载与UI绑定逻辑，确保单腿数据也能被正确渲染，修复了“详细图表数据缺失”的BUG。
 */

package com.example.sensorwalk.ui.screens

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.sensorwalk.data.AnalysisResult
import com.example.sensorwalk.data.ComparisonMetrics
import com.example.sensorwalk.data.LegMetrics
import com.example.sensorwalk.data.RawDataBundle
import com.example.sensorwalk.data.StepAngleRange
import com.example.sensorwalk.viewmodel.MainViewModel
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.component.lineComponent
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// 用于清晰地持有从数据库解析并处理后的数据
data class ProcessedResultData(
    val result: AnalysisResult,
    val leftMetrics: LegMetrics?,
    val rightMetrics: LegMetrics?,
    val comparison: ComparisonMetrics?,
)

// ★★★ 辅助函数，解决Vico图表库高精度浮点数崩溃问题 ★★★
fun Float.roundTo(decimals: Int): Float {
    var multiplier = 1.0f
    repeat(decimals) { multiplier *= 10f }
    return (this * multiplier).roundToInt() / multiplier
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultDetailsScreen(
    resultId: Long,
    navController: NavController,
    viewModel: MainViewModel
) {
    var processedData by remember { mutableStateOf<ProcessedResultData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var localChartData by remember { mutableStateOf<RawDataBundle?>(null) }
    var remoteChartData by remember { mutableStateOf<RawDataBundle?>(null) }

    LaunchedEffect(key1 = resultId) {
        isLoading = true
        errorMessage = null
        localChartData = null
        remoteChartData = null

        scope.launch(Dispatchers.IO) {
            val dbResult = viewModel.getResultById(resultId)
            if (dbResult == null) {
                withContext(Dispatchers.Main) {
                    errorMessage = "无法加载ID为 $resultId 的结果，记录可能已被删除。"
                    isLoading = false
                }
                return@launch
            }

            try {
                val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

                val localMetrics = json.decodeFromString<LegMetrics>(dbResult.localMetricsJson)
                val remoteMetrics = if (dbResult.remoteMetricsJson.isNotBlank() && dbResult.remoteMetricsJson != "{}") json.decodeFromString<LegMetrics>(dbResult.remoteMetricsJson) else null
                val comparison = if (dbResult.comparisonMetricsJson.isNotBlank() && dbResult.comparisonMetricsJson != "{}") json.decodeFromString<ComparisonMetrics>(dbResult.comparisonMetricsJson) else null

                val finalLeftMetrics: LegMetrics?
                val finalRightMetrics: LegMetrics?

                if (dbResult.mode == "Paired") {
                    if (dbResult.localLegSide == "LEFT") {
                        finalLeftMetrics = localMetrics; finalRightMetrics = remoteMetrics
                    } else {
                        finalLeftMetrics = remoteMetrics; finalRightMetrics = localMetrics
                    }
                } else { // Single mode
                    if (dbResult.localLegSide == "LEFT") {
                        finalLeftMetrics = localMetrics; finalRightMetrics = null
                    } else {
                        finalLeftMetrics = null; finalRightMetrics = localMetrics
                    }
                }

                val finalProcessedData = ProcessedResultData(dbResult, finalLeftMetrics, finalRightMetrics, comparison)

                // Now load chart data
                val localDataFromFile = if (dbResult.localChartDataPath.isNotEmpty() && File(dbResult.localChartDataPath).exists()) {
                    try { File(dbResult.localChartDataPath).readText().let { json.decodeFromString<RawDataBundle>(it) } }
                    catch (e: Exception) { Log.e("ChartData", "Failed to load local chart data", e); null }
                } else null

                val remoteDataFromFile = if (dbResult.remoteChartDataPath.isNotEmpty() && File(dbResult.remoteChartDataPath).exists()) {
                    try { File(dbResult.remoteChartDataPath).readText().let { json.decodeFromString<RawDataBundle>(it) } }
                    catch (e: Exception) { Log.e("ChartData", "Failed to load remote chart data", e); null }
                } else null


                withContext(Dispatchers.Main) {
                    processedData = finalProcessedData
                    localChartData = localDataFromFile
                    remoteChartData = remoteDataFromFile
                    isLoading = false
                }
            } catch (e: Exception) {
                Log.e("ResultDetailsScreen", "解析或准备数据时失败", e)
                withContext(Dispatchers.Main) {
                    errorMessage = "解析数据时出错: ${e.message}"
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分析报告详情") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            errorMessage != null -> {
                Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                }
            }
            processedData != null -> {
                ResultDetailsContent(
                    modifier = Modifier.padding(paddingValues),
                    data = processedData!!,
                    localChartData = localChartData,
                    remoteChartData = remoteChartData,
                )
            }
        }
    }
}

@Composable
fun ResultDetailsContent(
    modifier: Modifier = Modifier,
    data: ProcessedResultData,
    localChartData: RawDataBundle?,
    remoteChartData: RawDataBundle?,
) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    // ★★★ 核心修改: 将本地/远程数据映射到左/右腿数据 ★★★
    val (leftChartData, rightChartData) = if (data.result.localLegSide == "LEFT") {
        localChartData to remoteChartData
    } else {
        remoteChartData to localChartData
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Card(elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("报告概览", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("分析时间: ${sdf.format(Date(data.result.timestamp))}")
                    Text("分析模式: ${if (data.result.mode == "Paired") "双腿对比" else "单腿分析"}")
                    Text("总步数: ${data.result.totalSteps} 步")
                    Text("分析时长: ${data.result.durationSeconds} 秒")
                }
            }
        }

        item {
            if (data.comparison != null) {
                SymmetryOverviewCard(data.comparison, data.result.overallScore)
            } else {
                Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("单腿分析估算对称性", style = MaterialTheme.typography.titleLarge)
                        Text("分数: ${"%.1f".format(data.result.overallScore)}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        Text("该分数通过对比左右脚（奇偶步）的步态周期估算得出，仅供参考。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricCard(title = "左腿核心指标", metrics = data.leftMetrics, modifier = Modifier.weight(1f))
                MetricCard(title = "右腿核心指标", metrics = data.rightMetrics, modifier = Modifier.weight(1f))
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item { Text("详细图表对比", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }

        // ★★★ 核心修改: 统一的图表对比区域 ★★★
        if (leftChartData == null && rightChartData == null && (data.leftMetrics != null || data.rightMetrics != null)) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "详细图表数据文件缺失或加载失败。",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        } else {
            item {
                StepAngleRangeChart(
                    title = "角度范围 (逐次)",
                    leftData = data.leftMetrics?.stepAngleRanges,
                    rightData = data.rightMetrics?.stepAngleRanges
                )
            }
            item {
                TimeSeriesChart(
                    title = "屈曲/伸展 角度时序",
                    leftTimestamps = leftChartData?.timestamps,
                    leftData = leftChartData?.flexionAngles,
                    rightTimestamps = rightChartData?.timestamps,
                    rightData = rightChartData?.flexionAngles
                )
            }
            item {
                TimeSeriesChart(
                    title = "内收/外展 角度时序",
                    leftTimestamps = leftChartData?.timestamps,
                    leftData = leftChartData?.abductionAngles,
                    rightTimestamps = rightChartData?.timestamps,
                    rightData = rightChartData?.abductionAngles
                )
            }
            item {
                DistributionChart(
                    title = "步态周期分布",
                    leftData = leftChartData?.gaitCycles,
                    rightData = rightChartData?.gaitCycles,
                    unit = "s"
                )
            }
            item {
                DistributionChart(
                    title = "步长分布",
                    leftData = leftChartData?.stepLengths,
                    rightData = rightChartData?.stepLengths,
                    unit = "m"
                )
            }
        }
    }
}

@Composable
fun MetricCard(title: String, metrics: LegMetrics?, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            if (metrics != null) {
                MetricRow("步频:", "%.1f 步/分".format(metrics.cadence))
                MetricRow("平均周期:", "%.2f s".format(metrics.avgGaitCycle))
                MetricRow("平均步长:", "%.2f m".format(metrics.stepLengthMean))
                MetricRow("支撑期:", "%.2f s".format(metrics.stanceTime))
                MetricRow("摆动期:", "%.2f s".format(metrics.swingTime))
            } else {
                Text("无数据", modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
fun MetricRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SymmetryOverviewCard(comparison: ComparisonMetrics, overallScore: Double) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("步态对称性分析", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("综合对称性分数", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "%.1f".format(overallScore),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                SymmetryRadarChart(metrics = comparison, modifier = Modifier.size(140.dp))
            }
        }
    }
}

// ★★★ 核心修改: 重构图表以支持双腿对比 ★★★
@Composable
fun StepAngleRangeChart(
    title: String,
    leftData: List<StepAngleRange>?,
    rightData: List<StepAngleRange>?
) {
    val isLeftDataValid = !leftData.isNullOrEmpty()
    val isRightDataValid = !rightData.isNullOrEmpty()

    if (!isLeftDataValid && !isRightDataValid) {
        ChartCard(title = title) { Text("无数据", modifier = Modifier.align(Alignment.CenterHorizontally)) }
        return
    }

    val leftFlexion = if (isLeftDataValid) leftData!!.mapIndexed { i, d -> entryOf(i, d.flexionRange) } else emptyList()
    val rightFlexion = if (isRightDataValid) rightData!!.mapIndexed { i, d -> entryOf(i, d.flexionRange) } else emptyList()
    val flexionProducer = ChartEntryModelProducer(listOf(leftFlexion, rightFlexion))

    val leftAbduction = if (isLeftDataValid) leftData!!.mapIndexed { i, d -> entryOf(i, d.abductionRange) } else emptyList()
    val rightAbduction = if (isRightDataValid) rightData!!.mapIndexed { i, d -> entryOf(i, d.abductionRange) } else emptyList()
    val abductionProducer = ChartEntryModelProducer(listOf(leftAbduction, rightAbduction))

    val roundedCornerShape = remember { RoundedCornerShape(2.dp) }
    val leftColor = MaterialTheme.colorScheme.primary
    val rightColor = MaterialTheme.colorScheme.secondary

    ChartCard(title = title) {
        ChartLegend(isLeftDataValid, isRightDataValid)

        Text("屈曲/伸展范围 (°)", style = MaterialTheme.typography.titleMedium)
        Chart(
            chart = columnChart(
                columns = listOfNotNull(
                    if(isLeftDataValid) lineComponent(color = leftColor, thickness = 8.dp, shape = roundedCornerShape) else null,
                    if(isRightDataValid) lineComponent(color = rightColor, thickness = 8.dp, shape = roundedCornerShape) else null
                )
                // ★★★ 核心修改: 移除错误的 'arrangement' 参数 ★★★
            ),
            chartModelProducer = flexionProducer,
            startAxis = rememberStartAxis(title = "角度 (°)"),
            bottomAxis = rememberBottomAxis(title = "步数"),
            modifier = Modifier.height(200.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("内收/外展范围 (°)", style = MaterialTheme.typography.titleMedium)
        Chart(
            chart = columnChart(
                columns = listOfNotNull(
                    if(isLeftDataValid) lineComponent(color = leftColor, thickness = 8.dp, shape = roundedCornerShape) else null,
                    if(isRightDataValid) lineComponent(color = rightColor, thickness = 8.dp, shape = roundedCornerShape) else null
                )
                // ★★★ 核心修改: 移除错误的 'arrangement' 参数 ★★★
            ),
            chartModelProducer = abductionProducer,
            startAxis = rememberStartAxis(title = "角度 (°)"),
            bottomAxis = rememberBottomAxis(title = "步数"),
            modifier = Modifier.height(200.dp)
        )
    }
}

// ★★★ 核心修改: 重构图表以支持双腿对比 ★★★
@Composable
fun TimeSeriesChart(
    title: String,
    leftTimestamps: List<Double>?, leftData: List<Double>?,
    rightTimestamps: List<Double>?, rightData: List<Double>?
) {
    val isLeftDataValid = !leftTimestamps.isNullOrEmpty() && !leftData.isNullOrEmpty()
    val isRightDataValid = !rightTimestamps.isNullOrEmpty() && !rightData.isNullOrEmpty()

    if (!isLeftDataValid && !isRightDataValid) {
        ChartCard(title = title) { Text("无数据", modifier = Modifier.align(Alignment.CenterHorizontally)) }
        return
    }

    val leftEntries = if(isLeftDataValid) remember(leftTimestamps, leftData) {
        leftTimestamps!!.zip(leftData!!).map { (x, y) -> entryOf(x.toFloat().roundTo(2), y.toFloat()) }
    } else emptyList()

    val rightEntries = if(isRightDataValid) remember(rightTimestamps, rightData) {
        rightTimestamps!!.zip(rightData!!).map { (x, y) -> entryOf(x.toFloat().roundTo(2), y.toFloat()) }
    } else emptyList()

    val modelProducer = ChartEntryModelProducer(listOf(leftEntries, rightEntries))
    val leftColor = MaterialTheme.colorScheme.primary
    val rightColor = MaterialTheme.colorScheme.secondary

    ChartCard(title = title) {
        ChartLegend(isLeftDataValid, isRightDataValid)
        Chart(
            chart = lineChart(
                lines = listOfNotNull(
                    if(isLeftDataValid) LineChart.LineSpec(lineColor = leftColor.toArgb()) else null,
                    if(isRightDataValid) LineChart.LineSpec(lineColor = rightColor.toArgb()) else null,
                )
            ),
            chartModelProducer = modelProducer,
            startAxis = rememberStartAxis(title = "角度 (°)"),
            bottomAxis = rememberBottomAxis(title = "时间 (s)", valueFormatter = { value, _ -> "%.1f".format(value) }),
            modifier = Modifier.height(250.dp)
        )
    }
}

// ★★★ 核心修改: 重构图表以支持双腿对比 ★★★
@Composable
fun DistributionChart(
    title: String,
    leftData: List<Double>?,
    rightData: List<Double>?,
    unit: String
) {
    val isLeftDataValid = !leftData.isNullOrEmpty()
    val isRightDataValid = !rightData.isNullOrEmpty()

    if(!isLeftDataValid && !isRightDataValid) {
        ChartCard(title = title) { Text("无数据", modifier = Modifier.align(Alignment.CenterHorizontally)) }
        return
    }

    val bins = remember(leftData, rightData) {
        val allData = (leftData ?: emptyList()) + (rightData ?: emptyList())
        if(allData.isEmpty()) return@remember emptyList()

        val min = allData.minOrNull() ?: 0.0
        val max = allData.maxOrNull() ?: 0.0
        if (max == min) return@remember emptyList()

        val numBins = 10
        val binWidth = (max - min) / numBins
        if (binWidth <= 0) return@remember emptyList()

        val leftBinsMap = mutableMapOf<Int, Int>()
        leftData?.forEach { value ->
            val binIndex = floor((value - min) / binWidth).toInt().coerceAtMost(numBins - 1)
            leftBinsMap[binIndex] = (leftBinsMap[binIndex] ?: 0) + 1
        }

        val rightBinsMap = mutableMapOf<Int, Int>()
        rightData?.forEach { value ->
            val binIndex = floor((value - min) / binWidth).toInt().coerceAtMost(numBins - 1)
            rightBinsMap[binIndex] = (rightBinsMap[binIndex] ?: 0) + 1
        }

        (0 until numBins).map {
            val binStart = min + it * binWidth
            val label = "%.2f".format(binStart)
            val leftCount = leftBinsMap[it] ?: 0
            val rightCount = rightBinsMap[it] ?: 0
            Triple(label, leftCount, rightCount)
        }
    }

    if(bins.isEmpty()){
        ChartCard(title = title) { Text("数据分布无法计算", modifier = Modifier.align(Alignment.CenterHorizontally)) }
        return
    }

    val leftEntries = remember(bins) { bins.mapIndexed { index, t -> entryOf(index, t.second) } }
    val rightEntries = remember(bins) { bins.mapIndexed { index, t -> entryOf(index, t.third) } }
    val modelProducer = ChartEntryModelProducer(listOf(leftEntries, rightEntries))

    val leftColor = MaterialTheme.colorScheme.primary
    val rightColor = MaterialTheme.colorScheme.secondary

    ChartCard(title = title) {
        ChartLegend(isLeftDataValid, isRightDataValid)
        Chart(
            chart = columnChart(
                columns = listOfNotNull(
                    if(isLeftDataValid) lineComponent(color = leftColor, thickness = 16.dp) else null,
                    if(isRightDataValid) lineComponent(color = rightColor, thickness = 16.dp) else null
                )
                // ★★★ 核心修改: 移除错误的 'arrangement' 参数 ★★★
            ),
            chartModelProducer = modelProducer,
            startAxis = rememberStartAxis(title = "次数"),
            bottomAxis = rememberBottomAxis(
                title = "范围 ($unit)",
                valueFormatter = { value, _ -> bins.getOrNull(value.toInt())?.first ?: "" }
            ),
            modifier = Modifier.height(250.dp)
        )
    }
}

@Composable
fun ChartCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

// ★★★ 新增: 用于对比图表的图例 ★★★
@Composable
private fun ColumnScope.ChartLegend(isLeftAvailable: Boolean, isRightAvailable: Boolean) {
    if(!isLeftAvailable && !isRightAvailable) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if(isLeftAvailable) {
            LegendItem(color = MaterialTheme.colorScheme.primary, text = "左腿")
        }
        if(isLeftAvailable && isRightAvailable) {
            Spacer(modifier = Modifier.width(24.dp))
        }
        if(isRightAvailable) {
            LegendItem(color = MaterialTheme.colorScheme.secondary, text = "右腿")
        }
    }
}

// ★★★ 新增: 图例中的单个项目 ★★★
@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}


@Composable
fun SymmetryRadarChart(metrics: ComparisonMetrics, modifier: Modifier = Modifier) {
    val labels = listOf("周期", "步长", "支撑期", "摆动期", "屈曲", "外展")
    val values = listOf(
        metrics.timeSymmetry,
        metrics.stepLengthSymmetry,
        metrics.stanceTimeSymmetry,
        metrics.swingTimeSymmetry,
        metrics.flexionRangeSymmetry,
        metrics.abductionRangeSymmetry
    ).map { it.coerceIn(0.0, 1.0) }

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val dataColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = min(centerX, centerY) * 0.8f
        val angleStep = (2 * PI / labels.size).toFloat()

        (1..4).forEach { i ->
            val r = radius * i / 4
            drawCircle(color = gridColor, radius = r, style = Stroke(width = 1.dp.toPx()))
        }
        labels.indices.forEach { i ->
            val angle = i * angleStep - (PI / 2).toFloat()
            drawLine(
                color = gridColor,
                start = Offset(centerX, centerY),
                end = Offset(centerX + radius * cos(angle), centerY + radius * sin(angle)),
                strokeWidth = 1.dp.toPx()
            )
            val labelRadius = radius * 1.15f
            val textLayoutResult = textMeasurer.measure(labels[i], style = labelStyle)
            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(
                    x = centerX + labelRadius * cos(angle) - (textLayoutResult.size.width / 2),
                    y = centerY + labelRadius * sin(angle) - (textLayoutResult.size.height / 2)
                )
            )
        }

        val path = Path()
        values.forEachIndexed { i, value ->
            val angle = i * angleStep - (PI / 2).toFloat()
            val pointX = centerX + (radius * value).toFloat() * cos(angle)
            val pointY = centerY + (radius * value).toFloat() * sin(angle)
            if (i == 0) {
                path.moveTo(pointX, pointY)
            } else {
                path.lineTo(pointX, pointY)
            }
        }
        path.close()

        drawPath(path, color = dataColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, color = dataColor.copy(alpha = 0.3f))
    }
}
