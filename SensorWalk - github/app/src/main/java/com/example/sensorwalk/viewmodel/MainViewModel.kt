// 文件: app/src/main/java/com/example/sensorwalk/viewmodel/MainViewModel.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 全面实现了高级交互协议、文件化数据流和用户体验优化。新增“僵尸文件”清理机制；实现了首次启动弹窗；完善了删除和导出功能；实现了连接中断后降级分析等高级容错逻辑。
 * 2025-07-30 - Gemini-AI - [V2] 全面重构为应用核心控制器。实现了管理单机/主机/从机模式的健壮状态机。整合了新的网络协议，包含指令发送、ACK等待、超时重试（需求4.1, 4.10）、状态报告（需求4.2）和数据分块上传（需求3）等逻辑。实现了基于文件的分析流程（需求2），并添加了数据与报告的级联删除（需求4.8）、历史记录导出（需求 11.2 隐含）、“僵尸文件”清理（需求11）、首次启动弹窗（需求8）以及连接中断后降级为单机分析（需求4.12）等高级容错和UX优化。
 * 2025-07-30 - Gemini-AI - [V3] 修复SQLite崩溃：修改`saveResultAndNavigate`方法，不再将大的原始数据JSON存入数据库实体。新增了`getChartDataFilePath`方法和逻辑，将用于图表的数据（RawDataBundle）保存到独立的JSON文件中，并在数据库中仅存储其路径。
 * 2025-07-30 - Gemini-AI - [V4] 最终审查与确认。确认所有交互逻辑、状态管理、文件持久化和错误处理均符合最终要求。扩展了“僵尸文件”清理机制，使其能够同时清理图表数据文件。
 * 2025-07-30 - Gemini-AI - [V5] 致命错误修复 (联机模式无法启动): 修复了从机在收到主机开始指令后，因不当的状态重置导致其不发送确认(ACK)的BUG。重构了 `handleCommandStart` 方法，确保服务在响应指令前已正确绑定并就绪，从而保证了 AcknowledgeStart 数据包能够被成功发送，解决了主机等待超时的核心问题。
 * 2025-07-30 - Gemini-AI - [V6] 致命逻辑修复 (从机无法响应开始指令): 修复了 `handleDataPacket` 方法中错误的ID校验逻辑。该逻辑错误地拦截了用于开启新会话的 `CommandStart` 数据包，导致从机无法处理开始指令，联机流程中断。
 * 2025-07-30 - Gemini-AI - [V7] 致命UI状态修复 (从机断连后无法切换模式): 修复了从机断开连接后，切换回单机模式时“开始分析”按钮被永久禁用的BUG。修改了 `setAnalysisMode` 方法，确保在切换模式时，会强制重新计算并更新UI状态，保证了界面的正确响应。
 * 2025-07-30 - Gemini-AI - [V8] 致命错误修复 (联机报告空白) & 新增功能 (僵尸文件清理): 重构`stopCollection`流程，确保即使从机数据获取失败，也能对本机数据进行降级分析并生成报告，杜绝了白屏问题【需求 5.3】。在ViewModel初始化时，增加存储健康检查(`cleanupOrphanedDataFiles`)，自动删除未被任何报告引用的孤立数据文件，保证应用存储的长期健康【需求 5.1.11】。
 */
package com.example.sensorwalk.viewmodel

import android.content.*
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sensorwalk.analysis.GaitAnalysisEngine
import com.example.sensorwalk.connectivity.*
import com.example.sensorwalk.data.*
import com.example.sensorwalk.service.GaitService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import android.content.Intent
import kotlinx.serialization.decodeFromString

data class UiState(
    val isOperating: Boolean = false, // 总开关：是否正在采集或分析
    val isRecording: Boolean = false,
    val isAnalyzing: Boolean = false,
    val isWaitingForPeer: Boolean = false, // 是否在等待对方（ACK或数据）
    val isWalking: Boolean = false,
    val statusText: String = "将手机固定于大腿\n点击开始分析",
    val analysisMode: AnalysisMode = AnalysisMode.SINGLE,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val showDisclaimer: Boolean = false,
    val legSelection: LegSide = LegSide.LEFT,
    val sensorInfoText: String = "正在获取传感器信息...",
    val isStartButtonDisabledForClient: Boolean = false
)

private data class ChunkCollector(
    val totalChunks: Int,
    val receivedChunks: MutableList<DataPacket.DataChunk> = mutableListOf(),
)

