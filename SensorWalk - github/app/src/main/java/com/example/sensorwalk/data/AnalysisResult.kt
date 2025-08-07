// 文件: app/src/main/java/com/example/sensorwalk/data/AnalysisResult.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 新增(需求4.8): 在 AnalysisResult 实体中新增 localDataFilePath 和 remoteDataFilePath 字段，将报告与原始数据文件关联，以实现同步删除功能。
 * 2025-07-30 - Gemini-AI - 新增文件路径字段。为实现报告与原始数据文件的关联（需求2, 4.8, 4.14），在`AnalysisResult`实体中添加`localDataFilePath`和`remoteDataFilePath`两个`String`类型字段，用于持久化存储数据源文件的绝对路径。
 * 2025-07-30 - Gemini-AI - [V3] 致命错误修复 (SQLiteBlobTooBigException): 移除了 `localRawDataJson` 和 `remoteRawDataJson` 字段，这是导致数据库因数据过大而崩溃的根源。新增 `localChartDataPath` 和 `remoteChartDataPath` 字段，将用于图表的大体积原始数据外置到独立文件中存储，仅在数据库中保留其路径引用。
 */

package com.example.sensorwalk.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "analysis_results")
data class AnalysisResult(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val durationSeconds: Int,
    val mode: String, // "Single" or "Paired"
    val totalSteps: Int,
    val overallScore: Double,

    // 存储核心指标的JSON
    val localMetricsJson: String,
    val remoteMetricsJson: String,
    val comparisonMetricsJson: String,

    // ★★★ 核心修改：移除导致崩溃的大数据字段 ★★★
    // val localRawDataJson: String,  // <-- REMOVED
    // val remoteRawDataJson: String, // <-- REMOVED

    // ★★★ 核心修改：用文件路径引用取代直接存储 ★★★
    val localDataFilePath: String,      // 完整原始数据文件
    val remoteDataFilePath: String,     // 完整原始数据文件
    val localChartDataPath: String,     // 图表专用数据文件
    val remoteChartDataPath: String,    // 图表专用数据文件

    // 旧的摘要字段，保留以兼容旧迁移逻辑，但不再写入新数据
    val dataSummaryJson: String = "{}",

    val localLegSide: String, // "LEFT" or "RIGHT"
    val remoteLegSide: String, // "LEFT" or "RIGHT" or ""
)

// 这个数据摘要模型现在主要用于轻量级概览，不再是图表数据的来源
@Serializable
data class DataSummary(
    val sampleCount: Int = 0,
    val duration: Double = 0.0,
    val avgFlexion: Double = 0.0,
    val avgAbduction: Double = 0.0,
    val keyTimestamps: List<Double> = emptyList()
)
