package com.example.sensorwalk.di

import android.content.Context
import androidx.room.Room
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
        return Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "gait_analysis_db"
        )
            // ★★★ 核心修复: 添加破坏性迁移，以匹配 AppDatabase 中的版本升级 ★★★
            .fallbackToDestructiveMigration()
            .build()
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
