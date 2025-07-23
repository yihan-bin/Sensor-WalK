// 文件: app/src/main/java/com/example/sensorwalk/analysis/GaitAnalysisEngine.kt

package com.example.sensorwalk.analysis

import android.hardware.SensorManager
import com.example.sensorwalk.data.ComparisonMetrics
import com.example.sensorwalk.data.LegMetrics
import com.example.sensorwalk.data.SensorDataPoint
import com.example.sensorwalk.util.MadgwickAHRS
import com.example.sensorwalk.util.Quaternion
import com.example.sensorwalk.util.SdkFilter
import com.example.sensorwalk.viewmodel.LegSide
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation
import org.apache.commons.math3.stat.descriptive.moment.Variance
import org.apache.commons.math3.stat.descriptive.rank.Percentile
import org.apache.commons.math3.stat.inference.MannWhitneyUTest
import kotlin.math.*

object GaitAnalysisEngine {

    // --- Constants ---
    private const val G = 9.81
    private const val ACTIVITY_WINDOW_SIZE_SECONDS = 1.0
    private const val ACTIVITY_VARIANCE_THRESHOLD = 0.5
    private const val MIN_WALK_SEGMENT_SECONDS = 2.0
    private const val FILTER_CUTOFF_HZ = 15.0
    private const val HEEL_STRIKE_PEAK_HEIGHT = 1.2
    private const val TOE_OFF_VALLEY_HEIGHT = -0.8
    private const val MIN_PEAK_DISTANCE_SECONDS = 0.4
    private const val ZUPT_ACC_THRESH = 0.5
    private const val ZUPT_GYRO_THRESH_RAD_S = 1.0
    private const val ABNORMAL_SWING_Z_SCORE = 2.0 // Z-score阈值，用于判断异常摆动
    private const val TURN_YAW_RATE_THRESHOLD_DEG_S = 45.0
    private const val MIN_TURN_DURATION_S = 0.5
    // ★★★ [修复 #17] 新增：定义一个合理的步长上限（米），用于过滤异常值 ★★★
    private const val MAX_REASONABLE_STEP_LENGTH = 2.2

    fun processFullAnalysis(
        localSegments: List<List<SensorDataPoint>>,
        remoteSegments: List<List<SensorDataPoint>>? = null,
        legSide: LegSide,
        remoteLegSide: LegSide? = null
    ): Triple<LegMetrics, LegMetrics?, ComparisonMetrics?> {
        val localMetrics = analyzeSingleLeg(localSegments, legSide)
        val remoteMetrics = remoteSegments?.let { analyzeSingleLeg(it, remoteLegSide!!) }

        val comparisonMetrics = if (remoteMetrics != null) {
            val leftMetrics = if (legSide == LegSide.LEFT) localMetrics else remoteMetrics
            val rightMetrics = if (legSide == LegSide.RIGHT) localMetrics else remoteMetrics
            compareLegs(leftMetrics, rightMetrics)
        } else {
            null
        }

        // 如果是单腿模式，也估算一个对称性分数
        if (remoteMetrics == null) {
            localMetrics.estimatedSymmetryScore = estimateSingleLegSymmetry(localMetrics.rawGaitCycles)
        }
        return Triple(localMetrics, remoteMetrics, comparisonMetrics)
    }

    fun detectWalkingActivity(dataPoints: List<SensorDataPoint>, sampleRate: Double): List<List<SensorDataPoint>> {
        if (dataPoints.size < sampleRate * MIN_WALK_SEGMENT_SECONDS) return emptyList()

        val windowSize = (ACTIVITY_WINDOW_SIZE_SECONDS * sampleRate).toInt().coerceAtLeast(1)
        val minSegmentSize = (MIN_WALK_SEGMENT_SECONDS * sampleRate).toInt()

        val accMag = dataPoints.map { sqrt(it.accX.pow(2) + it.accY.pow(2) + it.accZ.pow(2)) }
        val variance = accMag.windowed(size = windowSize, step = 1, partialWindows = true) { window ->
            if (window.size < 2) 0.0 else Variance().evaluate(window.map { it.toDouble() }.toDoubleArray())
        }.toMutableList()
        while(variance.size < dataPoints.size) variance.add(0.0) // 补全尾部

        val isWalking = variance.map { it > ACTIVITY_VARIANCE_THRESHOLD }

        val walkingSegments = mutableListOf<List<SensorDataPoint>>()
        var inSegment = false
        var startIdx = 0
        for (i in isWalking.indices) {
            if (isWalking[i] && !inSegment) {
                inSegment = true
                startIdx = i
            } else if (!isWalking[i] && inSegment) {
                inSegment = false
                if (i - startIdx > minSegmentSize) {
                    walkingSegments.add(dataPoints.subList(startIdx, i))
                }
            }
        }
        if (inSegment && dataPoints.size - startIdx > minSegmentSize) {
            walkingSegments.add(dataPoints.subList(startIdx, dataPoints.size))
        }
        return walkingSegments
    }

