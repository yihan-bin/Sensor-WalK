package com.example.sensorwalk.service

import android.app.Notification
// **核心修复：添加缺失的 NotificationManager 导入**
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.sensorwalk.R
import com.example.sensorwalk.data.SensorDataPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.commons.math3.stat.descriptive.moment.Variance
import kotlin.math.sqrt

class GaitService : Service(), SensorEventListener {

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _isWalking = MutableStateFlow(false)
    val isWalking = _isWalking.asStateFlow()

    private val collectedData = mutableListOf<SensorDataPoint>()

    private val accMagnitudeBuffer = mutableListOf<Double>()
    private val bufferSize = 100
    private val activityVarianceThreshold = 0.5

    private val lastAcc = FloatArray(3)
    private val lastGyro = FloatArray(3)
    private val lastMag = FloatArray(3)
    private var lastPressure: Float = 0f
    private var lastAccTimestamp: Long = 0

    inner class LocalBinder : Binder() {
        fun getService(): GaitService = this@GaitService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(SERVICE_ID, createNotification("准备开始分析..."))
        startRecording()
        return START_NOT_STICKY
    }

    private fun startRecording() {
        collectedData.clear()
        accMagnitudeBuffer.clear()
        val samplingPeriod = SensorManager.SENSOR_DELAY_FASTEST
        val accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        sensorManager.registerListener(this, accSensor, samplingPeriod)
        sensorManager.registerListener(this, gyroSensor, samplingPeriod)
        sensorManager.registerListener(this, magSensor, samplingPeriod)
        pressureSensor?.let {
            sensorManager.registerListener(this, it, samplingPeriod)
        }
        _isRecording.value = true
    }

    fun stopRecordingAndGetData(): List<SensorDataPoint> {
        sensorManager.unregisterListener(this)
        _isRecording.value = false
        _isWalking.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return ArrayList(collectedData)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !_isRecording.value) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAcc, 0, 3)
                lastAccTimestamp = event.timestamp
                checkWalkingState(lastAcc)
            }
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, lastGyro, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, lastMag, 0, 3)
            Sensor.TYPE_PRESSURE -> lastPressure = event.values[0]
        }

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && _isWalking.value) {
            collectedData.add(
                SensorDataPoint(
                    timestamp = lastAccTimestamp,
                    accX = lastAcc[0], accY = lastAcc[1], accZ = lastAcc[2],
                    gyroX = lastGyro[0], gyroY = lastGyro[1], gyroZ = lastGyro[2],
                    magX = lastMag[0], magY = lastMag[1], magZ = lastMag[2],
                    pressure = lastPressure
                )
            )
        }
    }

    private fun checkWalkingState(acc: FloatArray) {
        val magnitude = sqrt((acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2]).toDouble())
        accMagnitudeBuffer.add(magnitude)
        if (accMagnitudeBuffer.size > bufferSize) {
            accMagnitudeBuffer.removeAt(0)
        }

        if (accMagnitudeBuffer.size >= bufferSize) {
            val variance = Variance().evaluate(accMagnitudeBuffer.toDoubleArray())
            val currentlyWalking = variance > activityVarianceThreshold
            if (_isWalking.value != currentlyWalking) {
                _isWalking.value = currentlyWalking
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        val text = if (_isWalking.value) "检测到走路，正在记录..." else "已暂停，请开始走路..."
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(SERVICE_ID, createNotification(text))
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("步态分析服务运行中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        if (_isRecording.value) {
            sensorManager.unregisterListener(this)
        }
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val SERVICE_ID = 1
        const val NOTIFICATION_CHANNEL_ID = "GAIT_SERVICE_CHANNEL"
    }
}
