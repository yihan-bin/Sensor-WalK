// 文件: app/src/main/java/com/example/sensorwalk/data/GaitMetrics.kt

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
    // 新增: 压力传感器数据 (单位: hPa 或 mbar)
    var pressure: Float = 0f
)

/**
 * 单条腿的完整分析指标，对标Python脚本输出。
 */
@Serializable
data class LegMetrics(
    // Base Spatiotemporal
    val totalSteps: Int = 0,
    val cadence: Double = 0.0,            // 步频 (步/分钟)
    val avgGaitCycle: Double = 0.0,       // 平均步态周期 (s)
    val stepAsymmetry: Double = 0.0,      // 步态不对称性 (周期时间的变异系数)
    val stanceTime: Double = 0.0,         // 支撑期 (s)
    val swingTime: Double = 0.0,          // 摆动期 (s)
    val stepLengthMean: Double = 0.0,     // 平均步长 (m)
    val stepLengthCv: Double = 0.0,       // 步长变异系数

    // Kinematics & Stability
    val gaitStability: Double = 0.0,      // 步态稳定性 (加速度方差)
    val flexionRange: Double = 0.0,       // 屈曲角度范围 (度)
    val abductionRange: Double = 0.0,     // 外展角度范围 (度)
    val abnormalSwingCount: Int = 0,      // 异常摆动次数
    val footClearance: Double = 0.0,      // 足底离地高度 (m)
    val circumduction: Double = 0.0,      // 绕行 (m)

    // Dynamics
    val grfMax: Double = 0.0,             // 最大垂直地面反作用力 (g)
    val jerkAvg: Double = 0.0,            // 平均冲击 (m/s³)
    val dominantFrequency: Double = 0.0,  // 主频率 (Hz) - 占位符

    // --- 新增指标 ---
    val totalTurns: Int = 0,               // 总转向次数
    val totalAltitudeGain: Double = 0.0,   // 累计海拔升高 (m)
    val totalAltitudeLoss: Double = 0.0,   // 累计海拔降低 (m)

    // For single mode analysis
    var estimatedSymmetryScore: Double = 0.0,

    // Raw data for plotting and detailed analysis
    // @Transient prevents serialization into DB/network JSON
    @Transient val rawGaitCycles: List<Double> = emptyList(),
    @Transient val rawStepLengths: List<Double> = emptyList(),
    @Transient val rawFlexionAngles: List<Double> = emptyList(),
    @Transient val rawAbductionAngles: List<Double> = emptyList(),
    @Transient val rawTimestamps: List<Double> = emptyList(),
    @Transient val rawYawAngles: List<Double> = emptyList(), // 新增: 原始偏航角数据
    @Transient val rawAltitude: List<Double> = emptyList(),  // 新增: 原始海拔数据
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
