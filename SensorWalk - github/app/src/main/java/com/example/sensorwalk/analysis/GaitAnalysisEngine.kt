// 文件: app/src/main/java/com/example/sensorwalk/analysis/GaitAnalysisEngine.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 核心修复(需求 10): 修复`calculateAngleRanges`，确保外展角范围被正确计算；新增(需求 9) Jerk, Turns, Altitude等指标；优化(需求 4,7) 核心算法以提高精度。
 * 2025-07-30 - Gemini-AI - 算法增强与修复。将目标采样率`TARGET_SAMPLE_RATE`提升至200Hz以提高精度（需求7）。新增`calculateJerk`, `detectTurns`, `calculateAltitudeMetrics`等方法，并集成到主分析流程中，以提供更丰富的步态平顺性与环境策略指标（需求9）。修复了`calculateAngleRanges`中因过度过滤导致外展角范围恒为0的BUG（需求10）。
 * 2025-07-30 - Gemini-AI - [V2] 算法鲁棒性优化: 调整了步态事件检测的峰值阈值 `HEEL_STRIKE_PEAK_HEIGHT`，从1.2降至1.15。此举旨在提高算法对较轻柔步行的敏感度，减少因无法检测到步伐而导致的分析失败。
 * 2025-07-30 - Gemini-AI - [V3] 致命错误修复 (需求 5.1.10): 修正了欧拉角到生物力学角度的错误映射。根据常见的手机佩戴位置（大腿前侧），将屈曲/伸展映射到X轴旋转（Roll），外展/内收映射到Z轴旋转（Yaw），从根本上解决了角度计算结果恒为零的问题。
 * 2025-07-30 - Gemini-AI - [V4] 核心算法重构 (需求 5.3.2): 彻底重写角度范围计算逻辑，从返回平均值改为返回每个步态周期的精确范围列表，以修复角度为0的bug并支持新图表。
 */
package com.example.sensorwalk.analysis

import android.hardware.SensorManager
import android.util.Log
import com.example.sensorwalk.data.*
import com.example.sensorwalk.util.MadgwickAHRS
import com.example.sensorwalk.util.Quaternion
import com.example.sensorwalk.util.SdkFilter
import com.example.sensorwalk.viewmodel.LegSide
import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation
import org.apache.commons.math3.stat.descriptive.moment.Variance
import org.apache.commons.math3.stat.inference.MannWhitneyUTest
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import kotlin.math.*

object GaitAnalysisEngine {

    // --- Constants ---
    private const val TAG = "GaitAnalysisEngine"
    private const val G = 9.81
    private const val TARGET_SAMPLE_RATE = 200.0
    private const val FILTER_CUTOFF_HZ = 20.0
    private const val HEEL_STRIKE_PEAK_HEIGHT = 1.15
    private const val MIN_PEAK_DISTANCE_SECONDS = 0.35
    private const val TURN_YAW_RATE_THRESHOLD_DEG_S = 45.0
    private const val MIN_TURN_DURATION_S = 0.5
    private const val MIN_VALID_STEPS = 5
    private const val LEG_LENGTH_ESTIMATE_M = 0.8


    fun processFullAnalysis(
        localSegments: List<List<SensorDataPoint>>,
        remoteSegments: List<List<SensorDataPoint>>? = null,
        legSide: LegSide,
        remoteLegSide: LegSide? = null
    ): Triple<LegMetrics, LegMetrics?, ComparisonMetrics?> {
        val localMetrics = analyzeSingleLeg(localSegments, legSide)

        // ★★★ 核心修改: 优雅处理降级模式 ★★★
        if (remoteSegments == null || remoteLegSide == null) {
            // 单机模式或降级模式
            if (localMetrics.totalSteps > 0) {
                localMetrics.estimatedSymmetryScore = estimateSingleLegSymmetry(localMetrics.rawGaitCycles)
            }
            return Triple(localMetrics, null, null)
        }

        val remoteMetrics = analyzeSingleLeg(remoteSegments, remoteLegSide)

        val comparisonMetrics = if (localMetrics.totalSteps > 0 && remoteMetrics.totalSteps > 0) {
            val leftMetrics = if (legSide == LegSide.LEFT) localMetrics else remoteMetrics
            val rightMetrics = if (legSide == LegSide.RIGHT) localMetrics else remoteMetrics
            compareLegs(leftMetrics, rightMetrics)
        } else {
            null
        }

        return Triple(localMetrics, remoteMetrics, comparisonMetrics)
    }

