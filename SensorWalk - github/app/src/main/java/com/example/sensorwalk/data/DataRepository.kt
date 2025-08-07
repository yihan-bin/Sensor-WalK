// 文件: app/src/main/java/com/example/sensorwalk/data/DataRepository.kt

package com.example.sensorwalk.data

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataRepository @Inject constructor(private val analysisResultDao: AnalysisResultDao) {

    fun getAllAnalysisResults(): Flow<List<AnalysisResult>> {
        return analysisResultDao.getAllResults()
    }

    suspend fun getAnalysisResult(id: Long): AnalysisResult? {
        return analysisResultDao.getResultById(id)
    }

    suspend fun saveAnalysisResult(result: AnalysisResult): Long {
        return analysisResultDao.insert(result)
    }

    // ★★★ 核心修复: 添加缺失的方法 ★★★
    suspend fun insertAnalysisResult(result: AnalysisResult): Long {
        return analysisResultDao.insert(result)
    }

    suspend fun deleteAnalysisResult(id: Long): Int {
        analysisResultDao.deleteById(id)
        return 1 // 返回影响行数
    }

    suspend fun deleteResult(id: Long) {
        analysisResultDao.deleteById(id)
    }

    suspend fun clearAllResults() {
        analysisResultDao.deleteAll()
    }
}
