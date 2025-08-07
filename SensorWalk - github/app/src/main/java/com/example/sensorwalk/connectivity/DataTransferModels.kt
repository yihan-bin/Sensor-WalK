// 文件: app/src/main/java/com/example/sensorwalk/connectivity/DataTransferModels.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 对网络交互协议进行了全面重构。引入了 `ClientStatus` 和 `StatusReport` 来实现带状态的心跳；新增了 `QueryDataStatus` 指令用于超时重试；并规范化所有数据包，强制包含 `collectionId`。
 * 2025-07-30 - Gemini-AI - [V2] 协议模型保持稳定。V1定义的协议已能满足本次重构的全部交互需求，无需更改。
 * 2025-07-30 - Gemini-AI - 全面重构网络协议以支持鲁棒的主从交互。引入`DataPacket`密封类，强制所有数据包包含`collectionId`（需求4.4）。新增`ClientStatus`枚举和`StatusReport`数据包以实现带状态的心跳（需求4.2）。新增`CommandStart/Stop`, `AcknowledgeStart`, `QueryDataStatus`, `Error`等多种消息类型，构建了完整的指令-响应-状态同步闭环（需求4.1, 4.3, 4.6, 4.10）。
 */

package com.example.sensorwalk.connectivity

import com.example.sensorwalk.data.SensorDataPoint
import com.example.sensorwalk.viewmodel.LegSide
import kotlinx.serialization.Serializable

/**
 * 从机的详细状态，由从机通过 StatusReport 定期发送给主机。
 */
@Serializable
enum class ClientStatus {
    IDLE,        // 空闲，等待指令
    READY,       // 服务已就绪，等待指令
    COLLECTING,  // 正在采集中
    UPLOADING,   // 正在上传数据
    UPLOAD_DONE, // 数据上传完成
    ANALYZING,   // 正在进行本地分析
    ERROR        // 发生错误
}

/**
 * 所有网络数据包的基类，强制要求包含 collectionId。
 */
@Serializable
sealed class DataPacket {
    abstract val collectionId: Long

    // --- 指令 (Host -> Client) ---
    /** 主机命令从机开始采集 */
    @Serializable
    data class CommandStart(override val collectionId: Long, val clientLegSide: LegSide) : DataPacket()

    /** 主机命令从机停止采集并上传数据 */
    @Serializable
    data class CommandStop(override val collectionId: Long) : DataPacket()

    /** ★★★ 新增：主机用于查询从机数据状态的指令 ★★★ */
    @Serializable
    data class QueryDataStatus(override val collectionId: Long) : DataPacket()

    // --- 确认 (Client -> Host) ---
    /** 从机确认收到 CommandStart */
    @Serializable
    data class AcknowledgeStart(override val collectionId: Long) : DataPacket()

    // --- 状态与数据 (Client -> Host) ---
    /** ★★★ 新增：从机定期向主机报告状态和电量（取代了简单心跳）★★★ */
    @Serializable
    data class StatusReport(override val collectionId: Long, val status: ClientStatus, val batteryLevel: Int) : DataPacket()

    /** 从机发送的数据块 */
    @Serializable
    data class DataChunk(override val collectionId: Long, val chunkData: List<SensorDataPoint>, val chunkIndex: Int, val totalChunks: Int) : DataPacket()

    // --- 通知 (双向) ---
    /** 用于流程关键节点的通知 */
    @Serializable
    data class Notification(override val collectionId: Long, val message: String) : DataPacket() {
        companion object {
            const val MSG_UPLOAD_COMPLETE = "UPLOAD_COMPLETE"
            const val MSG_ANALYSIS_COMPLETE = "ANALYSIS_COMPLETE"
        }
    }

    // --- 错误 (双向) ---
    /** 用于报告错误 */
    @Serializable
    data class Error(override val collectionId: Long, val reason: String) : DataPacket() {
        companion object {
            const val REASON_ID_MISMATCH = "ID_MISMATCH"
            const val REASON_DATA_NOT_FOUND = "DATA_NOT_FOUND"
            const val REASON_ANALYSIS_FAILED = "ANALYSIS_FAILED"
            const val REASON_TIMEOUT = "TIMEOUT"
            const val REASON_UNKNOWN = "UNKNOWN"
        }
    }

    // --- 心跳 (Client -> Host，用于保持连接活性) ---
    @Serializable
    data class Heartbeat(override val collectionId: Long = 0) : DataPacket()
}
