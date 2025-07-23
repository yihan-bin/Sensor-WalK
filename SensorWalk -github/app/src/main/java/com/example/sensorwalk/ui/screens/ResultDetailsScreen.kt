// 文件: app/src/main/java/com/example/sensorwalk/ui/screens/ResultDetailsScreen.kt

package com.example.sensorwalk.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
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
import com.example.sensorwalk.viewmodel.MainViewModel
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class ProcessedResultData(
    val result: AnalysisResult,
    val leftMetrics: LegMetrics?,
    val rightMetrics: LegMetrics?,
    val comparison: ComparisonMetrics?,
    val leftRawData: RawDataBundle?,
    val rightRawData: RawDataBundle?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultDetailsScreen(
    resultId: Long,
    navController: NavController,
    viewModel: MainViewModel
) {
    var processedData by remember { mutableStateOf<ProcessedResultData?>(null) }
    // ★★★ [修复 #18] 屈曲角图表模型生产者 ★★★
    val flexionChartProducer = remember { ChartEntryModelProducer() }

    // ★★★ [新增 #20] 其他图表的模型生产者 ★★★
    val abductionChartProducer = remember { ChartEntryModelProducer() }
    val gaitCycleChartProducer = remember { ChartEntryModelProducer() }
    val stepLengthChartProducer = remember { ChartEntryModelProducer() }

    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(key1 = resultId) {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val dbResult = viewModel.getResultById(resultId)
            if (dbResult != null) {
                val json = Json { ignoreUnknownKeys = true }

                val localMetrics = json.decodeFromString<LegMetrics>(dbResult.localMetricsJson)
                val remoteMetrics = if (dbResult.remoteMetricsJson.isNotEmpty()) json.decodeFromString<LegMetrics>(dbResult.remoteMetricsJson) else null
                val comparison = if (dbResult.comparisonMetricsJson.isNotEmpty()) json.decodeFromString<ComparisonMetrics>(dbResult.comparisonMetricsJson) else null
                val localRaw = json.decodeFromString<RawDataBundle>(dbResult.localRawDataJson)
                val remoteRaw = if (dbResult.remoteRawDataJson.isNotEmpty()) json.decodeFromString<RawDataBundle>(dbResult.remoteRawDataJson) else null

                val finalLeftMetrics: LegMetrics?
                val finalRightMetrics: LegMetrics?
                val finalLeftRaw: RawDataBundle?
                val finalRightRaw: RawDataBundle?

                if (dbResult.mode == "Paired") {
                    if (dbResult.localLegSide == "LEFT") {
                        finalLeftMetrics = localMetrics; finalRightMetrics = remoteMetrics
                        finalLeftRaw = localRaw; finalRightRaw = remoteRaw
                    } else {
                        finalLeftMetrics = remoteMetrics; finalRightMetrics = localMetrics
                        finalLeftRaw = remoteRaw; finalRightRaw = localRaw
                    }
                } else {
                    if (dbResult.localLegSide == "LEFT") {
                        finalLeftMetrics = localMetrics; finalRightMetrics = null
                        finalLeftRaw = localRaw; finalRightRaw = null
                    } else {
                        finalLeftMetrics = null; finalRightMetrics = localMetrics
                        finalLeftRaw = null; finalRightRaw = localRaw
                    }
                }

                // 准备所有图表数据
                val leftFlexion = finalLeftRaw?.flexionAngles?.mapIndexed { i, v -> entryOf(i.toFloat(), v.toFloat()) }
                val rightFlexion = finalRightRaw?.flexionAngles?.mapIndexed { i, v -> entryOf(i.toFloat(), v.toFloat()) }

                val leftAbduction = finalLeftRaw?.abductionAngles?.mapIndexed { i, v -> entryOf(i.toFloat(), v.toFloat()) }
                val rightAbduction = finalRightRaw?.abductionAngles?.mapIndexed { i, v -> entryOf(i.toFloat(), v.toFloat()) }

                val gaitCycles = (finalLeftRaw?.gaitCycles ?: emptyList()) + (finalRightRaw?.gaitCycles ?: emptyList())
                val gaitCycleEntries = gaitCycles.mapIndexed { i, v -> entryOf(i.toFloat(), v.toFloat()) }

                val stepLengths = (finalLeftRaw?.stepLengths ?: emptyList()) + (finalRightRaw?.stepLengths ?: emptyList())
                val stepLengthEntries = stepLengths.mapIndexed { i, v -> entryOf(i.toFloat(), v.toFloat()) }

                withContext(Dispatchers.Main) {
                    processedData = ProcessedResultData(
                        result = dbResult,
                        leftMetrics = finalLeftMetrics, rightMetrics = finalRightMetrics,
                        comparison = comparison,
                        leftRawData = finalLeftRaw, rightRawData = finalRightRaw
                    )

                    // 更新所有图表
                    flexionChartProducer.setEntries(listOfNotNull(leftFlexion, rightFlexion))
                    abductionChartProducer.setEntries(listOfNotNull(leftAbduction, rightAbduction))
                    if(gaitCycleEntries.isNotEmpty()) gaitCycleChartProducer.setEntries(gaitCycleEntries)
                    if(stepLengthEntries.isNotEmpty()) stepLengthChartProducer.setEntries(stepLengthEntries)

                    isLoading = false
                }
            } else {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分析详情") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (processedData != null) {
            DetailsContent(
                data = processedData!!,
                flexionChartProducer = flexionChartProducer,
                abductionChartProducer = abductionChartProducer,
                gaitCycleChartProducer = gaitCycleChartProducer,
                stepLengthChartProducer = stepLengthChartProducer,
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("无法加载ID为 $resultId 的结果")
            }
        }
    }
}

@Composable
fun DetailsContent(
    data: ProcessedResultData,
    flexionChartProducer: ChartEntryModelProducer,
    abductionChartProducer: ChartEntryModelProducer,
    gaitCycleChartProducer: ChartEntryModelProducer,
    stepLengthChartProducer: ChartEntryModelProducer,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
        Text("分析于: ${sdf.format(Date(data.result.timestamp))}", style = MaterialTheme.typography.titleMedium)

        val totalSteps = (data.leftMetrics?.totalSteps ?: 0) + (data.rightMetrics?.totalSteps ?: 0)
        val avgCadence = listOfNotNull(data.leftMetrics?.cadence, data.rightMetrics?.cadence).takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val avgStepLength = listOfNotNull(data.leftMetrics?.stepLengthMean, data.rightMetrics?.stepLengthMean).takeIf { it.isNotEmpty() }?.average() ?: 0.0

        SummaryCard(data.result, totalSteps, avgCadence, avgStepLength)

        if (data.comparison != null && data.leftMetrics != null && data.rightMetrics != null) {
            RadarChartCard(data.leftMetrics, data.rightMetrics)
        }

        // ★★★ [修复 #18, #20] 渲染所有图表 ★★★
        SingleChartCard(
            title = "大腿屈曲角度",
            chartModelProducer = flexionChartProducer,
            yAxisTitle = "角度 (°)",
            xAxisTitle = "数据点",
            hasLeft = data.leftMetrics != null,
            hasRight = data.rightMetrics != null
        )

        SingleChartCard(
            title = "大腿外展角度",
            chartModelProducer = abductionChartProducer,
            yAxisTitle = "角度 (°)",
            xAxisTitle = "数据点",
            hasLeft = data.leftMetrics != null,
            hasRight = data.rightMetrics != null
        )

        SingleChartCard(
            title = "每步步态周期",
            chartModelProducer = gaitCycleChartProducer,
            yAxisTitle = "时间 (s)",
            xAxisTitle = "步数",
            lineColor = MaterialTheme.colorScheme.primary
        )

        SingleChartCard(
            title = "每步步长",
            chartModelProducer = stepLengthChartProducer,
            yAxisTitle = "距离 (m)",
            xAxisTitle = "步数",
            lineColor = MaterialTheme.colorScheme.secondary
        )

        DetailedMetricsCard(data)
    }
}