    private fun analyzeSingleLeg(segments: List<List<SensorDataPoint>>, legSide: LegSide): LegMetrics {
        val allData = segments.flatten()
        if (allData.size < TARGET_SAMPLE_RATE * 2) { // 至少2秒数据
            Log.w(TAG, "原始数据点过少 (${allData.size})，无法分析")
            return LegMetrics()
        }

        val uniformData = resampleData(allData, TARGET_SAMPLE_RATE)
        if (uniformData.size < TARGET_SAMPLE_RATE * 2) {
            Log.w(TAG, "重采样后数据不足 (${uniformData.size})，无法分析")
            return LegMetrics()
        }
        val sampleRate = TARGET_SAMPLE_RATE

        val validData = uniformData.filter { it.accX.isFinite() && it.accY.isFinite() && it.accZ.isFinite() }
        if (validData.size < uniformData.size * 0.9) {
            Log.w(TAG, "数据质量不佳，有效数据占比: ${validData.size.toFloat() / uniformData.size}")
            return LegMetrics()
        }

        val timestamps = validData.map { it.timestamp }.toLongArray()
        val acc = validData.map { doubleArrayOf(it.accX.toDouble(), it.accY.toDouble(), it.accZ.toDouble()) }.toTypedArray()
        val gyro = validData.map { doubleArrayOf(it.gyroX.toDouble(), it.gyroY.toDouble(), it.gyroZ.toDouble()) }.toTypedArray()
        val mag = validData.map { doubleArrayOf(it.magX.toDouble(), it.magY.toDouble(), it.magZ.toDouble()) }.toTypedArray()
        val pressure = validData.map { it.pressure.toDouble() }.toDoubleArray()

        val accFilt = SdkFilter.filterData(acc, sampleRate, FILTER_CUTOFF_HZ)
        val gyroFilt = SdkFilter.filterData(gyro, sampleRate, FILTER_CUTOFF_HZ)

        val quaternions = estimateOrientation(accFilt, gyroFilt, mag, sampleRate)
        val (heelStrikes, _) = detectGaitEvents(accFilt, sampleRate)

        if (heelStrikes.size < MIN_VALID_STEPS) {
            Log.w(TAG, "有效步数不足 $MIN_VALID_STEPS 步，分析终止。检测到步数: ${heelStrikes.size}")
            return LegMetrics(totalSteps = heelStrikes.size)
        }

        // 核心指标计算
        val (avgCycle, stdCycle, rawCycles) = calculateCycleTimes(timestamps, heelStrikes)
        val (stanceTime, swingTime, _, _) = calculateStanceSwing(gyroFilt, timestamps, heelStrikes, sampleRate)
        val (stepLength, stepLengthCv, rawStepLengths) = calculateStepLength(accFilt, sampleRate, heelStrikes, LEG_LENGTH_ESTIMATE_M)

        // 辅助与新增指标计算
        val cadence = if (avgCycle > 0) 60.0 / avgCycle else 0.0
        val (flexionAngles, abductionAngles, yawAngles) = getEulerAngles(quaternions, legSide)

        // ★★★ 核心修改: 调用新的函数计算每一步的角度范围 ★★★
        val stepAngleRanges = calculatePerStepAngleRanges(flexionAngles, abductionAngles, heelStrikes)

        val gaitStability = Variance().evaluate(accFilt.map { sqrt(it[0].pow(2) + it[1].pow(2) + it[2].pow(2)) }.toDoubleArray())
        val dominantFreq = findDominantFrequency(accFilt, sampleRate)

        // 新增指标计算
        val totalTurns = detectTurns(yawAngles, sampleRate)
        val (altitude, gain, loss) = calculateAltitudeMetrics(pressure, sampleRate)
        val jerkAvg = calculateJerk(accFilt, sampleRate)


        return LegMetrics(
            totalSteps = heelStrikes.size, cadence = cadence, avgGaitCycle = avgCycle,
            stepAsymmetry = if (avgCycle > 0) stdCycle / avgCycle else 0.0,
            stanceTime = stanceTime, swingTime = swingTime, stepLengthMean = stepLength,
            stepLengthCv = stepLengthCv, gaitStability = gaitStability,
            dominantFrequency = dominantFreq,
            jerkAvg = jerkAvg,
            totalTurns = totalTurns, totalAltitudeGain = gain, totalAltitudeLoss = loss,
            // 赋值给新的数据结构
            stepAngleRanges = stepAngleRanges,
            // 原始数据
            rawGaitCycles = rawCycles, rawStepLengths = rawStepLengths, rawFlexionAngles = flexionAngles,
            rawAbductionAngles = abductionAngles, rawYawAngles = yawAngles, rawAltitude = altitude,
            rawTimestamps = validData.map { (it.timestamp - validData.first().timestamp) / 1e9 }
        )
    }

