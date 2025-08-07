package com.example.sensorwalk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.sensorwalk.service.GaitService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SensorWalkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "步态分析服务"
            val descriptionText = "用于在后台采集和分析步态数据"
            val importance = NotificationManager.IMPORTANCE_LOW // 设置为LOW，避免声音提示
            val channel = NotificationChannel(GaitService.NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