// ★★★ [重构 #20] 创建一个可复用的图表卡片组件 ★★★
@Composable
fun SingleChartCard(
    title: String,
    chartModelProducer: ChartEntryModelProducer,
    yAxisTitle: String,
    xAxisTitle: String,
    hasLeft: Boolean? = null,
    hasRight: Boolean? = null,
    lineColor: Color? = null
) {
    Card(modifier = Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))

            val lines = if (hasLeft != null && hasRight != null) {
                listOfNotNull(
                    if(hasLeft) lineSpec(lineColor = MaterialTheme.colorScheme.primary) else null,
                    if(hasRight) lineSpec(lineColor = MaterialTheme.colorScheme.tertiary) else null
                )
            } else {
                listOf(lineSpec(lineColor = lineColor ?: MaterialTheme.colorScheme.primary))
            }

            if (chartModelProducer.getModel()?.entries?.firstOrNull()?.isNotEmpty() == true) {
                Chart(
                    chart = lineChart(lines = lines),
                    chartModelProducer = chartModelProducer,
                    startAxis = rememberStartAxis(title = yAxisTitle),
                    bottomAxis = rememberBottomAxis(title = xAxisTitle),
                    modifier = Modifier.height(250.dp)
                )
            } else {
                Box(modifier = Modifier.height(250.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("无可用数据")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (hasLeft != null && hasRight != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (hasLeft) LegendItem(color = MaterialTheme.colorScheme.primary, "左腿")
                    if (hasLeft && hasRight) Spacer(modifier = Modifier.width(16.dp))
                    if (hasRight) LegendItem(color = MaterialTheme.colorScheme.tertiary, "右腿")
                }
            }
        }
    }
}