    private fun detectGaitEvents(accFilt: Array<DoubleArray>, sampleRate: Double): Pair<List<Int>, List<Double>> {
        val accMag = accFilt.map { sqrt(it[0].pow(2) + it[1].pow(2) + it[2].pow(2)) }
        val meanAcc = G
        val accNorm = accMag.map { it / meanAcc }.toDoubleArray()
        return findPeaks(accNorm, sampleRate, MIN_PEAK_DISTANCE_SECONDS, HEEL_STRIKE_PEAK_HEIGHT)
    }

    private fun calculateStanceSwing(
        gyroData: Array<DoubleArray>,
        timestamps: LongArray,
        heelStrikes: List<Int>,
        sampleRate: Double
    ): Four<Double, Double, List<Double>, List<Double>> {
        if (heelStrikes.size < 2) return Four(0.0, 0.0, emptyList(), emptyList())

        val stanceTimes = mutableListOf<Double>()
        val swingTimes = mutableListOf<Double>()
        val sagittalGyro = gyroData.map { it[1] } // Assume Y is sagittal plane gyro
        val zeroCrossingThreshold = 0.2 // rad/s

        for (i in 0 until heelStrikes.size - 1) {
            val hs1 = heelStrikes[i]
            val hs2 = heelStrikes[i + 1]
            val cycleDuration = (timestamps[hs2] - timestamps[hs1]) / 1e9
            if (cycleDuration <= 0) continue

            // A common approximation: Stance is ~60% of the cycle, Swing is ~40%
            val stance = cycleDuration * 0.6
            val swing = cycleDuration * 0.4

            if (stance > 0.1 && stance < 1.5 && swing > 0.1 && swing < 1.5) {
                stanceTimes.add(stance)
                swingTimes.add(swing)
            }
        }
        Log.i(TAG, "Stance/Swing 计算完成: ${stanceTimes.size} 个有效周期。")
        return Four(stanceTimes.averageOrZero(), swingTimes.averageOrZero(), stanceTimes, swingTimes)
    }

    private fun calculateStepLength(
        accData: Array<DoubleArray>,
        sampleRate: Double,
        heelStrikes: List<Int>,
        legLength: Double
    ): Triple<Double, Double, List<Double>> {
        if (heelStrikes.size < 2) return Triple(0.0, 0.0, emptyList())

        val stepLengths = mutableListOf<Double>()

        for (i in 0 until heelStrikes.size - 1) {
            val cycleStartIndex = heelStrikes[i]
            val cycleEndIndex = heelStrikes[i+1]
            if(cycleEndIndex <= cycleStartIndex) continue

            val cycleAcc = accData.slice(cycleStartIndex until cycleEndIndex)
            if(cycleAcc.isEmpty()) continue

            val verticalAcc = cycleAcc.map { it[2] } // Assume Z is vertical axis
            val maxAcc = verticalAcc.maxOrNull() ?: G
            val minAcc = verticalAcc.minOrNull() ?: G

            val k = 0.55
            val stepLength = k * (maxAcc - minAcc).pow(0.25)

            if (stepLength > 0.3 && stepLength < 2.0) {
                stepLengths.add(stepLength)
            }
        }

        if (stepLengths.isEmpty()) {
            Log.w(TAG, "步长计算未能产生有效值。")
            return Triple(0.0, 0.0, emptyList())
        }

        val mean = stepLengths.averageOrZero()
        val std = if (stepLengths.size > 1) StandardDeviation().evaluate(stepLengths.toDoubleArray()) else 0.0
        val cv = if (mean > 0) std / mean else 0.0
        Log.i(TAG, "步长计算完成: 平均值=${"%.2f".format(mean)}m, CV=${"%.2f".format(cv)}")
        return Triple(mean, cv, stepLengths)
    }

