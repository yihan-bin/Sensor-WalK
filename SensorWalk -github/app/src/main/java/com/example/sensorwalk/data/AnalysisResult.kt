package com.example.sensorwalk.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "analysis_results")
data class AnalysisResult(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val durationSeconds: Int,
    val mode: String, // "Single" or "Paired"
    val totalSteps: Int,
    val overallScore: Double,

    // 将复杂的指标对象序列化为JSON字符串进行存储
    val localMetricsJson: String,
    val remoteMetricsJson: String,
    val comparisonMetricsJson: String,

    // 同样存储用于绘图的原始数据
    val localRawDataJson: String,
    val remoteRawDataJson: String,

    // ★★★ 新增 [需求 5, 14]: 记录腿部信息 ★★★
    val localLegSide: String, // "LEFT" or "RIGHT"
    val remoteLegSide: String, // "LEFT" or "RIGHT" or ""
)