// ... 以下为其他UI组件，与您提供的代码基本一致，无需修改 ...
// ... (SummaryCard, DetailedMetricsCard, RadarChartCard, etc.) ...
private fun normalizeRadarData(left: DoubleArray, right: DoubleArray): List<List<Float>> {
    val normalizedLeft = FloatArray(left.size)
    val normalizedRight = FloatArray(right.size)

    for (i in left.indices) {
        val maxVal = max(left[i], right[i])
        if (maxVal > 0) {
            normalizedLeft[i] = (left[i] / maxVal).toFloat()
            normalizedRight[i] = (right[i] / maxVal).toFloat()
        } else {
            normalizedLeft[i] = 0f
            normalizedRight[i] = 0f
        }
    }
    return listOf(normalizedLeft.toList(), normalizedRight.toList())
}

@Composable
fun SummaryCard(result: AnalysisResult, totalSteps: Int, avgCadence: Double, avgStepLength: Double) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("核心摘要", style = MaterialTheme.typography.titleLarge)
            HorizontalDivider()
            SummaryRow("模式", if (result.mode == "Paired") "双腿对比" else "单腿估算")
            SummaryRow("对称性总分", "%.1f / 100".format(result.overallScore), isHighlight = true)
            SummaryRow("总步数", "$totalSteps 步")
            SummaryRow("平均步频", "%.1f 步/分钟".format(avgCadence))
            SummaryRow("平均步长", "%.2f 米".format(avgStepLength))
        }
    }
}