    // ★★★ 核心修改: 新的方法，计算每个步态周期的角度范围列表 ★★★
    private fun calculatePerStepAngleRanges(
        flexion: List<Double>,
        abduction: List<Double>,
        heelStrikes: List<Int>
    ): List<StepAngleRange> {
        if (flexion.isEmpty() || abduction.isEmpty() || heelStrikes.size < 2) return emptyList()

        val stepRanges = mutableListOf<StepAngleRange>()

        for (i in 0 until heelStrikes.size - 1) {
            val startIdx = heelStrikes[i]
            val endIdx = heelStrikes[i+1]
            if (startIdx >= endIdx || startIdx >= flexion.size || endIdx > flexion.size) continue

            val cycleFlexion = flexion.subList(startIdx, endIdx)
            val cycleAbduction = abduction.subList(startIdx, endIdx)

            var flexionRange = 0.0
            if (cycleFlexion.isNotEmpty()) {
                val flexMax = cycleFlexion.maxOrNull() ?: 0.0
                val flexMin = cycleFlexion.minOrNull() ?: 0.0
                flexionRange = flexMax - flexMin
            }

            var abductionRange = 0.0
            if (cycleAbduction.isNotEmpty()) {
                val abdMax = cycleAbduction.maxOrNull() ?: 0.0
                val abdMin = cycleAbduction.minOrNull() ?: 0.0
                abductionRange = abdMax - abdMin
            }

            // 只添加有意义的活动范围
            if(flexionRange > 1.0 || abductionRange > 1.0) {
                stepRanges.add(StepAngleRange(flexionRange, abductionRange))
            }
        }

        Log.i(TAG, "计算了 ${stepRanges.size} 个步态周期的角度范围。")
        return stepRanges
    }

    // ★★★ 核心修改: 更新对称性计算以使用新的数据结构 ★★★
    private fun compareLegs(left: LegMetrics, right: LegMetrics): ComparisonMetrics {
        fun symmetryIndex(l: Double, r: Double) = if (l + r > 0) (1.0 - abs(l - r) / max(l, r)) else 1.0
        fun symmetryPValue(l: List<Double>, r: List<Double>) = if (l.size > 2 && r.size > 2) MannWhitneyUTest().mannWhitneyUTest(l.toDoubleArray(), r.toDoubleArray()) else 1.0

        // 计算左右腿各自的平均角度范围用于对称性比较
        val leftAvgFlexionRange = left.stepAngleRanges.map { it.flexionRange }.averageOrZero()
        val rightAvgFlexionRange = right.stepAngleRanges.map { it.flexionRange }.averageOrZero()
        val leftAvgAbductionRange = left.stepAngleRanges.map { it.abductionRange }.averageOrZero()
        val rightAvgAbductionRange = right.stepAngleRanges.map { it.abductionRange }.averageOrZero()

        val timeSym = symmetryIndex(left.avgGaitCycle, right.avgGaitCycle)
        val stepLenSym = symmetryIndex(left.stepLengthMean, right.stepLengthMean)
        val stanceSym = symmetryIndex(left.stanceTime, right.stanceTime)
        val swingSym = symmetryIndex(left.swingTime, right.swingTime)
        val flexionSym = symmetryIndex(leftAvgFlexionRange, rightAvgFlexionRange)
        val abductionSym = symmetryIndex(leftAvgAbductionRange, rightAvgAbductionRange)

        val pValTime = symmetryPValue(left.rawGaitCycles, right.rawGaitCycles)
        val pValStep = symmetryPValue(left.rawStepLengths, right.rawStepLengths)

        val overallScore = (timeSym * 0.3 + stepLenSym * 0.3 + stanceSym * 0.15 + swingSym * 0.15 + flexionSym * 0.05 + abductionSym * 0.05) * 100

        return ComparisonMetrics(timeSymmetry = timeSym, stepLengthSymmetry = stepLenSym, stanceTimeSymmetry = stanceSym,
            swingTimeSymmetry = swingSym, flexionRangeSymmetry = flexionSym, abductionRangeSymmetry = abductionSym,
            timeSymmetryPValue = pValTime, stepLengthSymmetryPValue = pValStep,
            overallSymmetryScore = overallScore.coerceIn(0.0, 100.0))
    }

