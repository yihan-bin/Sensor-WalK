// 文件: app/src/main/java/com/example/sensorwalk/viewmodel/MainViewModel.kt

package com.example.sensorwalk.viewmodel

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sensorwalk.analysis.GaitAnalysisEngine
import com.example.sensorwalk.connectivity.ConnectionManager
import com.example.sensorwalk.connectivity.ConnectionState
import com.example.sensorwalk.connectivity.DataPacket
import com.example.sensorwalk.data.*
import com.example.sensorwalk.service.GaitService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class UiState(
    val isRecording: Boolean = false,
    val isAnalyzing: Boolean = false,
    val isWaitingForRemote: Boolean = false,
    val isWalking: Boolean = false,
    val statusText: String = "将手机固定于大腿\n点击开始分析",
    val analysisMode: AnalysisMode = AnalysisMode.SINGLE,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val showDisclaimer: Boolean = false,
    val legSelection: LegSide = LegSide.LEFT,
    val isStartButtonDisabledForClient: Boolean = false,
    val sensorInfoText: String = "正在获取传感器信息..."
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: DataRepository,
    private val connectionManager: ConnectionManager,
    @ApplicationContext private val application: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _navigateToResultEvent = MutableSharedFlow<Long>()
    val navigateToResultEvent = _navigateToResultEvent.asSharedFlow()

    val allResults: Flow<List<AnalysisResult>> = repository.getAllAnalysisResults()
    suspend fun getResultById(id: Long): AnalysisResult? = repository.getAnalysisResult(id)

    private var gaitService: GaitService? = null
    private var isServiceBound = false
    private var serviceStateJob: Job? = null

    private var connectionJobs: Job? = null
    private var localDataForPairing: List<List<SensorDataPoint>>? = null

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    init {
        checkIfFirstLaunch()
        listenToConnectionManagerEvents()
        getSensorInfo()
    }

    private fun checkIfFirstLaunch() {
        if (prefs.getBoolean("is_first_launch", true)) {
            _uiState.update { it.copy(showDisclaimer = true) }
        }
    }

    fun onDisclaimerConfirmed() {
        prefs.edit().putBoolean("is_first_launch", false).apply()
        _uiState.update { it.copy(showDisclaimer = false) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GaitService.LocalBinder
            gaitService = binder.getService()
            isServiceBound = true
            serviceStateJob?.cancel()
            serviceStateJob = viewModelScope.launch {
                gaitService?.isRecording?.collect { isRec -> _uiState.update { it.copy(isRecording = isRec) } }
            }
            viewModelScope.launch {
                gaitService?.isWalking?.collect { isWalk -> _uiState.update { it.copy(isWalking = isWalk) } }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            gaitService = null
            serviceStateJob?.cancel()
            _uiState.update { it.copy(isRecording = false, isWalking = false) }
        }
    }

    private fun getSensorInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensorsToQuery = mapOf(
                "加速度计" to Sensor.TYPE_ACCELEROMETER,
                "陀螺仪" to Sensor.TYPE_GYROSCOPE,
                "磁力计" to Sensor.TYPE_MAGNETIC_FIELD
            )

            val infoText = sensorsToQuery.map { (name, type) ->
                val sensor = sensorManager.getDefaultSensor(type)
                if (sensor != null) {
                    val maxRate = if (sensor.minDelay > 0) (1_000_000 / sensor.minDelay) else 0
                    "$name: ${maxRate}Hz"
                } else {
                    "$name: 不可用"
                }
            }.joinToString(" | ")

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(sensorInfoText = "最大采样率: $infoText") }
            }
        }
    }

    private fun listenToConnectionManagerEvents() {
        connectionJobs?.cancel()
        connectionJobs = viewModelScope.launch {
            launch { listenToConnectionChanges() }
            launch { listenToReceivedData() }
        }
    }

    fun toggleAnalysis(context: Context) {
        if (uiState.value.isStartButtonDisabledForClient && uiState.value.analysisMode == AnalysisMode.PAIRED_CLIENT) return

        if (uiState.value.isRecording) {
            if (uiState.value.analysisMode == AnalysisMode.PAIRED_HOST && uiState.value.connectionState is ConnectionState.Connected) {
                viewModelScope.launch { connectionManager.sendData(DataPacket.ControlCommand("STOP")) }
            }
            stopAndAnalyze(context.applicationContext)
        } else {
            if (uiState.value.analysisMode == AnalysisMode.PAIRED_HOST && uiState.value.connectionState is ConnectionState.Connected) {
                viewModelScope.launch {
                    val remoteLeg = if (uiState.value.legSelection == LegSide.LEFT) LegSide.RIGHT else LegSide.LEFT
                    connectionManager.sendData(DataPacket.ControlCommand("START", remoteLeg))
                }
            }
            startAnalysis(context.applicationContext)
        }
    }

    fun setLegSelection(leg: LegSide) {
        if (uiState.value.isRecording || uiState.value.isAnalyzing) return
        _uiState.update { it.copy(legSelection = leg) }
    }

    fun setAnalysisMode(mode: AnalysisMode) {
        if (uiState.value.isRecording || uiState.value.isAnalyzing) return
        if (uiState.value.connectionState !is ConnectionState.Idle) {
            connectionManager.disconnect()
        }
        _uiState.update { it.copy(
            analysisMode = mode,
            isStartButtonDisabledForClient = (mode == AnalysisMode.PAIRED_CLIENT),
            statusText = if (mode == AnalysisMode.SINGLE) "将手机固定于大腿\n点击开始分析" else "请在设置页选择联机角色",
            connectionState = ConnectionState.Idle
        ) }
    }

    fun startHosting() {
        setAnalysisMode(AnalysisMode.PAIRED_HOST)
        connectionManager.startServer()
    }

    fun startJoining() {
        setAnalysisMode(AnalysisMode.PAIRED_CLIENT)
        connectionManager.startDiscovery()
    }

    // ★★★ [修复 #21]: 关键修改。主动断开连接时，无论之前是什么角色，都恢复到可以独立工作的单机模式 ★★★
    fun disconnect() {
        connectionManager.disconnect()
        _uiState.update {
            it.copy(
                connectionState = ConnectionState.Idle,
                // 强制重置为单机模式，确保UI和逻辑都回到可独立操作的状态
                analysisMode = AnalysisMode.SINGLE,
                statusText = "已断开连接\n可进行单机分析",
                isStartButtonDisabledForClient = false // 解除从机的按钮禁用
            )
        }
    }

    private fun startAnalysis(context: Context) {
        val intent = Intent(context, GaitService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        localDataForPairing = null
        val legSideText = if(uiState.value.legSelection == LegSide.LEFT) "左腿" else "右腿"
        val status = when (uiState.value.analysisMode) {
            AnalysisMode.SINGLE -> "单机模式 ($legSideText): \n采集中..."
            AnalysisMode.PAIRED_HOST -> "主机 ($legSideText): \n采集中..."
            AnalysisMode.PAIRED_CLIENT -> "从机 ($legSideText): \n等待主机指令..."
        }
        _uiState.update { it.copy(
            isRecording = true,
            statusText = status,
            isAnalyzing = false,
            isWaitingForRemote = false
        )}
    }

    private fun stopAndAnalyze(context: Context) {
        if (!isServiceBound || gaitService == null) {
            _uiState.update { it.copy(isRecording = false, statusText = "服务异常，无法停止。") }
            return
        }

        val rawData = gaitService!!.stopRecordingAndGetData()
        unbindService(context)

        _uiState.update { it.copy(isRecording = false, isAnalyzing = true, statusText = "正在预处理数据...") }

        viewModelScope.launch(Dispatchers.Default) {
            val sampleRate = GaitAnalysisEngine.calculateSampleRate(rawData)
            val walkingSegments = GaitAnalysisEngine.detectWalkingActivity(rawData, sampleRate)

            if (walkingSegments.isEmpty() || walkingSegments.flatten().size < sampleRate * 3) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isAnalyzing = false, statusText = "分析失败：\n未检测到足够的走路数据。") }
                }
                if (uiState.value.analysisMode == AnalysisMode.PAIRED_HOST) {
                    viewModelScope.launch { connectionManager.sendData(DataPacket.AnalysisFailure("主机走路数据不足")) }
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(statusText = "正在分析步态参数...") }
            }

            val isConnected = connectionManager.connectionState.value is ConnectionState.Connected

            when (uiState.value.analysisMode) {
                AnalysisMode.SINGLE -> processSingleMode(walkingSegments)
                AnalysisMode.PAIRED_CLIENT -> {
                    if (isConnected) processClientMode(walkingSegments)
                    else {
                        showToast("连接丢失，降级为单机分析")
                        processSingleMode(walkingSegments)
                    }
                }
                AnalysisMode.PAIRED_HOST -> {
                    if (isConnected) {
                        localDataForPairing = walkingSegments
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy(isWaitingForRemote = true, isAnalyzing = false, statusText = "主机分析完成，等待从机数据...") }
                        }
                    } else {
                        showToast("连接丢失，降级为单机分析")
                        processSingleMode(walkingSegments)
                    }
                }
            }
        }
    }

    private suspend fun processSingleMode(localSegments: List<List<SensorDataPoint>>) {
        val (localMetrics, _, _) = GaitAnalysisEngine.processFullAnalysis(
            localSegments = localSegments,
            legSide = uiState.value.legSelection
        )
        saveAndNavigate(localMetrics)
    }

    private suspend fun processClientMode(segments: List<List<SensorDataPoint>>) {
        connectionManager.sendData(DataPacket.SensorData(segments))
        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(isAnalyzing = false, statusText = "数据已发送至主机，\n请在主机查看结果。") }
        }
    }

    private suspend fun listenToConnectionChanges() {
        connectionManager.connectionState.collect { state ->
            withContext(Dispatchers.Main.immediate) {
                val currentState = _uiState.value
                var updatedState = currentState.copy(connectionState = state)

                if (state is ConnectionState.Connected && !state.isHost) {
                    updatedState = updatedState.copy(isStartButtonDisabledForClient = true)
                }

                // ★★★ [修复 #21]: 关键修改。处理意外断连的情况 ★★★
                if (state is ConnectionState.Disconnected) {
                    updatedState = updatedState.copy(
                        analysisMode = AnalysisMode.SINGLE, // 强制恢复到单机模式
                        isStartButtonDisabledForClient = false,
                        statusText = "连接已断开\n可进行单机分析"
                    )
                    // 如果主机正在等待从机数据时断连，则自动降级为单腿分析
                    if (currentState.isWaitingForRemote) {
                        localDataForPairing?.let { localData ->
                            showToast("连接丢失！降级为单腿模式分析。")
                            viewModelScope.launch(Dispatchers.Default) {
                                processSingleMode(localData)
                                localDataForPairing = null
                            }
                        }
                    }
                }
                _uiState.value = updatedState
            }
        }
    }

    private suspend fun listenToReceivedData() {
        connectionManager.receivedDataPackets.collect { packet ->
            when(packet) {
                is DataPacket.SensorData -> handleRemoteData(packet)
                is DataPacket.ControlCommand -> handleControlCommand(packet)
                is DataPacket.AnalysisFailure -> {
                    showToast("远端分析失败: ${packet.reason}")
                    if(_uiState.value.isWaitingForRemote) {
                        localDataForPairing?.let { localData ->
                            viewModelScope.launch(Dispatchers.Default) {
                                processSingleMode(localData)
                                localDataForPairing = null
                            }
                        }
                    }
                }
                is DataPacket.Heartbeat -> { /* Connection is alive */ }
            }
        }
    }

    private fun handleRemoteData(packet: DataPacket.SensorData) {
        val currentState = _uiState.value
        if (currentState.analysisMode == AnalysisMode.PAIRED_HOST && currentState.isWaitingForRemote && localDataForPairing != null) {
            _uiState.update { it.copy(isWaitingForRemote = false, isAnalyzing = true, statusText = "收到从机数据，开始对比分析...") }

            val hostLeg = currentState.legSelection
            val remoteLeg = if(hostLeg == LegSide.LEFT) LegSide.RIGHT else LegSide.LEFT

            viewModelScope.launch(Dispatchers.Default) {
                val (local, remote, comparison) = GaitAnalysisEngine.processFullAnalysis(
                    localSegments = localDataForPairing!!,
                    remoteSegments = packet.segments,
                    legSide = hostLeg,
                    remoteLegSide = remoteLeg
                )
                saveAndNavigate(local, remote, comparison)
                localDataForPairing = null
            }
        }
    }

    private fun handleControlCommand(packet: DataPacket.ControlCommand) {
        when(packet.command) {
            "START" -> {
                if (_uiState.value.analysisMode == AnalysisMode.PAIRED_CLIENT && packet.legSide != null && !_uiState.value.isRecording) {
                    val legSideText = if (packet.legSide == LegSide.LEFT) "左腿" else "右腿"
                    _uiState.update { it.copy(
                        legSelection = packet.legSide,
                        statusText = "从机 ($legSideText): \n采集中..."
                    ) }
                    startAnalysis(application)
                }
            }
            "STOP" -> {
                if (_uiState.value.analysisMode == AnalysisMode.PAIRED_CLIENT && _uiState.value.isRecording) {
                    stopAndAnalyze(application)
                }
            }
        }
    }

    private suspend fun saveAndNavigate(localMetrics: LegMetrics, remoteMetrics: LegMetrics? = null, comp: ComparisonMetrics? = null) {
        val localLegSide = uiState.value.legSelection
        val remoteLegSide = if (remoteMetrics != null) {
            if (localLegSide == LegSide.LEFT) LegSide.RIGHT else LegSide.LEFT
        } else {
            null
        }

        val result = AnalysisResult(
            timestamp = System.currentTimeMillis(),
            durationSeconds = localMetrics.rawTimestamps.lastOrNull()?.toInt() ?: 0,
            mode = if (remoteMetrics != null) "Paired" else "Single",
            totalSteps = localMetrics.totalSteps + (remoteMetrics?.totalSteps ?: 0),
            overallScore = comp?.overallSymmetryScore ?: localMetrics.estimatedSymmetryScore,
            localMetricsJson = json.encodeToString(localMetrics),
            remoteMetricsJson = remoteMetrics?.let { json.encodeToString(it) } ?: "",
            comparisonMetricsJson = comp?.let { json.encodeToString(it) } ?: "",
            localRawDataJson = json.encodeToString(createRawDataBundle(localMetrics)),
            remoteRawDataJson = remoteMetrics?.let { json.encodeToString(createRawDataBundle(it)) } ?: "",
            localLegSide = localLegSide.name,
            remoteLegSide = remoteLegSide?.name ?: ""
        )
        val newId = repository.saveAnalysisResult(result)

        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(
                isAnalyzing = false,
                isWaitingForRemote = false,
                statusText = "分析完成！\n请在历史记录中查看详情。"
            ) }
            _navigateToResultEvent.emit(newId)
        }
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createRawDataBundle(metrics: LegMetrics): RawDataBundle {
        return RawDataBundle(
            flexionAngles = metrics.rawFlexionAngles,
            abductionAngles = metrics.rawAbductionAngles,
            gaitCycles = metrics.rawGaitCycles, // 新增
            stepLengths = metrics.rawStepLengths, // 新增
            timestamps = metrics.rawTimestamps
        )
    }

    private fun unbindService(context: Context) {
        if (isServiceBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.e("ViewModel", "Service not registered: ${e.message}")
            } finally {
                isServiceBound = false
                gaitService = null
            }
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteResult(id) }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) { repository.clearAllResults() }
    }

    fun exportAllHistory(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val results = allResults.first()
            if (results.isEmpty()) {
                showToast("没有历史记录可导出")
                return@launch
            }

            val jsonString = json.encodeToString(results)
            val fileName = "GaitHistory_${System.currentTimeMillis()}.json"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SensorWalk")
                }
            }

            try {
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it).use { outputStream ->
                        outputStream?.write(jsonString.toByteArray())
                    }
                    showToast("历史记录已导出到 'Download/SensorWalk' 文件夹")
                } ?: throw Exception("Failed to create MediaStore entry.")
            } catch (e: Exception) {
                Log.e("Export", "Failed to export history", e)
                showToast("导出失败: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        unbindService(application)
        connectionJobs?.cancel()
        serviceStateJob?.cancel()
        connectionManager.disconnect()
    }
}
