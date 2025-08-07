package com.example.sensorwalk.viewmodel

/**
 * 定义整个应用中使用的腿部。
 * 从 ViewModel 中移出，以供其他层（如 AnalysisEngine）轻松访问。
 */
enum class LegSide { LEFT, RIGHT }

/**
 * 定义分析模式。
 */
enum class AnalysisMode { SINGLE, PAIRED_HOST, PAIRED_CLIENT }
