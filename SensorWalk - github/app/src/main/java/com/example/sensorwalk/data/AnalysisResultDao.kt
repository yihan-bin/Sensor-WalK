package com.example.sensorwalk.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: AnalysisResult): Long

    @Query("SELECT * FROM analysis_results ORDER BY timestamp DESC")
    fun getAllResults(): Flow<List<AnalysisResult>>

    @Query("SELECT * FROM analysis_results WHERE id = :id")
    suspend fun getResultById(id: Long): AnalysisResult?

    // --- 新增 ---
    @Query("DELETE FROM analysis_results WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM analysis_results")
    suspend fun deleteAll()
}
