// 文件: app/src/main/java/com/example/sensorwalk/data/GaitMetrics.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 新增(需求9)：在 LegMetrics 中添加 jerkAvg, totalTurns, totalAltitudeGain, totalAltitudeLoss 字段，以存储由分析引擎新增的计算指标。
 * 2025-07-30 - Gemini-AI - [V2] 扩展 LegMetrics，添加更多原始数据字段，用于绘图和调试。将瞬态数据用 `@Transient` 注解，防止被序列化，减小数据库和网络负载。
 * 2025-07-30 - Gemini-AI - 扩展数据模型以支持新指标和绘图。在 `LegMetrics` 中新增 `jerkAvg`, `totalTurns`, `totalAltitudeGain`, `totalAltitudeLoss` 字段，用于持久化存储由 `GaitAnalysisEngine` 新计算的指标（需求9）。同时，添加了多个 `raw...` 字段（如 `rawYawAngles`, `rawAltitude` 等），并使用 `@Transient` 注解，确保这些仅用于UI绘图的大量原始数据不会被序列化到数据库或通过网络传输，有效优化了性能和存储。
 * 2025-07-30 - Gemini-AI - [V4] 核心数据模型重构 (需求 5.3.2): 移除了平均化的`flexionRange`和`abductionRange`指标，新增了`stepAngleRanges`列表及`StepAngleRange`数据类，用于存储每个步态周期的精确角度活动范围，以支持新的条形图需求并修复角度为0的bug。
 */

package com.example.sensorwalk.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 传感器单次采样的数据点。
 */
@Serializable
data class SensorDataPoint(
    var timestamp: Long,
    var accX: Float = 0f, var accY: Float = 0f, var accZ: Float = 0f,
    var gyroX: Float = 0f, var gyroY: Float = 0f, var gyroZ: Float = 0f,
    var magX: Float = 0f, var magY: Float = 0f, var magZ: Float = 0f,
    var pressure: Float = 0f
)

/**
 * 单条腿的完整分析指标。
 */
@Serializable
data class LegMetrics(
    // 基础时空参数
    val totalSteps: Int = 0,
    val cadence: Double = 0.0,            // 步频 (步/分钟)
    val avgGaitCycle: Double = 0.0,       // 平均步态周期 (s)
    val stepAsymmetry: Double = 0.0,      // 步态不对称性 (周期时间的变异系数)
    val stanceTime: Double = 0.0,         // 支撑期 (s)
    val swingTime: Double = 0.0,          // 摆动期 (s)
    val stepLengthMean: Double = 0.0,     // 平均步长 (m)
    val stepLengthCv: Double = 0.0,       // 步长变异系数

    // 运动学与稳定性
    val gaitStability: Double = 0.0,      // 步态稳定性 (加速度方差)

    // ★★★ 核心修改: 使用新的数据结构存储每一步的角度范围 ★★★
    val stepAngleRanges: List<StepAngleRange> = emptyList(),

    // ★★★ 本次新增：动力学与平顺性 ★★★
    val jerkAvg: Double = 0.0,            // 平均冲击 (m/s³), 代表平顺性
    val dominantFrequency: Double = 0.0,  // 主频率 (Hz)

    // ★★★ 本次新增：环境与策略 ★★★
    val totalTurns: Int = 0,               // 总转向次数
    val totalAltitudeGain: Double = 0.0,   // 累计海拔升高 (m)
    val totalAltitudeLoss: Double = 0.0,   // 累计海拔降低 (m)

    // 单机模式下的估算对称性
    var estimatedSymmetryScore: Double = 0.0,

    // 瞬态原始数据，用于绘图和详细分析 (不会被序列化到网络或数据库)
    @Transient val rawGaitCycles: List<Double> = emptyList(),
    @Transient val rawStepLengths: List<Double> = emptyList(),
    @Transient val rawFlexionAngles: List<Double> = emptyList(),
    @Transient val rawAbductionAngles: List<Double> = emptyList(),
    @Transient val rawTimestamps: List<Double> = emptyList(),
    @Transient val rawYawAngles: List<Double> = emptyList(),
    @Transient val rawAltitude: List<Double> = emptyList(),
)

/**
 * ★★★ 新增数据类，用于存储单一步态周期的角度活动范围 ★★★
 */
@Serializable
data class StepAngleRange(
    val flexionRange: Double,
    val abductionRange: Double
)

/**
 * 双腿对比的分析指标。
 */
@Serializable
data class ComparisonMetrics(
    val timeSymmetry: Double = 0.0,
    val stepLengthSymmetry: Double = 0.0,
    val stanceTimeSymmetry: Double = 0.0,
    val swingTimeSymmetry: Double = 0.0,
    val flexionRangeSymmetry: Double = 0.0,
    val abductionRangeSymmetry: Double = 0.0,
    val timeSymmetryPValue: Double = 1.0,
    val stepLengthSymmetryPValue: Double = 1.0,
    val overallSymmetryScore: Double = 0.0
)