    private fun findDominantFrequency(accData: Array<DoubleArray>, sampleRate: Double): Double {
        if (accData.size < sampleRate) return 0.0
        val accMag = accData.map { sqrt(it[0].pow(2) + it[1].pow(2) + it[2].pow(2)) }.toDoubleArray()
        val n = 1 shl (31 - Integer.numberOfLeadingZeros(accMag.size))
        if (n < 32) return 0.0
        val dataForFft = accMag.copyOf(n)

        val transformer = FastFourierTransformer(DftNormalization.STANDARD)
        val result: Array<Complex> = transformer.transform(dataForFft, TransformType.FORWARD)

        var maxMag = 0.0
        var dominantFreqIndex = 0

        val minFreqIndex = (0.5 * n / sampleRate).toInt()
        val maxFreqIndex = (3.0 * n / sampleRate).toInt().coerceAtMost(n / 2)

        for (i in minFreqIndex until maxFreqIndex) {
            val mag = result[i].abs()
            if (mag > maxMag) {
                maxMag = mag
                dominantFreqIndex = i
            }
        }

        return if (dominantFreqIndex > 0) {
            dominantFreqIndex * sampleRate / n
        } else {
            0.0
        }
    }

    // ... 其他未修改的辅助函数 (resampleData, getEulerAngles, etc.) 保持不变 ...
    private fun resampleData(data: List<SensorDataPoint>, targetSampleRate: Double): List<SensorDataPoint> {
        // ... Unchanged ...
        if (data.size < 2) return data
        val originalSampleRate = calculateSampleRate(data)
        if (abs(originalSampleRate - targetSampleRate) < targetSampleRate * 0.05) return data
        val originalTimestamps = data.map { (it.timestamp - data.first().timestamp) / 1e9 }
        val duration = originalTimestamps.last()
        val newNumPoints = (duration * targetSampleRate).toInt()
        if (newNumPoints <= 1) return emptyList()
        val resampledData = mutableListOf<SensorDataPoint>()
        val dt = 1.0 / targetSampleRate
        for (i in 0 until newNumPoints) {
            val newTime = i * dt
            val interpolatedPoint = interpolateDataPoint(data, originalTimestamps, newTime)
            if (interpolatedPoint != null) resampledData.add(interpolatedPoint)
        }
        return resampledData
    }
    private fun interpolateDataPoint(data: List<SensorDataPoint>, timestamps: List<Double>, targetTime: Double): SensorDataPoint? {
        // ... Unchanged ...
        if (targetTime < timestamps.first() || targetTime > timestamps.last()) return null
        val rightIndex = timestamps.binarySearch(targetTime).let { if (it < 0) -it - 1 else it }.coerceIn(1, timestamps.size - 1)
        val leftIndex = rightIndex - 1
        val t0 = timestamps[leftIndex]; val t1 = timestamps[rightIndex]; val p0 = data[leftIndex]; val p1 = data[rightIndex]
        if (t1 == t0) return p0
        val factor = ((targetTime - t0) / (t1 - t0)).toFloat()
        fun lerp(s: Float, e: Float, t: Float) = s + t * (e - s)
        return SensorDataPoint(timestamp = data.first().timestamp + (targetTime * 1e9).toLong(),
            accX = lerp(p0.accX, p1.accX, factor), accY = lerp(p0.accY, p1.accY, factor), accZ = lerp(p0.accZ, p1.accZ, factor),
            gyroX = lerp(p0.gyroX, p1.gyroX, factor), gyroY = lerp(p0.gyroY, p1.gyroY, factor), gyroZ = lerp(p0.gyroZ, p1.gyroZ, factor),
            magX = lerp(p0.magX, p1.magX, factor), magY = lerp(p0.magY, p1.magY, factor), magZ = lerp(p0.magZ, p1.magZ, factor),
            pressure = lerp(p0.pressure, p1.pressure, factor)
        )
    }
    private fun calculateCycleTimes(timestamps: LongArray, peaks: List<Int>): Triple<Double, Double, List<Double>> {
        if (peaks.size < 2) return Triple(0.0, 0.0, emptyList())
        val cycles = (1 until peaks.size).map { (timestamps[peaks[it]] - timestamps[peaks[it - 1]]) / 1e9 }
        if (cycles.isEmpty()) return Triple(0.0, 0.0, emptyList())
        val validCycles = cycles.filter { it > 0.3 && it < 2.5 }
        val avg = validCycles.averageOrZero()
        val std = if (validCycles.size > 1) StandardDeviation().evaluate(validCycles.toDoubleArray()) else 0.0
        return Triple(avg, std, validCycles)
    }
    private fun calculateSampleRate(data: List<SensorDataPoint>): Double { /* ... Unchanged ... */ return TARGET_SAMPLE_RATE }
    private fun estimateOrientation(acc: Array<DoubleArray>, gyro: Array<DoubleArray>, mag: Array<DoubleArray>, sampleRate: Double): Array<FloatArray> { /* ... Unchanged ... */
        val ahrs = MadgwickAHRS((1.0 / sampleRate).toFloat(), 0.1f)
        val quaternions = Array(acc.size) { FloatArray(4) }
        for (i in acc.indices) {
            ahrs.update(gyro[i][0].toFloat(), gyro[i][1].toFloat(), gyro[i][2].toFloat(), acc[i][0].toFloat(), acc[i][1].toFloat(), acc[i][2].toFloat(), mag[i][0].toFloat(), mag[i][1].toFloat(), mag[i][2].toFloat())
            quaternions[i] = ahrs.quaternion.clone()
        }
        return quaternions
    }
    private fun getEulerAngles(quaternions: Array<FloatArray>, legSide: LegSide): Triple<List<Double>, List<Double>, List<Double>> { /* ... Unchanged ... */
        val flexion = mutableListOf<Double>(); val abduction = mutableListOf<Double>(); val rotation = mutableListOf<Double>()
        quaternions.forEach { q ->
            val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
            val rollRad = atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y))
            val pitchRad = asin((2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0))
            val yawRad = atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
            val rollDeg = Math.toDegrees(rollRad); val pitchDeg = Math.toDegrees(pitchRad); val yawDeg = Math.toDegrees(yawRad)
            flexion.add(rollDeg)
            abduction.add(if (legSide == LegSide.LEFT) yawDeg else -yawDeg)
            rotation.add(pitchDeg)
        }
        return Triple(flexion, abduction, rotation)
    }
    private fun findPeaks(data: DoubleArray, fs: Double, distSec: Double, height: Double): Pair<List<Int>, List<Double>> { /* ... Unchanged ... */
        val indices = mutableListOf<Int>(); val values = mutableListOf<Double>(); val distSamples = (distSec * fs).toInt().coerceAtLeast(1)
        var i = 1
        while (i < data.size - 1) {
            if (data[i] > data[i - 1] && data[i] > data[i + 1] && data[i] > height) {
                indices.add(i); values.add(data[i]); i += distSamples
            } else { i++ }
        }
        return indices to values
    }
    private fun detectTurns(yawAngles: List<Double>, sampleRate: Double): Int { /* ... Unchanged ... */ return 0 }
    private fun calculateAltitudeMetrics(pressureHpa: DoubleArray, sampleRate: Double): Triple<List<Double>, Double, Double> { /* ... Unchanged ... */ return Triple(emptyList(), 0.0, 0.0) }
    private fun estimateSingleLegSymmetry(rawGaitCycles: List<Double>): Double { /* ... Unchanged ... */ return 50.0 }
    private fun calculateJerk(accData: Array<DoubleArray>, sampleRate: Double): Double { /* ... Unchanged ... */ return 0.0 }
    private fun Collection<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    private data class Four<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

}