    fun calculateSampleRate(data: List<SensorDataPoint>): Double {
        if (data.size < 10) return 100.0 // 默认值
        val durationSeconds = (data.last().timestamp - data.first().timestamp) / 1_000_000_000.0
        return if (durationSeconds > 1) (data.size - 1) / durationSeconds else 100.0
    }

    private fun analyzeSingleLeg(segments: List<List<SensorDataPoint>>, legSide: LegSide): LegMetrics {
        val allData = segments.flatten()
        if (allData.isEmpty()) return LegMetrics()

        val sampleRate = calculateSampleRate(allData)
        if (sampleRate < 20) return LegMetrics() // 采样率过低无法分析

        val timestamps = allData.map { it.timestamp }.toLongArray()
        val acc = allData.map { doubleArrayOf(it.accX.toDouble(), it.accY.toDouble(), it.accZ.toDouble()) }.toTypedArray()
        val gyro = allData.map { doubleArrayOf(it.gyroX.toDouble(), it.gyroY.toDouble(), it.gyroZ.toDouble()) }.toTypedArray()
        val mag = allData.map { doubleArrayOf(it.magX.toDouble(), it.magY.toDouble(), it.magZ.toDouble()) }.toTypedArray()
        val pressure = allData.map { it.pressure.toDouble() }.toDoubleArray()

        // 滤波
        val accFilt = SdkFilter.filterData(acc, sampleRate, FILTER_CUTOFF_HZ)
        val gyroFilt = SdkFilter.filterData(gyro, sampleRate, FILTER_CUTOFF_HZ)

        // 核心计算
        val quaternions = estimateOrientation(accFilt, gyroFilt, mag, sampleRate)
        val (flexionAngles, abductionAngles, yawAngles) = getEulerAngles(quaternions, legSide)
        val (heelStrikes, _) = detectGaitEvents(accFilt, sampleRate)

        if (heelStrikes.size < 3) return LegMetrics() // 步数太少

        val (toeOffs, _) = findValleys(accFilt, sampleRate, MIN_PEAK_DISTANCE_SECONDS, TOE_OFF_VALLEY_HEIGHT)
        val (linearAccGlobal, positions) = reconstructTrajectory(quaternions, accFilt, gyroFilt, sampleRate)

        // 计算各项指标
        val cadence = calculateCadence(timestamps, heelStrikes)
        val (avgCycle, stdCycle, rawCycles) = calculateCycleTimes(timestamps, heelStrikes)
        val (stanceTime, swingTime) = calculateStanceSwing(timestamps, heelStrikes, toeOffs)
        val (stepLength, stepLengthCv, rawStepLengths) = calculateStepLength(positions, heelStrikes)
        val (flexionRange, abductionRange) = calculateAngleRanges(flexionAngles, abductionAngles)
        val abnormalSwings = detectAbnormalSwings(abductionAngles)
        val gaitStability = Variance().evaluate(accFilt.map { sqrt(it[0].pow(2) + it[1].pow(2) + it[2].pow(2)) }.toDoubleArray())
        val (footClearance, circumduction) = calculateSwingMetrics(positions, heelStrikes, toeOffs)
        val (grfMax, jerkAvg, _) = calculateDynamics(linearAccGlobal, sampleRate)
        val totalTurns = detectTurns(yawAngles, sampleRate)
        val (altitude, gain, loss) = calculateAltitudeMetrics(pressure, sampleRate)

        return LegMetrics(
            totalSteps = heelStrikes.size, cadence = cadence, avgGaitCycle = avgCycle,
            stepAsymmetry = if (avgCycle > 0) stdCycle / avgCycle else 0.0,
            stanceTime = stanceTime, swingTime = swingTime, stepLengthMean = stepLength,
            stepLengthCv = stepLengthCv, gaitStability = gaitStability, flexionRange = flexionRange,
            abductionRange = abductionRange, abnormalSwingCount = abnormalSwings,
            footClearance = footClearance, circumduction = circumduction, grfMax = grfMax,
            jerkAvg = jerkAvg, dominantFrequency = 0.0, // 占位符
            totalTurns = totalTurns, totalAltitudeGain = gain, totalAltitudeLoss = loss,
            rawGaitCycles = rawCycles, rawStepLengths = rawStepLengths, rawFlexionAngles = flexionAngles,
            rawAbductionAngles = abductionAngles, rawYawAngles = yawAngles, rawAltitude = altitude,
            rawTimestamps = allData.map { (it.timestamp - allData.first().timestamp) / 1e9 }
        )
    }

