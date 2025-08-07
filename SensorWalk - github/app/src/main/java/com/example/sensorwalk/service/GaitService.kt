// 文件: app/src/main/java/com/example/sensorwalk/service/GaitService.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 核心重构：将数据采集方式从“内存缓存”重构为“文件流式写入”，极大地降低了内存占用，并实现了数据采集与处理的解耦，确保了息屏后台运行的稳定性。
 * 2025-07-30 - Gemini-AI - 数据采集流重构。将数据采集方式由内存列表缓存改为文件流式写入（需求2）。`startCollection`现在接收一个`filePath`参数，并使用`BufferedWriter`将`SensorDataPoint`实时序列化并写入磁盘。此修改极大降低了内存占用，解决了长时间采集导致的OOM问题，并增强了息屏后台运行的稳定性（核心任务目标5, 6）。
 */

package com.example.sensorwalk.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.sensorwalk.MainActivity
import com.example.sensorwalk.R
import com.example.sensorwalk.data.SensorDataPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.math3.stat.descriptive.moment.Variance
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.math.pow
import kotlin.math.sqrt

class GaitService : Service(), SensorEventListener {

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null

    // 传感器实例
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var pressureSensor: Sensor? = null

    // 状态管理
    @Volatile private var isCollectingData = false
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState = _serviceState.asStateFlow()

    // ★★★ 本次修改：数据采集与文件写入 ★★★
    private var dataFileWriter: BufferedWriter? = null
    private var isFirstDataPointInFile = true
    private val json = Json
    @Volatile private var totalPointsCollected = 0

    // 步行检测
    private val _isWalkingFlow = MutableStateFlow(false)
    val isWalkingFlow = _isWalkingFlow.asStateFlow()
    private val WALK_DETECTION_WINDOW_SIZE = 100
    private val WALK_VARIANCE_THRESHOLD = 0.3
    private val walkingDetectionBuffer = mutableListOf<SensorDataPoint>()

    // 实时传感器值缓存
    private val lastAcc = FloatArray(3)
    private val lastGyro = FloatArray(3)
    private val lastMag = FloatArray(3)
    private var lastPressure: Float = 0f
    private var lastAccTimestamp: Long = 0

    // 通知与系统广播
    private var lastNotificationUpdateTime = 0L
    private val NOTIFICATION_UPDATE_INTERVAL_MS = 2000L
    private var screenStateReceiver: BroadcastReceiver? = null


    sealed class ServiceState {
        object Idle : ServiceState()
        data class Collecting(val pointCount: Int) : ServiceState()
        data class Walking(val pointCount: Int) : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    inner class LocalBinder : Binder() {
        fun getService(): GaitService = this@GaitService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GaitService created.")
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        setupPowerManagement()
        setupScreenStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "GaitService onStartCommand.")
        val notification = createNotification("等待开始采集数据...")
        try {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0 // Not needed for older versions
            }
            startForeground(SERVICE_ID, notification, serviceType)
            Log.i(TAG, "服务已成功提升为前台服务。")
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败 (startForeground)", e)
            _serviceState.value = ServiceState.Error("无法启动前台服务: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    fun startCollection(filePath: String) {
        if (isCollectingData) {
            Log.w(TAG, "数据采集已在进行中，忽略本次请求。")
            return
        }
        Log.i(TAG, "★★★ 开始数据采集，将写入文件: $filePath ★★★")

        resetCollectionState()

        // 初始化文件写入器
        try {
            val file = File(filePath)
            // 如果存在旧文件，先删除
            if(file.exists()) file.delete()
            dataFileWriter = BufferedWriter(FileWriter(file, false)) // false to overwrite
            dataFileWriter?.write("[") // 写入JSON数组的开头
            isFirstDataPointInFile = true
        } catch (e: Exception) {
            Log.e(TAG, "初始化文件写入器失败", e)
            _serviceState.value = ServiceState.Error("无法创建数据文件")
            return
        }

        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(3 * 60 * 60 * 1000L) // 申请3小时的锁
                Log.i(TAG, "唤醒锁 (WakeLock) 已获取。")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取唤醒锁失败", e)
        }

        registerSensors()
        isCollectingData = true
        _serviceState.value = ServiceState.Collecting(0)
        updateNotification("正在采集数据... (0 点)")
    }

    fun stopAndRetrieveData() {
        if (!isCollectingData) return
        Log.i(TAG, "★★★ 停止数据采集 ★★★")

        sensorManager.unregisterListener(this)
        isCollectingData = false
        _isWalkingFlow.value = false

        // 关闭文件写入器
        try {
            dataFileWriter?.write("]") // 写入JSON数组的结尾
            dataFileWriter?.close()
            dataFileWriter = null
        } catch (e: Exception) {
            Log.e(TAG, "关闭文件写入器失败", e)
        }

        updateNotification("采集完成，总计 $totalPointsCollected 点")
        Log.i(TAG, "数据采集停止，总数据点: $totalPointsCollected")
        releaseWakeLock()

        // 状态重置在 ViewModel 中完成
    }