@Composable
fun DetailedMetricsCard(data: ProcessedResultData) {
    var showAbnormalSwingInfo by remember { mutableStateOf(false) }

    if (showAbnormalSwingInfo) {
        AlertDialog(
            onDismissRequest = { showAbnormalSwingInfo = false },
            title = { Text("关于异常摆动次数") },
            text = {
                Text("该指标通过统计学方法（Z-score > 2）计算得出。\n\n它衡量的是在行走过程中，大腿向侧方摆动（外展/内收）的幅度超出其正常变化范围的次数。\n\n次数越多，可能意味着行走时侧向控制不稳定或存在代偿动作。\n\n正常情况下，异常摆动次数应该较少（通常小于总数据点的5%）。")
            },
            confirmButton = {
                Button(onClick = { showAbnormalSwingInfo = false }) {
                    Text("我明白了")
                }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("详细参数对比", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("参数", modifier = Modifier.weight(0.4f), fontWeight = FontWeight.Bold)
                if (data.leftMetrics != null) {
                    Text("左腿", modifier = Modifier.weight(0.3f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                if (data.rightMetrics != null) {
                    Text("右腿", modifier = Modifier.weight(0.3f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            MetricRow("步态周期 (s)", data.leftMetrics?.avgGaitCycle, data.rightMetrics?.avgGaitCycle)
            MetricRow("支撑期 (s)", data.leftMetrics?.stanceTime, data.rightMetrics?.stanceTime)
            MetricRow("摆动期 (s)", data.leftMetrics?.swingTime, data.rightMetrics?.swingTime)
            MetricRow("步长 (m)", data.leftMetrics?.stepLengthMean, data.rightMetrics?.stepLengthMean)
            MetricRow("屈曲范围 (°)", data.leftMetrics?.flexionRange, data.rightMetrics?.flexionRange)
            MetricRow("外展范围 (°)", data.leftMetrics?.abductionRange, data.rightMetrics?.abductionRange)
            MetricRow("最大冲击力 (g)", data.leftMetrics?.grfMax, data.rightMetrics?.grfMax)
            MetricRow("平均冲击 (m/s³)", data.leftMetrics?.jerkAvg, data.rightMetrics?.jerkAvg)

            MetricRowWithInfo(
                label = "异常摆动次数",
                leftValue = data.leftMetrics?.abnormalSwingCount?.toDouble(),
                rightValue = data.rightMetrics?.abnormalSwingCount?.toDouble(),
                isInt = true,
                onInfoClick = { showAbnormalSwingInfo = true },
                data = data
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            MetricRow("累计上升 (m)", data.leftMetrics?.totalAltitudeGain, data.rightMetrics?.totalAltitudeGain)
            MetricRow("累计下降 (m)", data.leftMetrics?.totalAltitudeLoss, data.rightMetrics?.totalAltitudeLoss)
            MetricRow("转向次数", data.leftMetrics?.totalTurns?.toDouble(), data.rightMetrics?.totalTurns?.toDouble(), isInt = true)
        }
    }
}

@Composable
fun MetricRowWithInfo(
    label: String,
    leftValue: Double?,
    rightValue: Double?,
    isInt: Boolean = false,
    onInfoClick: () -> Unit,
    data: ProcessedResultData
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(0.4f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onInfoClick, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Info, contentDescription = "详细信息", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val leftText = if (leftValue == null) "N/A" else if(isInt) "%.0f".format(leftValue) else "%.2f".format(leftValue)
        val rightText = if (rightValue == null) "N/A" else if(isInt) "%.0f".format(rightValue) else "%.2f".format(rightValue)

        if (data.leftMetrics != null) {
            Text(leftText, modifier = Modifier.weight(0.3f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
        if (data.rightMetrics != null) {
            Text(rightText, modifier = Modifier.weight(0.3f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun RadarChartCard(left: LegMetrics, right: LegMetrics) {
    val labels = listOf("周期", "步长", "支撑期", "摆动期", "屈曲", "外展")
    val leftValues = with(left) { doubleArrayOf(avgGaitCycle, stepLengthMean, stanceTime, swingTime, flexionRange, abductionRange) }
    val rightValues = with(right) { doubleArrayOf(avgGaitCycle, stepLengthMean, stanceTime, swingTime, flexionRange, abductionRange) }
    val normalizedData = normalizeRadarData(leftValues, rightValues)
    val leftColor = MaterialTheme.colorScheme.primary
    val rightColor = MaterialTheme.colorScheme.tertiary

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("参数对称性雷达图", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            CustomRadarChart(modifier = Modifier.fillMaxWidth().height(300.dp), data = normalizedData, labels = labels, colors = listOf(leftColor, rightColor))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                LegendItem(color = leftColor.copy(alpha = 0.4f), "左腿")
                Spacer(modifier = Modifier.width(16.dp))
                LegendItem(color = rightColor.copy(alpha = 0.4f), "右腿")
            }
        }
    }
}

@Composable
fun CustomRadarChart(modifier: Modifier = Modifier, data: List<List<Float>>, labels: List<String>, colors: List<Color>) {
    val textMeasurer = rememberTextMeasurer()
    val textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)

    Canvas(modifier = modifier) {
        val numAxes = labels.size
        if (numAxes == 0) return@Canvas
        val angleStep = 2 * Math.PI.toFloat() / numAxes
        val radius = size.minDimension / 2.2f
        val center = Offset(size.width / 2, size.height / 2)

        drawRadarWeb(numAxes, angleStep, radius, center)
        drawRadarLabels(labels, angleStep, radius, center, textMeasurer, textStyle)

        data.forEachIndexed { index, values ->
            if (values.size != numAxes) return@forEachIndexed
            val color = colors.getOrElse(index) { Color.Gray }
            val path = Path()
            values.forEachIndexed { i, value ->
                val angle = i * angleStep
                val x = center.x + radius * value * cos(angle - Math.PI.toFloat() / 2)
                val y = center.y + radius * value * sin(angle - Math.PI.toFloat() / 2)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color = color, style = Stroke(width = 4f))
            drawPath(path, color = color.copy(alpha = 0.4f))
        }
    }
}

private fun DrawScope.drawRadarWeb(numAxes: Int, angleStep: Float, radius: Float, center: Offset) {
    val webColor = Color.Gray.copy(alpha = 0.6f)
    val circleCount = 4
    for(i in 1..circleCount) {
        val r = radius * i / circleCount
        drawCircle(webColor, radius = r, center = center, style = Stroke(width=1.dp.toPx()))
    }
    for (i in 0 until numAxes) {
        val angle = i * angleStep
        drawLine(color = webColor, start = center,
            end = center + Offset(radius * cos(angle - Math.PI.toFloat()/2), radius * sin(angle - Math.PI.toFloat()/2)),
            strokeWidth = 1.dp.toPx())
    }
}

private fun DrawScope.drawRadarLabels(labels: List<String>, angleStep: Float, radius: Float, center: Offset, textMeasurer: TextMeasurer, style: TextStyle) {
    for (i in labels.indices) {
        val angle = i * angleStep
        val labelRadius = radius * 1.1f
        val x = center.x + labelRadius * cos(angle - Math.PI.toFloat() / 2)
        val y = center.y + labelRadius * sin(angle - Math.PI.toFloat() / 2)
        val textLayoutResult = textMeasurer.measure(labels[i], style)
        drawText(textLayoutResult = textLayoutResult, topLeft = Offset(x - textLayoutResult.size.width/2, y - textLayoutResult.size.height/2))
    }
}

@Composable
fun SummaryRow(label: String, value: String, isHighlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = if(isHighlight) FontWeight.Bold else FontWeight.Normal)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = if(isHighlight) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

@Composable
fun MetricRow(label: String, leftValue: Double?, rightValue: Double?, isInt: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium)

        val leftText = if (leftValue == null) "N/A" else if(isInt) "%.0f".format(leftValue) else "%.2f".format(leftValue)
        val rightText = if (rightValue == null) "N/A" else if(isInt) "%.0f".format(rightValue) else "%.2f".format(rightValue)

        if (leftValue != null || rightValue != null) {
            Text(leftText, modifier = Modifier.weight(0.3f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Text(rightText, modifier = Modifier.weight(0.3f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontSize = 14.sp)
    }
}
