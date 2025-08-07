package com.example.sensorwalk.data

import kotlinx.serialization.Serializable

@Serializable
data class RawDataBundle(
    val flexionAngles: List<Double>,
    val abductionAngles: List<Double>,
    // ★★★ [新增 #20]: 为新图表添加原始数据字段 ★★★
    val gaitCycles: List<Double>,
    val stepLengths: List<Double>,
    val timestamps: List<Double>
)