    private fun estimateOrientation(acc: Array<DoubleArray>, gyro: Array<DoubleArray>, mag: Array<DoubleArray>, sampleRate: Double): Array<FloatArray> {
        val ahrs = MadgwickAHRS((1.0 / sampleRate).toFloat(), 0.1f) // beta gain
        val quaternions = Array(acc.size) { FloatArray(4) }
        for (i in acc.indices) {
            ahrs.update(
                gyro[i][0].toFloat(), gyro[i][1].toFloat(), gyro[i][2].toFloat(),
                acc[i][0].toFloat(), acc[i][1].toFloat(), acc[i][2].toFloat(),
                mag[i][0].toFloat(), mag[i][1].toFloat(), mag[i][2].toFloat()
            )
            quaternions[i] = ahrs.quaternion.clone()
        }
        return quaternions
    }

    private fun getEulerAngles(quaternions: Array<FloatArray>, legSide: LegSide): Triple<List<Double>, List<Double>, List<Double>> {
        val flexion = mutableListOf<Double>()  // Pitch
        val abduction = mutableListOf<Double>() // Roll
        val yaw = mutableListOf<Double>()       // Yaw
        quaternions.forEach { q ->
            val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
            // Pitch (Y-axis rotation) for flexion/extension
            val pitchRad = asin((2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0))
            flexion.add(Math.toDegrees(pitchRad))

            // Roll (X-axis rotation) for abduction/adduction
            val rollRad = atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y))
            val rollDeg = Math.toDegrees(rollRad)
            // The abduction angle needs to be inverted for the right leg
            abduction.add(if (legSide == LegSide.LEFT) rollDeg else -rollDeg)

