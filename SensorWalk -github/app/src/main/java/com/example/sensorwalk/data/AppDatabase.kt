package com.example.sensorwalk.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// ★★★ 核心修复: 严格遵循 #16 要求，不使用自动迁移，而是采用破坏性迁移 ★★★
@Database(
    entities = [AnalysisResult::class],
    version = 2, // <-- 修改: 版本必须升到 2，因为表结构变了
    exportSchema = false // <-- 修改: 设为 false，这样就不需要 schema 文件和插件了
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun analysisResultDao(): AnalysisResultDao
}