    fun releaseServiceResources() {
        Log.i(TAG, "正在释放服务所有资源...")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isCollectingData) return

        val timestamp = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAcc, 0, 3)
                lastAccTimestamp = timestamp
            }
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, lastGyro, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, lastMag, 0, 3)
            Sensor.TYPE_PRESSURE -> lastPressure = event.values.firstOrNull() ?: 0f
            else -> return
        }

        // 以加速度计的时间戳为准，合并一次数据
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            processNewDataPoint()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        Log.d(TAG, "GaitService destroying...")
        if (isCollectingData) {
            Log.w(TAG, "服务在采集中被销毁，强制停止。")
            stopAndRetrieveData()
        }
        releaseWakeLock()
        screenStateReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) { Log.w(TAG, "注销屏幕监听器失败", e) }
        }
        super.onDestroy()
        Log.d(TAG, "GaitService destroyed.")
    }

    private fun processNewDataPoint() {
        val newDataPoint = SensorDataPoint(
            timestamp = lastAccTimestamp,
            accX = lastAcc[0], accY = lastAcc[1], accZ = lastAcc[2],
            gyroX = lastGyro[0], gyroY = lastGyro[1], gyroZ = lastGyro[2],
            magX = lastMag[0], magY = lastMag[1], magZ = lastMag[2],
            pressure = lastPressure
        )

        // 写入文件
        try {
            if (!isFirstDataPointInFile) {
                dataFileWriter?.write(",")
            }
            dataFileWriter?.write(json.encodeToString(newDataPoint))
            isFirstDataPointInFile = false
        } catch (e: Exception) {
            Log.e(TAG, "写入数据点到文件失败", e)
            // 如果写入失败，可能磁盘满了，停止采集
            _serviceState.value = ServiceState.Error("写入文件失败")
            stopAndRetrieveData()
            return
        }

        totalPointsCollected++

        // 更新步行状态
        walkingDetectionBuffer.add(newDataPoint)
        if (walkingDetectionBuffer.size > WALK_DETECTION_WINDOW_SIZE) {
            walkingDetectionBuffer.removeAt(0)
        }
        if (totalPointsCollected % (WALK_DETECTION_WINDOW_SIZE / 2) == 0) {
            detectWalkingAndUpdateState()
        }

        // 更新通知
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationUpdateTime > NOTIFICATION_UPDATE_INTERVAL_MS) {
            val statusText = if(_isWalkingFlow.value) "正在步行中..." else "正在采集中..."
            updateNotification("$statusText (已采集 $totalPointsCollected 点)")
            lastNotificationUpdateTime = currentTime
        }
    }

    private fun registerSensors() {
        val samplingRate = SensorManager.SENSOR_DELAY_FASTEST
        val sensorsToRegister = listOfNotNull(accelerometer, gyroscope, magnetometer, pressureSensor)
        sensorsToRegister.forEach { sensor ->
            val success = sensorManager.registerListener(this, sensor, samplingRate, 20000) // 20ms batch report delay
            Log.i(TAG, "Registering ${sensor.name}: ${if (success) "Success" else "Failed"}")
        }
    }

    private fun detectWalkingAndUpdateState() {
        if (walkingDetectionBuffer.size < WALK_DETECTION_WINDOW_SIZE / 2) return

        val accMags = walkingDetectionBuffer.map { sqrt(it.accX.pow(2) + it.accY.pow(2) + it.accZ.pow(2)).toDouble() }
        val variance = Variance().evaluate(accMags.toDoubleArray())
        val isCurrentlyWalking = variance > WALK_VARIANCE_THRESHOLD

        if (_isWalkingFlow.value != isCurrentlyWalking) {
            _isWalkingFlow.value = isCurrentlyWalking
        }

        _serviceState.value = if (isCurrentlyWalking) {
            ServiceState.Walking(totalPointsCollected)
        } else {
            ServiceState.Collecting(totalPointsCollected)
        }
    }

    private fun resetCollectionState() {
        isCollectingData = false
        totalPointsCollected = 0
        walkingDetectionBuffer.clear()
        _isWalkingFlow.value = false
        _serviceState.value = ServiceState.Idle
        lastNotificationUpdateTime = 0L
    }

    private fun setupPowerManagement() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorWalk::DataCollectionWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
                Log.i(TAG, "唤醒锁 (WakeLock) 已释放。")
            } catch (e: Exception) {
                Log.e(TAG, "释放唤醒锁失败", e)
            }
        }
    }

    private fun setupScreenStateListener() {
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> Log.i(TAG, "屏幕关闭，服务将依靠WakeLock和前台服务持续运行。")
                    Intent.ACTION_SCREEN_ON -> Log.i(TAG, "屏幕开启。")
                }
            }
        }
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, intentFilter)
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(SERVICE_ID, createNotification(text))
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("步态分析服务")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "GaitService"
        const val SERVICE_ID = 1
        const val NOTIFICATION_CHANNEL_ID = "GAIT_SERVICE_CHANNEL"
    }
}