private data class DataFetchState(
    var retryCount: Int = 0,
    var timeoutJob: Job? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: DataRepository,
    val connectionManager: ConnectionManager,
    @ApplicationContext private val application: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _navigateToResultEvent = MutableSharedFlow<Long>()
    val navigateToResultEvent = _navigateToResultEvent.asSharedFlow()

    private val _requestCreateFile = MutableSharedFlow<Intent>()
    val requestCreateFile = _requestCreateFile.asSharedFlow()

    val allResults: Flow<List<AnalysisResult>> = repository.getAllAnalysisResults()
    suspend fun getResultById(id: Long): AnalysisResult? = repository.getAnalysisResult(id)

    private var gaitService: GaitService? = null
    private var isServiceBound = false

    private var currentCollectionId: Long? = null
    private var hostState: HostState = HostState.IDLE
    @Volatile private var clientStatus: ClientStatus = ClientStatus.IDLE

    private var serviceMonitoringJob: Job? = null
    private var connectionListenerJob: Job? = null
    private var statusReporterJob: Job? = null
    private var peerAckTimeoutJob: Job? = null
    private val dataFetchStates = ConcurrentHashMap<Long, DataFetchState>()

    private val remoteDataChunkCollector = ConcurrentHashMap<Long, ChunkCollector>()
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private enum class HostState { IDLE, WAITING_FOR_ACK, COLLECTING, WAITING_FOR_DATA, ANALYZING }

    init {
        checkIfFirstLaunch()
        startListeners()
        getSensorInfo()
        cleanupOrphanedDataFiles()
    }

    private fun checkIfFirstLaunch() {
        val isFirst = prefs.getBoolean("is_first_launch", true)
        if (isFirst) {
            _uiState.update { it.copy(showDisclaimer = true) }
        }
    }

    fun onDisclaimerConfirmed() {
        prefs.edit().putBoolean("is_first_launch", false).apply()
        updateUi { it.copy(showDisclaimer = false) }
    }

    fun toggleAnalysis() {
        if (_uiState.value.isOperating) {
            stopCollection()
        } else {
            startCollection()
        }
    }

    fun setLegSelection(legSide: LegSide) {
        _uiState.update { it.copy(legSelection = legSide) }
    }

    fun setAnalysisMode(mode: AnalysisMode) {
        if (_uiState.value.analysisMode == mode) return

        if (connectionManager.connectionState.value !is ConnectionState.Idle) {
            connectionManager.disconnect()
        }

        _uiState.update { currentState ->
            currentState.copy(
                analysisMode = mode,
                statusText = getIdleTextForMode(mode),
                isStartButtonDisabledForClient = (mode == AnalysisMode.PAIRED_CLIENT && currentState.connectionState !is ConnectionState.Connected)
            )
        }
    }


    fun startHosting() {
        setAnalysisMode(AnalysisMode.PAIRED_HOST)
        connectionManager.startServer()
    }

    fun startJoining() {
        setAnalysisMode(AnalysisMode.PAIRED_CLIENT)
        ensureServiceIsRunningAndBound()
        connectionManager.startDiscovery()
        updateUi { it.copy(statusText = "正在搜索主机...") }
    }

    fun disconnect() {
        connectionManager.disconnect()
        resetAllStatesAndService()
    }

    fun deleteHistoryItem(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val result = repository.getAnalysisResult(id)
        if (result != null) {
            repository.deleteResult(id)
            try {
                if (result.localDataFilePath.isNotEmpty()) File(result.localDataFilePath).delete()
                if (result.remoteDataFilePath.isNotEmpty()) File(result.remoteDataFilePath).delete()
                if (result.localChartDataPath.isNotEmpty()) File(result.localChartDataPath).delete()
                if (result.remoteChartDataPath.isNotEmpty()) File(result.remoteChartDataPath).delete()
                Log.i(TAG, "成功删除报告ID $id 及其关联的所有数据文件。")
            } catch (e: Exception) {
                Log.e(TAG, "删除关联数据文件失败", e)
            }
        }
    }

    fun clearAllHistory() = viewModelScope.launch(Dispatchers.IO) {
        val all = repository.getAllAnalysisResults().first()
        all.forEach { result ->
            try {
                if (result.localDataFilePath.isNotEmpty()) File(result.localDataFilePath).delete()
                if (result.remoteDataFilePath.isNotEmpty()) File(result.remoteDataFilePath).delete()
                if (result.localChartDataPath.isNotEmpty()) File(result.localChartDataPath).delete()
                if (result.remoteChartDataPath.isNotEmpty()) File(result.remoteChartDataPath).delete()
            } catch (e: Exception) {
                Log.e(TAG, "清空历史时，删除文件失败: ${result.id}", e)
            }
        }
        repository.clearAllResults()
    }

    fun exportAllHistory(context: Context) = viewModelScope.launch(Dispatchers.IO) {
        val history = repository.getAllAnalysisResults().first()
        if (history.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "没有可导出的记录。", Toast.LENGTH_SHORT).show()
            }
            return@launch
        }

        val historyJson = json.encodeToString(history)
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = "SensorWalk_History_${sdf.format(Date())}.json"

        ExportManager.setContentToExport(historyJson)

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        _requestCreateFile.emit(intent)
    }

    private fun startCollection() {
        ensureServiceIsRunningAndBound()
        viewModelScope.launch {
            if (!waitForServiceBound()) return@launch

            val newCollectionId = System.currentTimeMillis()
            currentCollectionId = newCollectionId

            when (_uiState.value.analysisMode) {
                AnalysisMode.SINGLE -> {
                    hostState = HostState.COLLECTING
                    updateUiForRecording(true)
                    gaitService?.startCollection(getDataFilePath(newCollectionId, isLocal = true, isHost = true))
                }
                AnalysisMode.PAIRED_HOST -> {
                    hostState = HostState.WAITING_FOR_ACK
                    updateUi { it.copy(isOperating = true, isWaitingForPeer = true, statusText = "等待从机确认...") }
                    startAckTimeout(newCollectionId)
                    val remoteLegSide = if (_uiState.value.legSelection == LegSide.LEFT) LegSide.RIGHT else LegSide.LEFT
                    viewModelScope.launch {
                        connectionManager.sendData(DataPacket.CommandStart(newCollectionId, remoteLegSide))
                    }
                }
                AnalysisMode.PAIRED_CLIENT -> Log.w(TAG, "从机模式下不应触发主动开始采集")
            }
        }
    }

    // ★★★ 核心修改: 重构停止和分析逻辑 ★★★
    private fun stopCollection() {
        val collectionId = currentCollectionId ?: return
        if (gaitService == null) {
            resetAllStatesAndService("服务异常，已重置")
            return
        }

        gaitService?.stopAndRetrieveData()
        updateUiForRecording(false)

        viewModelScope.launch(Dispatchers.IO) {
            when (_uiState.value.analysisMode) {
                AnalysisMode.SINGLE -> {
                    hostState = HostState.ANALYZING
                    withContext(Dispatchers.Main) {
                        updateUi { it.copy(isAnalyzing = true, statusText = "正在分析数据...") }
                    }
                    analyzeData(collectionId, null, isHost = true)
                }
                AnalysisMode.PAIRED_HOST -> {
                    hostState = HostState.WAITING_FOR_DATA
                    withContext(Dispatchers.Main) {
                        updateUi { it.copy(isWaitingForPeer = true, statusText = "等待从机上传数据...") }
                    }
                    startDataFetchTimeout(collectionId)
                    connectionManager.sendData(DataPacket.CommandStop(collectionId))
                }
                AnalysisMode.PAIRED_CLIENT -> Log.w(TAG, "从机模式下不应触发主动停止采集")
            }
        }
    }

    private fun handleDataPacket(packet: DataPacket) {
        if (packet !is DataPacket.CommandStart && packet !is DataPacket.StatusReport &&
            packet.collectionId != currentCollectionId && packet.collectionId != 0L) {
            if (packet is DataPacket.DataChunk && hostState == HostState.WAITING_FOR_DATA) {
                Log.w(TAG, "收到旧会话的数据分片, 但当前正在等待数据，允许处理。Expected: $currentCollectionId, Got: ${packet.collectionId}")
            } else {
                Log.w(TAG, "收到ID不匹配的数据包. Expected: $currentCollectionId, Got: ${packet.collectionId}. Packet: ${packet::class.simpleName}")
                return
            }
        }

        when (packet) {
            is DataPacket.AcknowledgeStart -> handleAckStart(packet)
            is DataPacket.DataChunk -> handleDataChunk(packet)
            is DataPacket.Notification -> handleNotification(packet)
            is DataPacket.StatusReport -> handleStatusReport(packet)
            is DataPacket.CommandStart -> handleCommandStart(packet)
            is DataPacket.CommandStop -> handleCommandStop(packet)
            is DataPacket.QueryDataStatus -> handleQueryDataStatus(packet)
            is DataPacket.Error -> handleErrorPacket(packet)
            is DataPacket.Heartbeat -> {}
        }
    }

    private fun handleStatusReport(packet: DataPacket.StatusReport) {
        clientStatus = packet.status
    }

    private fun handleAckStart(packet: DataPacket.AcknowledgeStart) {
        if (hostState == HostState.WAITING_FOR_ACK && packet.collectionId == currentCollectionId) {
            peerAckTimeoutJob?.cancel()
            hostState = HostState.COLLECTING
            Log.i(TAG, "收到从机确认，主机开始采集...")
            gaitService?.startCollection(getDataFilePath(currentCollectionId!!, isLocal = true, isHost = true))
            updateUiForRecording(true)
        }
    }

    private fun handleDataChunk(packet: DataPacket.DataChunk) {
        if (hostState != HostState.WAITING_FOR_DATA) return
        val collector = remoteDataChunkCollector.computeIfAbsent(packet.collectionId) {
            ChunkCollector(packet.totalChunks)
        }
        collector.receivedChunks.add(packet)
        val receivedCount = collector.receivedChunks.size
        updateUi { it.copy(statusText = "正在接收从机数据 (${receivedCount}/${collector.totalChunks})...") }
    }

    private fun handleNotification(packet: DataPacket.Notification) {
        when(packet.message) {
            DataPacket.Notification.MSG_UPLOAD_COMPLETE -> handleUploadComplete(packet)
            DataPacket.Notification.MSG_ANALYSIS_COMPLETE -> handleAnalysisCompleteNotification(packet)
        }
    }

    private fun handleUploadComplete(packet: DataPacket.Notification) {
        if (hostState == HostState.WAITING_FOR_DATA && packet.collectionId == currentCollectionId) {
            dataFetchStates[packet.collectionId]?.timeoutJob?.cancel()
            val collector = remoteDataChunkCollector[packet.collectionId]
            if (collector == null || collector.receivedChunks.size != collector.totalChunks) {
                showToast("数据接收不完整! 将仅分析本机数据。")
                fallbackToSingleModeAnalysis(packet.collectionId, "数据块不完整")
                return
            }

            Log.i(TAG, "从机数据接收完毕，开始拼接和分析...")
            hostState = HostState.ANALYZING
            updateUi { it.copy(isWaitingForPeer = false, isAnalyzing = true, statusText = "正在进行双腿对比分析...") }

            viewModelScope.launch(Dispatchers.Default) {
                val remoteData = collector.receivedChunks.sortedBy { it.chunkIndex }.flatMap { it.chunkData }
                val remoteFilePath = getDataFilePath(packet.collectionId, isLocal = false, isHost = true)
                saveRawDataToFile(remoteFilePath, remoteData)
                remoteDataChunkCollector.remove(packet.collectionId)
                analyzeData(packet.collectionId, remoteFilePath, isHost = true)
            }
        }
    }

    private fun handleCommandStart(packet: DataPacket.CommandStart) {
        if (_uiState.value.analysisMode == AnalysisMode.PAIRED_CLIENT && !_uiState.value.isOperating) {
            Log.i(TAG, "[CLIENT] 收到了主机的开始指令，会话ID: ${packet.collectionId}")

            ensureServiceIsRunningAndBound()

            viewModelScope.launch {
                if (!waitForServiceBound()) {
                    Log.e(TAG, "[CLIENT] 处理 CommandStart 时服务绑定失败。")
                    connectionManager.sendData(DataPacket.Error(packet.collectionId, "客户端服务未就绪"))
                    resetAllStatesAndService("客户端服务启动失败")
                    return@launch
                }

                currentCollectionId = packet.collectionId
                updateUi { it.copy(legSelection = packet.clientLegSide) }

                Log.i(TAG, "[CLIENT] 服务就绪，正在发送 AcknowledgeStart 给主机 (ID: ${packet.collectionId})")
                connectionManager.sendData(DataPacket.AcknowledgeStart(packet.collectionId))

                val filePath = getDataFilePath(packet.collectionId, isLocal = true, isHost = false)
                gaitService?.startCollection(filePath)

                clientStatus = ClientStatus.COLLECTING
                updateUiForRecording(true)
            }
        } else {
            Log.w(TAG, "[CLIENT] 收到了 CommandStart 指令，但当前状态不适用。模式: ${_uiState.value.analysisMode}, 是否操作中: ${_uiState.value.isOperating}")
        }
    }

    private fun handleCommandStop(packet: DataPacket.CommandStop) {
        if (_uiState.value.analysisMode == AnalysisMode.PAIRED_CLIENT && _uiState.value.isRecording) {
            Log.i(TAG, "从机收到停止指令，开始上传数据...")
            updateUiForRecording(false)
            gaitService?.stopAndRetrieveData()
            clientStatus = ClientStatus.UPLOADING
            updateUi { it.copy(isOperating = true, isAnalyzing = true, statusText = "正在准备上传数据...") }

            viewModelScope.launch(Dispatchers.IO) {
                val filePath = getDataFilePath(packet.collectionId, isLocal = true, isHost = false)
                val dataToSend = readRawDataFromFile(filePath)
                if (dataToSend.isEmpty()) {
                    Log.e(TAG, "读取本地数据失败或为空，无法上传。File: $filePath")
                    showToast("本地数据文件读取失败！")
                    connectionManager.sendData(DataPacket.Error(packet.collectionId, DataPacket.Error.REASON_DATA_NOT_FOUND))
                    resetAllStatesAndService()
                    return@launch
                }
                sendDataInChunks(packet.collectionId, dataToSend)
                clientStatus = ClientStatus.UPLOAD_DONE
                connectionManager.sendData(DataPacket.Notification(packet.collectionId, DataPacket.Notification.MSG_UPLOAD_COMPLETE))

                Log.i(TAG, "[CLIENT] 数据上传完毕，开始进行本地单机分析...")
                updateUi { it.copy(isAnalyzing = true, statusText = "数据上传完毕，正在本地生成报告...") }
                analyzeData(packet.collectionId, remoteDataPath = null, isHost = false)
            }
        }
    }

    private fun handleAnalysisCompleteNotification(packet: DataPacket.Notification) {
        if (_uiState.value.analysisMode == AnalysisMode.PAIRED_CLIENT) {
            Log.i(TAG, "[CLIENT] 收到主机分析完成通知，本次交互成功结束。")
            showToast("与主机的交互已成功完成！")
            resetAllStatesAndService("等待主机开始新的分析")
        }
    }

    private fun handleQueryDataStatus(packet: DataPacket.QueryDataStatus) {
        if (_uiState.value.analysisMode == AnalysisMode.PAIRED_CLIENT && packet.collectionId == currentCollectionId) {
            viewModelScope.launch {
                if (clientStatus == ClientStatus.UPLOADING || clientStatus == ClientStatus.UPLOAD_DONE) {
                    Log.i(TAG, "[CLIENT] 响应主机数据查询，重新发送上传完成通知")
                    connectionManager.sendData(DataPacket.Notification(packet.collectionId, DataPacket.Notification.MSG_UPLOAD_COMPLETE))
                } else {
                    Log.w(TAG, "[CLIENT] 收到主机数据查询，但当前状态为 $clientStatus，无法响应")
                }
            }
        }
    }

    private fun handleErrorPacket(packet: DataPacket.Error) {
        showToast("收到对方错误: ${packet.reason}")
        if (packet.reason == DataPacket.Error.REASON_DATA_NOT_FOUND && _uiState.value.analysisMode == AnalysisMode.PAIRED_HOST) {
            fallbackToSingleModeAnalysis(packet.collectionId, "从机明确表示数据丢失")
        } else {
            resetAllStatesAndService("对方发生错误，已重置")
        }
    }

    private suspend fun analyzeData(collectionId: Long, remoteDataPath: String?, isHost: Boolean) {
        val localDataPath = getDataFilePath(collectionId, isLocal = true, isHost = isHost)
        val localData = readRawDataFromFile(localDataPath).chunked(5000)
        val remoteData = remoteDataPath?.let { readRawDataFromFile(it).chunked(5000) }

        if (localData.flatten().isEmpty()) {
            handleAnalysisFailure("本地采集数据为空", isHost)
            return
        }

        if (remoteDataPath != null && (remoteData?.flatten()?.size ?: 0) < 200) {
            showToast("从机数据过少，降级为单机分析")
            if (isHost) {
                analyzeData(collectionId, null, isHost = true)
            }
            return
        }

        val legSideForAnalysis = _uiState.value.legSelection
        val remoteLegSide = if (legSideForAnalysis == LegSide.LEFT) LegSide.RIGHT else LegSide.LEFT

        val (localMetrics, remoteMetrics, comparison) = GaitAnalysisEngine.processFullAnalysis(
            localSegments = localData,
            remoteSegments = remoteData,
            legSide = legSideForAnalysis,
            remoteLegSide = remoteLegSide
        )

        if (localMetrics.totalSteps < 5) {
            handleAnalysisFailure("有效步数过少，请走更长的路程", isHost)
            return
        }

        saveResultAndNavigate(collectionId, localMetrics, remoteMetrics, comparison, isHost, legSideForAnalysis)
    }

    private suspend fun saveResultAndNavigate(
        collectionId: Long,
        localMetrics: LegMetrics,
        remoteMetrics: LegMetrics?,
        comparisonMetrics: ComparisonMetrics?,
        isHost: Boolean,
        localLeg: LegSide
    ) {
        val localDataPath = getDataFilePath(collectionId, isLocal = true, isHost = isHost)
        val remoteDataPath = if (isHost && remoteMetrics != null) getDataFilePath(collectionId, isLocal = false, isHost = true) else ""

        val localChartDataBundle = createRawDataBundle(localMetrics)
        val localChartPath = getChartDataFilePath(collectionId, isLocal = true, isHost = isHost)
        localChartDataBundle?.let { saveChartDataToFile(localChartPath, it) }

        var remoteChartPath = ""
        if (remoteMetrics != null) {
            val remoteChartDataBundle = createRawDataBundle(remoteMetrics)
            remoteChartPath = getChartDataFilePath(collectionId, isLocal = false, isHost = isHost)
            remoteChartDataBundle?.let { saveChartDataToFile(remoteChartPath, it) }
        }

        val duration = readRawDataFromFile(localDataPath).let {
            if (it.size > 1) (it.last().timestamp - it.first().timestamp) / 1_000_000_000L else 0L
        }

        val result = AnalysisResult(
            timestamp = collectionId, durationSeconds = duration.toInt(),
            mode = if (remoteMetrics != null) "Paired" else "Single",
            totalSteps = localMetrics.totalSteps + (remoteMetrics?.totalSteps ?: 0),
            overallScore = comparisonMetrics?.overallSymmetryScore ?: localMetrics.estimatedSymmetryScore,
            localMetricsJson = json.encodeToString(localMetrics),
            remoteMetricsJson = remoteMetrics?.let { json.encodeToString(it) } ?: "{}",
            comparisonMetricsJson = comparisonMetrics?.let { json.encodeToString(it) } ?: "{}",
            localLegSide = localLeg.name,
            remoteLegSide = if (remoteMetrics != null) (if (localLeg == LegSide.LEFT) LegSide.RIGHT else LegSide.LEFT).name else "",
            localDataFilePath = localDataPath,
            remoteDataFilePath = remoteDataPath,
            localChartDataPath = localChartPath,
            remoteChartDataPath = remoteChartPath
        )

        val resultId = repository.insertAnalysisResult(result)
        Log.i(TAG, "分析结果已保存，ID: $resultId. isHost: $isHost")

        withContext(Dispatchers.Main) {
            _navigateToResultEvent.emit(resultId)
            if (isHost) {
                if (_uiState.value.analysisMode == AnalysisMode.PAIRED_HOST) {
                    collectionId?.let { connectionManager.sendData(DataPacket.Notification(it, DataPacket.Notification.MSG_ANALYSIS_COMPLETE)) }
                }
                resetAllStatesAndService("分析完成！")
            } else {
                updateUi { it.copy(isAnalyzing = false, statusText = "本地报告已生成，等待主机完成...") }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GaitService.LocalBinder
            gaitService = binder.getService()
            isServiceBound = true
            Log.i(TAG, "GaitService 已绑定。")
            monitorServiceState()
            if (_uiState.value.analysisMode == AnalysisMode.PAIRED_CLIENT) {
                clientStatus = ClientStatus.READY
                updateUi { it.copy(statusText = "服务就绪，等待主机指令...") }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false; gaitService = null
            Log.w(TAG, "GaitService 意外断开。")
            if(_uiState.value.isOperating) { resetAllStatesAndService("服务连接已断开") }
        }
    }

    private fun startListeners() {
        connectionListenerJob?.cancel()
        connectionListenerJob = viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                if (state is ConnectionState.Disconnected && _uiState.value.isRecording) {
                    showToast("连接已断开，将仅分析本机数据")
                    gaitService?.stopAndRetrieveData()
                    updateUiForRecording(false)
                    val isHost = _uiState.value.analysisMode == AnalysisMode.PAIRED_HOST
                    val legSide = _uiState.value.legSelection

                    setAnalysisMode(AnalysisMode.SINGLE)
                    updateUi { it.copy(legSelection = legSide) }

                    updateUi { it.copy(isAnalyzing = true, statusText = "正在分析本机数据...") }
                    viewModelScope.launch(Dispatchers.Default) {
                        currentCollectionId?.let { analyzeData(it, null, isHost) }
                    }
                    return@collect
                }
                if (_uiState.value.analysisMode == AnalysisMode.PAIRED_CLIENT) {
                    if (state is ConnectionState.Connected) startStatusReporter() else stopStatusReporter()
                }
                if (!_uiState.value.isOperating) {
                    updateUi { it.copy(
                        connectionState = state,
                        statusText = getStatusTextForConnectionState(state, it.analysisMode),
                        isStartButtonDisabledForClient = (it.analysisMode == AnalysisMode.PAIRED_CLIENT && state !is ConnectionState.Connected)
                    )}
                } else { updateUi { it.copy(connectionState = state) } }
            }
        }
        viewModelScope.launch {
            connectionManager.receivedDataPackets.collect { packet -> handleDataPacket(packet) }
        }
    }

    private fun monitorServiceState() {
        serviceMonitoringJob?.cancel()
        serviceMonitoringJob = viewModelScope.launch {
            gaitService?.serviceState?.collect { state ->
                val isWalking = state is GaitService.ServiceState.Walking
                if (_uiState.value.isWalking != isWalking) { updateUi { it.copy(isWalking = isWalking) } }
                if (_uiState.value.isRecording) {
                    val text = when (state) {
                        is GaitService.ServiceState.Collecting -> "采集中... (${state.pointCount}点)"
                        is GaitService.ServiceState.Walking -> "检测到步行... (${state.pointCount}点)"
                        else -> _uiState.value.statusText
                    }
                    updateUi { it.copy(statusText = text) }
                }
            }
        }
    }

    private fun updateUi(update: (UiState) -> UiState) = _uiState.update(update)

    private fun getIdleTextForMode(mode: AnalysisMode) = when (mode) {
        AnalysisMode.SINGLE -> "将手机固定于大腿\n点击开始分析"
        else -> "请选择联机角色或等待连接"
    }

    private fun getStatusTextForConnectionState(state: ConnectionState, mode: AnalysisMode): String = when (state) {
        is ConnectionState.Connected -> if (state.isHost) "已作为主机连接，请开始分析" else "已连接到主机，等待指令..."
        is ConnectionState.Connecting -> state.message
        is ConnectionState.Disconnected -> "${state.reason}，已断开连接。"
        is ConnectionState.Discovering -> state.message
        is ConnectionState.Error -> "连接错误: ${state.message}"
        ConnectionState.Idle -> getIdleTextForMode(mode)
        ConnectionState.StartingServer -> "正在启动主机..."
        is ConnectionState.WaitingForClient -> state.message
    }

    private fun updateUiForRecording(isRecording: Boolean) {
        if (isRecording) {
            updateUi { it.copy(isOperating = true, isRecording = true, isWaitingForPeer = false, isAnalyzing = false, statusText = "准备采集中...") }
        } else {
            updateUi { it.copy(isRecording = false) }
        }
    }

    private fun resetAllStatesAndService(statusText: String? = null) {
        currentCollectionId?.let {
            val isHost = _uiState.value.analysisMode == AnalysisMode.PAIRED_HOST
            try {
                File(getDataFilePath(it, isLocal = true, isHost = isHost)).delete()
                File(getDataFilePath(it, isLocal = false, isHost = isHost)).delete()
                File(getChartDataFilePath(it, isLocal = true, isHost = isHost)).delete()
                File(getChartDataFilePath(it, isLocal = false, isHost = isHost)).delete()
            } catch (e: Exception) { Log.w(TAG, "清理孤立文件失败", e) }
        }

        currentCollectionId = null
        hostState = HostState.IDLE
        clientStatus = ClientStatus.IDLE
        peerAckTimeoutJob?.cancel()
        dataFetchStates.values.forEach { it.timeoutJob?.cancel() }
        dataFetchStates.clear()
        remoteDataChunkCollector.clear()
        if (isServiceBound) unbindAndStopGaitService()
        updateUi {
            it.copy(
                isOperating = false,
                isRecording = false,
                isAnalyzing = false,
                isWaitingForPeer = false,
                statusText = statusText ?: getIdleTextForMode(it.analysisMode),
                isStartButtonDisabledForClient = (it.analysisMode == AnalysisMode.PAIRED_CLIENT && it.connectionState !is ConnectionState.Connected)
            )
        }
        Log.i(TAG, "所有状态已重置。")
    }

    private fun startAckTimeout(collectionId: Long) {
        peerAckTimeoutJob?.cancel()
        peerAckTimeoutJob = viewModelScope.launch {
            delay(30_000L)
            if (isActive && hostState == HostState.WAITING_FOR_ACK && currentCollectionId == collectionId) {
                val msg = "从机确认超时，流程已取消"
                Log.e(TAG, "超时: $msg (ID: $collectionId)")
                showToast(msg)
                resetAllStatesAndService(msg)
            }
        }
    }

    private fun startDataFetchTimeout(collectionId: Long) {
        val state = dataFetchStates.computeIfAbsent(collectionId) { DataFetchState() }
        state.timeoutJob?.cancel()
        state.timeoutJob = viewModelScope.launch {
            delay(45_000L) // 等待45秒
            if (isActive && hostState == HostState.WAITING_FOR_DATA && currentCollectionId == collectionId) {
                if (state.retryCount < 3) {
                    state.retryCount++
                    Log.w(TAG, "接收数据超时，发起第 ${state.retryCount} 次查询...")
                    showToast("未收到从机数据，正在尝试重新查询... (${state.retryCount}/3)")
                    connectionManager.sendData(DataPacket.QueryDataStatus(collectionId))
                    startDataFetchTimeout(collectionId) // 重新启动下一个超时
                } else {
                    val msg = "接收从机数据超时，将仅分析本机数据"
                    Log.e(TAG, "超时: $msg (ID: $collectionId)")
                    showToast(msg)
                    fallbackToSingleModeAnalysis(collectionId, "重试多次后依然超时")
                }
            }
        }
    }

    private fun fallbackToSingleModeAnalysis(collectionId: Long, reason: String) {
        Log.w(TAG, "降级为单机分析。原因: $reason")
        dataFetchStates.remove(collectionId)
        hostState = HostState.ANALYZING
        updateUi { it.copy(isWaitingForPeer = false, isAnalyzing = true, statusText = "正在分析本机数据...") }
        viewModelScope.launch(Dispatchers.Default) {
            analyzeData(collectionId, null, isHost = true)
        }
    }

    private fun getDataFilePath(collectionId: Long, isLocal: Boolean, isHost: Boolean): String {
        val role = if(isHost) "host" else "client"
        val type = if(isLocal) "local" else "remote"
        val fileName = "gsd_${collectionId}_${role}_$type.json"
        return File(application.filesDir, fileName).absolutePath
    }

    private fun getChartDataFilePath(collectionId: Long, isLocal: Boolean, isHost: Boolean): String {
        val role = if(isHost) "host" else "client"
        val type = if(isLocal) "local" else "remote"
        val fileName = "chart_${collectionId}_${role}_$type.json"
        return File(application.filesDir, fileName).absolutePath
    }

    private fun saveRawDataToFile(filePath: String, data: List<SensorDataPoint>) {
        try { File(filePath).writeText(json.encodeToString(data)) } catch (e: Exception) { Log.e(TAG, "保存原始数据文件失败: $filePath", e) }
    }

    private fun saveChartDataToFile(filePath: String, data: RawDataBundle) {
        try { File(filePath).writeText(json.encodeToString(data)) } catch (e: Exception) { Log.e(TAG, "保存图表数据文件失败: $filePath", e) }
    }


    private fun readRawDataFromFile(filePath: String): List<SensorDataPoint> = try {
        File(filePath).takeIf { it.exists() }?.readText()?.let { json.decodeFromString(it) } ?: emptyList()
    } catch (e: Exception) { Log.e(TAG, "从文件读取原始数据失败: $filePath", e); emptyList() }


    private suspend fun sendDataInChunks(collectionId: Long, allData: List<SensorDataPoint>) {
        val chunkSize = 1000
        if (allData.isEmpty()) return
        val totalChunks = (allData.size + chunkSize - 1) / chunkSize
        allData.chunked(chunkSize).forEachIndexed { index, chunkData ->
            val packet = DataPacket.DataChunk(collectionId, chunkData, index, totalChunks)
            try {
                withContext(Dispatchers.Main) { updateUi { it.copy(statusText = "正在发送数据 (${index + 1}/$totalChunks)...") } }
                connectionManager.sendData(packet)
                delay(50)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showToast("数据发送失败")
                resetAllStatesAndService("数据传输中断")
                currentCoroutineContext().cancel()
            }
        }
    }

    private fun handleAnalysisFailure(reason: String, isHost: Boolean) {
        currentCollectionId?.let {
            try {
                File(getDataFilePath(it, isLocal = true, isHost = isHost)).delete()
                File(getChartDataFilePath(it, isLocal = true, isHost = isHost)).delete()
            } catch(e: Exception) { Log.w(TAG, "清理失败文件时出错", e) }
        }

        viewModelScope.launch(Dispatchers.Main) {
            showToast("分析失败: $reason")
            if (isHost && _uiState.value.analysisMode == AnalysisMode.PAIRED_HOST) {
                currentCollectionId?.let { connectionManager.sendData(DataPacket.Error(it, DataPacket.Error.REASON_ANALYSIS_FAILED)) }
            }
            resetAllStatesAndService("分析失败")
        }
    }

    private fun ensureServiceIsRunningAndBound() {
        if (isServiceBound) return
        Log.i(TAG, "正在启动并绑定 GaitService...")
        Intent(application, GaitService::class.java).also { intent ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    application.startForegroundService(intent)
                } else { application.startService(intent) }
                application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.e(TAG, "启动或绑定服务时发生异常", e)
                if (e is IllegalStateException || e.javaClass.name.contains("ForegroundServiceStartNotAllowedException")) {
                    showToast("应用在后台，无法启动服务。请保持App在前台进行操作。")
                } else { showToast("无法启动后台服务: ${e.message}") }
                resetAllStatesAndService("服务启动异常")
            }
        }
    }

    private fun unbindAndStopGaitService() {
        serviceMonitoringJob?.cancel()
        if (isServiceBound) {
            try { application.unbindService(serviceConnection) } catch (e: Exception) { Log.w(TAG, "解绑服务失败", e) }
            isServiceBound = false
        }
        gaitService?.releaseServiceResources()
        gaitService = null
        Log.i(TAG, "服务已解绑并停止。")
    }

    private fun createRawDataBundle(metrics: LegMetrics) =
        if (metrics.rawFlexionAngles.isEmpty()) null
        else RawDataBundle(metrics.rawFlexionAngles, metrics.rawAbductionAngles, metrics.rawGaitCycles, metrics.rawStepLengths, metrics.rawTimestamps)

    private fun showToast(message: String) = viewModelScope.launch(Dispatchers.Main) { Toast.makeText(application, message, Toast.LENGTH_LONG).show() }

    private fun startStatusReporter() {
        if (statusReporterJob?.isActive == true) return
        statusReporterJob = viewModelScope.launch(Dispatchers.IO) {
            Log.i(TAG, "[CLIENT] 状态报告器已启动。")
            while (isActive) {
                val batteryPct = getBatteryLevel()
                val report = DataPacket.StatusReport(currentCollectionId ?: 0L, clientStatus, batteryPct)
                connectionManager.sendData(report)
                delay(5000L)
            }
        }
    }

    private fun stopStatusReporter() {
        statusReporterJob?.cancel()
        statusReporterJob = null
        Log.i(TAG, "[CLIENT] 状态报告器已停止。")
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = application.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private suspend fun waitForServiceBound(): Boolean {
        var attempt = 0
        while (!isServiceBound && attempt < 25) { // Wait for up to 5 seconds
            delay(200); attempt++
        }
        if (!isServiceBound || gaitService == null) {
            showToast("服务启动失败，请重试")
            resetAllStatesAndService(); return false
        }
        return true
    }

    private fun getSensorInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val sm = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
            val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
            val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
            val info = "传感器支持: 加速计(${if (acc) "✓" else "✗"}) 陀螺仪(${if (gyro) "✓" else "✗"}) 磁力计(${if (mag) "✓" else "✗"}) 气压计(${if (pressure) "✓" else "✗"})"
            _uiState.update { it.copy(sensorInfoText = info) }
        }
    }

    private fun cleanupOrphanedDataFiles() = viewModelScope.launch(Dispatchers.IO) {
        Log.d(TAG, "开始执行僵尸文件清理检查...")
        try {
            val allResults = repository.getAllAnalysisResults().first()
            val referencedPaths = mutableSetOf<String>()
            allResults.forEach {
                if (it.localDataFilePath.isNotEmpty()) referencedPaths.add(it.localDataFilePath)
                if (it.remoteDataFilePath.isNotEmpty()) referencedPaths.add(it.remoteDataFilePath)
                if (it.localChartDataPath.isNotEmpty()) referencedPaths.add(it.localChartDataPath)
                if (it.remoteChartDataPath.isNotEmpty()) referencedPaths.add(it.remoteChartDataPath)
            }
            Log.d(TAG, "数据库中引用的文件数量: ${referencedPaths.size}")

            val filesDir = application.filesDir
            val dataFiles = filesDir.listFiles { _, name -> (name.startsWith("gsd_") || name.startsWith("chart_")) && name.endsWith(".json") }
            if (dataFiles == null || dataFiles.isEmpty()) {
                Log.d(TAG, "没有找到数据文件，无需清理。")
                return@launch
            }
            Log.d(TAG, "在存储中找到 ${dataFiles.size} 个数据文件。")

            var deletedCount = 0
            dataFiles.forEach { file ->
                if (!referencedPaths.contains(file.absolutePath)) {
                    if (file.delete()) {
                        deletedCount++
                        Log.i(TAG, "已删除僵尸文件: ${file.name}")
                    } else {
                        Log.w(TAG, "删除僵尸文件失败: ${file.name}")
                    }
                }
            }
            if (deletedCount > 0) {
                Log.i(TAG, "僵尸文件清理完成，共删除 $deletedCount 个文件。")
            } else {
                Log.d(TAG, "未发现僵尸文件，清理完成。")
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行僵尸文件清理时发生错误", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel onCleared.")
        connectionListenerJob?.cancel()
        stopStatusReporter()
        disconnect()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}

object ExportManager {
    private var content: String? = null
    fun setContentToExport(jsonContent: String) {
        content = jsonContent
    }
    fun getContentToExport(): String? {
        val temp = content
        content = null // Consume content after getting it
        return temp
    }
}
