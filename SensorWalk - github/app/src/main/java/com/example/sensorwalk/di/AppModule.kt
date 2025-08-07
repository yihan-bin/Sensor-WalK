// 文件: app/src/main/java/com/example/sensorwalk/di/AppModule.kt
package com.example.sensorwalk.di

import android.content.Context
import com.example.sensorwalk.connectivity.ConnectionManager
import com.example.sensorwalk.data.AnalysisResultDao
import com.example.sensorwalk.data.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext appContext: Context): AppDatabase {
        // ★★★ 核心修改：使用新的单例模式获取数据库实例 ★★★
        return AppDatabase.getDatabase(appContext)
    }

    @Provides
    fun provideAnalysisResultDao(appDatabase: AppDatabase): AnalysisResultDao {
        return appDatabase.analysisResultDao()
    }

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideConnectionManager(
        @ApplicationContext context: Context,
        coroutineScope: CoroutineScope
    ): ConnectionManager {
        return ConnectionManager(context, coroutineScope)
    }
}
