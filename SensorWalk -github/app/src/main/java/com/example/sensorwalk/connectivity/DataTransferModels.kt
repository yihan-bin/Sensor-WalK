package com.example.sensorwalk.connectivity

import com.example.sensorwalk.data.SensorDataPoint
import com.example.sensorwalk.viewmodel.LegSide
import kotlinx.serialization.Serializable

@Serializable
sealed class DataPacket {
    /** 包含传感器数据片段的数据包。 */
    @Serializable
    data class SensorData(val segments: List<List<SensorDataPoint>>) : DataPacket()

    /** 主机发送给从机的控制指令。 */
    @Serializable
    data class ControlCommand(val command: String, val legSide: LegSide? = null) : DataPacket() // command: "START" or "STOP"

    /** 分析失败的通知。 */
    @Serializable
    data class AnalysisFailure(val reason: String) : DataPacket()

    /** 心跳包，用于维持连接和检测活性。 */
    @Serializable
    object Heartbeat : DataPacket()
}