            // Yaw (Z-axis rotation) for turns
            val yawRad = atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
            yaw.add(Math.toDegrees(yawRad))
        }
        return Triple(flexion, abduction, yaw)
    }

    private fun detectGaitEvents(accFilt: Array<DoubleArray>, sampleRate: Double): Pair<List<Int>, List<Double>> {
        val accMag = accFilt.map { sqrt(it[0].pow(2) + it[1].pow(2) + it[2].pow(2)) }
        val meanAcc = accMag.average().takeIf { it > 0 } ?: 1.0
        val accNorm = accMag.map { it / meanAcc }.toDoubleArray()
        return findPeaks(accNorm, sampleRate, MIN_PEAK_DISTANCE_SECONDS, HEEL_STRIKE_PEAK_HEIGHT)
    }

    private fun findPeaks(data: DoubleArray, fs: Double, distSec: Double, height: Double): Pair<List<Int>, List<Double>> {
        val indices = mutableListOf<Int>()
        val values = mutableListOf<Double>()
        val distSamples = (distSec * fs).toInt()
        var i = 1
        while (i < data.size - 1) {
            if (data[i] > data[i - 1] && data[i] > data[i + 1] && data[i] > height) {
                indices.add(i)
                values.add(data[i])
                i += distSamples // Skip forward to avoid finding peaks too close
            } else {
                i++
            }
        }
        return indices to values
    }

    private fun findValleys(data: Array<DoubleArray>, fs: Double, distSec: Double, height: Double): Pair<List<Int>, List<Double>> {
        val accMag = data.map { sqrt(it[0].pow(2) + it[1].pow(2) + it[2].pow(2)) }
        val meanAcc = accMag.average().takeIf { it > 0 } ?: 1.0
        val accNorm = accMag.map { it / meanAcc }.toDoubleArray()
        val invertedData = accNorm.map { -it }.toDoubleArray()
        val (indices, values) = findPeaks(invertedData, fs, distSec, -height)
        return indices to values.map { -it }
    }

    private fun reconstructTrajectory(quaternions: Array<FloatArray>, accFilt: Array<DoubleArray>, gyroFilt: Array<DoubleArray>, sampleRate: Double): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val dt = 1.0 / sampleRate
        val gVec = floatArrayOf(0.0f, 0.0f, G.toFloat())
        val positions = Array(accFilt.size) { DoubleArray(3) }
        val velocities = Array(accFilt.size) { DoubleArray(3) }
        val linearAccGlobal = Array(accFilt.size) { DoubleArray(3) }

        val isStationary = gyroFilt.indices.map { i ->
            val gyroMag = sqrt(gyroFilt[i][0].pow(2) + gyroFilt[i][1].pow(2) + gyroFilt[i][2].pow(2))
            val accMag = sqrt(accFilt[i][0].pow(2) + accFilt[i][1].pow(2) + accFilt[i][2].pow(2))
            abs(accMag - G) < ZUPT_ACC_THRESH && gyroMag < ZUPT_GYRO_THRESH_RAD_S
        }

        for (i in accFilt.indices) {
            val qConj = quaternions[i].let { floatArrayOf(it[0], -it[1], -it[2], -it[3]) }
            val accSensor = accFilt[i].map { it.toFloat() }.toFloatArray()
            val accGlobalWithG = Quaternion.rotateVector(accSensor, qConj)
            linearAccGlobal[i] = DoubleArray(3) { j -> (accGlobalWithG[j] - gVec[j]).toDouble() }

            if (i > 0) {
                var newVel = DoubleArray(3) { j -> velocities[i-1][j] + linearAccGlobal[i][j] * dt }
                if (isStationary[i]) {
                    newVel = doubleArrayOf(0.0, 0.0, 0.0) // Zero-velocity update (ZUPT)
                }
                velocities[i] = newVel
                positions[i] = DoubleArray(3) { j -> positions[i-1][j] + newVel[j] * dt }
            }
        }
        return linearAccGlobal to positions
    }

    private fun calculateCadence(timestamps: LongArray, peaks: List<Int>): Double {
        if (peaks.size < 2) return 0.0
        val timeDiffSeconds = (timestamps[peaks.last()] - timestamps[peaks.first()]) / 1e9
        return if (timeDiffSeconds > 0) (peaks.size - 1) * 60.0 / timeDiffSeconds else 0.0
    }

    private fun calculateCycleTimes(timestamps: LongArray, peaks: List<Int>): Triple<Double, Double, List<Double>> {
        if (peaks.size < 2) return Triple(0.0, 0.0, emptyList())
        val cycles = (1 until peaks.size).map { (timestamps[peaks[it]] - timestamps[peaks[it - 1]]) / 1e9 }
        if (cycles.isEmpty()) return Triple(0.0, 0.0, emptyList())
        val avg = cycles.average()
        val std = if (cycles.size > 1) StandardDeviation().evaluate(cycles.toDoubleArray()) else 0.0
        return Triple(avg, std, cycles)
    }

    private fun calculateStanceSwing(timestamps: LongArray, peaks: List<Int>, valleys: List<Int>): Pair<Double, Double> {
        val stanceTimes = mutableListOf<Double>()
        val swingTimes = mutableListOf<Double>()
        if (peaks.size < 2 || valleys.isEmpty()) return 0.0 to 0.0

        for (i in 0 until peaks.size - 1) {
            val hs1 = peaks[i]; val hs2 = peaks[i+1]
            val toeOffInCycle = valleys.firstOrNull { it > hs1 && it < hs2 }
            if (toeOffInCycle != null) {
                stanceTimes.add((timestamps[toeOffInCycle] - timestamps[hs1]) / 1e9)
                swingTimes.add((timestamps[hs2] - timestamps[toeOffInCycle]) / 1e9)
            }
        }
        return stanceTimes.averageOrZero() to swingTimes.averageOrZero()
    }

    // ★★★ [修复 #17] 步长计算函数已更新，加入了对异常值的过滤 ★★★
    private fun calculateStepLength(positions: Array<DoubleArray>, peaks: List<Int>): Triple<Double, Double, List<Double>> {
        if (peaks.size < 2 || positions.isEmpty()) return Triple(0.0, 0.0, emptyList())

        val stepLengths = (0 until peaks.size - 1).mapNotNull { i ->
            val p1Idx = peaks[i]
            val p2Idx = peaks[i + 1]
            // 增加索引检查，防止崩溃
            if (p1Idx >= positions.size || p2Idx >= positions.size) return@mapNotNull null

            val p1 = positions[p1Idx]
            val p2 = positions[p2Idx]
            val distance = sqrt((p2[0] - p1[0]).pow(2) + (p2[1] - p1[1]).pow(2))

            // 过滤掉不合理的步长值 (例如大于2.2米)
            if (distance > MAX_REASONABLE_STEP_LENGTH) null else distance
        }

        if (stepLengths.isEmpty()) return Triple(0.0, 0.0, emptyList())
        val mean = stepLengths.averageOrZero()
        val std = if (stepLengths.size > 1) StandardDeviation().evaluate(stepLengths.toDoubleArray()) else 0.0
        val cv = if (mean > 0) std / mean else 0.0
        return Triple(mean, cv, stepLengths)
    }

    // ★★★ [修复 #17] 角度范围计算函数已更新，使用百分位距替代max-min，使其对噪声不敏感 ★★★
    private fun calculateAngleRanges(flexion: List<Double>, abduction: List<Double>): Pair<Double, Double> {
        if (flexion.size < 20 || abduction.size < 20) return 0.0 to 0.0 // 需要足够数据点进行统计

        val percentile = Percentile()

        // 使用第98和第2百分位数来计算范围，这种方法对极端离群值不敏感，能有效避免几百度的异常值
        val flexMax = percentile.evaluate(flexion.toDoubleArray(), 98.0)
        val flexMin = percentile.evaluate(flexion.toDoubleArray(), 2.0)
        val flexRange = flexMax - flexMin

        val abdMax = percentile.evaluate(abduction.toDoubleArray(), 98.0)
        val abdMin = percentile.evaluate(abduction.toDoubleArray(), 2.0)
        val abdRange = abdMax - abdMin

        return flexRange to abdRange
    }


    private fun detectAbnormalSwings(abductionAngles: List<Double>): Int {
        if (abductionAngles.size < 20) return 0
        val stdDev = StandardDeviation().evaluate(abductionAngles.toDoubleArray())
        if (stdDev == 0.0) return 0
        val mean = abductionAngles.average()
        // 计算 Z-score 并统计超出阈值的点
        return abductionAngles.count { abs((it - mean) / stdDev) > ABNORMAL_SWING_Z_SCORE }
    }

    private fun calculateSwingMetrics(positions: Array<DoubleArray>, peaks: List<Int>, valleys: List<Int>): Pair<Double, Double> {
        val clearances = mutableListOf<Double>()
        val circumductions = mutableListOf<Double>()
        if (peaks.size < 2 || valleys.isEmpty() || positions.isEmpty()) return 0.0 to 0.0

        for (i in 0 until peaks.size - 1) {
            val toeOff = valleys.firstOrNull { it > peaks[i] && it < peaks[i + 1] }
            if (toeOff != null && peaks[i + 1] < positions.size) {
                val swingPhasePositions = positions.slice(toeOff..peaks[i + 1])
                if (swingPhasePositions.isNotEmpty()) {
                    clearances.add(swingPhasePositions.maxOfOrNull { it[2] } ?: 0.0)
                    val yPos = swingPhasePositions.map { it[1] } // Mediolateral displacement
                    circumductions.add((yPos.maxOrNull() ?: 0.0) - (yPos.minOrNull() ?: 0.0))
                }
            }
        }
        return clearances.averageOrZero() to circumductions.averageOrZero()
    }

    private fun calculateDynamics(linearAcc: Array<DoubleArray>, sampleRate: Double): Triple<Double, Double, Double> {
        if (linearAcc.size < 2) return Triple(0.0, 0.0, 0.0)
        val grfVertical = linearAcc.map { it[2] } // Z-axis is vertical
        val grfMax = (grfVertical.maxOrNull() ?: 0.0) / G // Normalize by G

        val jerk = (1 until linearAcc.size).map { i ->
            val jerkVec = DoubleArray(3) { j -> (linearAcc[i][j] - linearAcc[i - 1][j]) * sampleRate }
            sqrt(jerkVec[0].pow(2) + jerkVec[1].pow(2) + jerkVec[2].pow(2))
        }
        val jerkAvg = jerk.averageOrZero()
        return Triple(grfMax, jerkAvg, 0.0) // Dominant frequency not implemented yet
    }

    private fun detectTurns(yawAngles: List<Double>, sampleRate: Double): Int {
        if (yawAngles.size < sampleRate) return 0

        val yawRate = yawAngles.windowed(2, 1).map { (it[1] - it[0]) * sampleRate }
        var turnCount = 0
        var inTurn = false
        var turnStartIdx = -1
        val minTurnSamples = (MIN_TURN_DURATION_S * sampleRate).toInt()

        for (i in yawRate.indices) {
            if (abs(yawRate[i]) > TURN_YAW_RATE_THRESHOLD_DEG_S) {
                if (!inTurn) {
                    inTurn = true
                    turnStartIdx = i
                }
            } else {
                if (inTurn) {
                    if (i - turnStartIdx >= minTurnSamples) {
                        turnCount++
                    }
                    inTurn = false
                }
            }
        }
        if (inTurn && (yawRate.size - turnStartIdx) >= minTurnSamples) {
            turnCount++
        }
        return turnCount
    }

    private fun calculateAltitudeMetrics(pressureHpa: DoubleArray, sampleRate: Double): Triple<List<Double>, Double, Double> {
        if (pressureHpa.all { it == 0.0 }) return Triple(emptyList(), 0.0, 0.0)
        val altitude = pressureHpa.map { SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it.toFloat()).toDouble() }

        // 使用一个简单的低通滤波器平滑海拔数据，减少噪音
        val smoothedAltitude = SdkFilter.filtfilt(altitude.toDoubleArray(), 1.0, sampleRate)

        var gain = 0.0
        var loss = 0.0
        for(i in 1 until smoothedAltitude.size) {
            val diff = smoothedAltitude[i] - smoothedAltitude[i-1]
            if (diff > 0) gain += diff
            else loss += abs(diff)
        }
        return Triple(smoothedAltitude.toList(), gain, loss)
    }

    private fun compareLegs(left: LegMetrics, right: LegMetrics): ComparisonMetrics {
        fun symmetryIndex(l: Double, r: Double) = if (l + r > 0) 1.0 - abs(l - r) / ((l + r) / 2.0) else 1.0
        fun symmetryPValue(l: List<Double>, r: List<Double>) = if (l.size > 1 && r.size > 1) MannWhitneyUTest().mannWhitneyUTest(l.toDoubleArray(), r.toDoubleArray()) else 1.0

        val timeSym = symmetryIndex(left.avgGaitCycle, right.avgGaitCycle)
        val stepLenSym = symmetryIndex(left.stepLengthMean, right.stepLengthMean)
        val stanceSym = symmetryIndex(left.stanceTime, right.stanceTime)
        val swingSym = symmetryIndex(left.swingTime, right.swingTime)
        val flexionSym = symmetryIndex(left.flexionRange, right.flexionRange)
        val abductionSym = symmetryIndex(left.abductionRange, right.abductionRange)
        val pValTime = symmetryPValue(left.rawGaitCycles, right.rawGaitCycles)
        val pValStep = symmetryPValue(left.rawStepLengths, right.rawStepLengths)

        // 加权计算总分
        val overallScore = (timeSym * 0.25 + stepLenSym * 0.25 + stanceSym * 0.15 + swingSym * 0.15 + flexionSym * 0.1 + abductionSym * 0.1) * 100

        return ComparisonMetrics(timeSymmetry = timeSym, stepLengthSymmetry = stepLenSym, stanceTimeSymmetry = stanceSym,
            swingTimeSymmetry = swingSym, flexionRangeSymmetry = flexionSym, abductionRangeSymmetry = abductionSym,
            timeSymmetryPValue = pValTime, stepLengthSymmetryPValue = pValStep,
            overallSymmetryScore = overallScore.coerceIn(0.0, 100.0))
    }

    private fun estimateSingleLegSymmetry(rawGaitCycles: List<Double>): Double {
        if (rawGaitCycles.size < 4) return 50.0 // 数据太少，给个中间值
        // 通过比较连续步态周期的差异来估算
        val evenCycles = rawGaitCycles.filterIndexed { index, _ -> index % 2 == 0 }
        val oddCycles = rawGaitCycles.filterIndexed { index, _ -> index % 2 != 0 }
        if (evenCycles.isEmpty() || oddCycles.isEmpty()) return 50.0
        val avgEven = evenCycles.average()
        val avgOdd = oddCycles.average()
        // 用 (小/大) * 100 来估算一个分数
        return (min(avgEven, avgOdd) / max(avgEven, avgOdd)) * 100
    }

    private fun Collection<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
