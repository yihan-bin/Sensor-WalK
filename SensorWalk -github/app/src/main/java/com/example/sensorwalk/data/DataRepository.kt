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

    // --- 新增 ---
    suspend fun deleteResult(id: Long) {
        analysisResultDao.deleteById(id)
    }

    suspend fun clearAllResults() {
        analysisResultDao.deleteAll()
    }
}
