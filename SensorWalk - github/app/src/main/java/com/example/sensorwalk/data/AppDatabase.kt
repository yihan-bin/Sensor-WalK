// 文件: app/src/main/java/com/example/sensorwalk/data/AppDatabase.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 数据库迁移(需求4.8): 数据库版本从 6 升级到 7，并新增了 MIGRATION_6_7 迁移逻辑，为`analysis_results`表添加文件路径列。
 * 2025-07-30 - Gemini-AI - 升级数据库版本并添加迁移逻辑。为支持`AnalysisResult`中的新文件路径字段（需求2, 4.8），将数据库版本`version`从6提升至7。新增`MIGRATION_6_7`，通过`ALTER TABLE`语句向`analysis_results`表无损添加新列，确保老用户更新App后数据不丢失。
 * 2025-07-30 - Gemini-AI - [V3] 数据库版本升级至8并添加迁移逻辑以修复崩溃。新增 MIGRATION_7_8，该迁移会复制出一个不包含崩溃字段（localRawDataJson, remoteRawDataJson）的新表，并将数据迁移过去，然后用新表替换旧表。同时添加了新的 chart data 路径列，以完成数据外置化改造。
 */

package com.example.sensorwalk.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AnalysisResult::class],
    version = 8, // ★★★ 核心修改：数据库版本升级到 8 ★★★
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun analysisResultDao(): AnalysisResultDao

    companion object {
        // 保留之前的迁移逻辑
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                 db.execSQL("CREATE TABLE analysis_results_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, durationSeconds INTEGER NOT NULL, mode TEXT NOT NULL, totalSteps INTEGER NOT NULL, overallScore REAL NOT NULL, localMetricsJson TEXT NOT NULL, remoteMetricsJson TEXT NOT NULL, comparisonMetricsJson TEXT NOT NULL, dataSummaryJson TEXT NOT NULL, localLegSide TEXT NOT NULL, remoteLegSide TEXT NOT NULL)")
                try {
                    db.execSQL("INSERT INTO analysis_results_new (id, timestamp, durationSeconds, mode, totalSteps, overallScore, localMetricsJson, remoteMetricsJson, comparisonMetricsJson, dataSummaryJson, localLegSide, remoteLegSide) SELECT id, timestamp, durationSeconds, mode, totalSteps, overallScore, localMetricsJson, COALESCE(remoteMetricsJson, '{}'), COALESCE(comparisonMetricsJson, '{}'), '{}', localLegSide, COALESCE(remoteLegSide, '') FROM analysis_results")
                } catch (e: Exception) {
                     Log.e("DB_MIGRATION_4_5", "Failed to copy data during migration 4->5.", e)
                }
                db.execSQL("DROP TABLE analysis_results")
                db.execSQL("ALTER TABLE analysis_results_new RENAME TO analysis_results")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_results ADD COLUMN localRawDataJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE analysis_results ADD COLUMN remoteRawDataJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_results ADD COLUMN localDataFilePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE analysis_results ADD COLUMN remoteDataFilePath TEXT NOT NULL DEFAULT ''")
            }
        }

        // ★★★ 核心修改：新增从版本 7 到 8 的迁移，移除大JSON列 ★★★
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i("DB_MIGRATION", "Starting migration from version 7 to 8 to externalize raw data.")
                // 1. 创建一个符合新 schema 的临时表
                db.execSQL("""
                    CREATE TABLE analysis_results_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        durationSeconds INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        totalSteps INTEGER NOT NULL,
                        overallScore REAL NOT NULL,
                        localMetricsJson TEXT NOT NULL,
                        remoteMetricsJson TEXT NOT NULL,
                        comparisonMetricsJson TEXT NOT NULL,
                        localDataFilePath TEXT NOT NULL,
                        remoteDataFilePath TEXT NOT NULL,
                        localChartDataPath TEXT NOT NULL DEFAULT '',
                        remoteChartDataPath TEXT NOT NULL DEFAULT '',
                        dataSummaryJson TEXT NOT NULL,
                        localLegSide TEXT NOT NULL,
                        remoteLegSide TEXT NOT NULL
                    )
                """)

                // 2. 将旧表中的数据复制到新表，忽略被删除的列
                db.execSQL("""
                    INSERT INTO analysis_results_new (
                        id, timestamp, durationSeconds, mode, totalSteps, overallScore,
                        localMetricsJson, remoteMetricsJson, comparisonMetricsJson,
                        localDataFilePath, remoteDataFilePath,
                        dataSummaryJson, localLegSide, remoteLegSide
                    )
                    SELECT
                        id, timestamp, durationSeconds, mode, totalSteps, overallScore,
                        localMetricsJson, remoteMetricsJson, comparisonMetricsJson,
                        localDataFilePath, remoteDataFilePath,
                        dataSummaryJson, localLegSide, remoteLegSide
                    FROM analysis_results
                """)

                // 3. 删除旧表
                db.execSQL("DROP TABLE analysis_results")

                // 4. 将新表重命名为原始表名
                db.execSQL("ALTER TABLE analysis_results_new RENAME TO analysis_results")
                Log.i("DB_MIGRATION", "Migration to version 8 completed successfully.")
            }
        }


        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gait_analysis_db"
                )
                    // ★★★ 核心修改：添加新的迁移逻辑 ★★★
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    // 保留 fallback 作为最终保险
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
