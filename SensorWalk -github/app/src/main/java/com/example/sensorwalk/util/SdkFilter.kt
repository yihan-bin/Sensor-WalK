package com.example.sensorwalk.util

import org.apache.commons.math3.analysis.function.Tan
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Implements a 2nd order Butterworth low-pass filter with zero-phase filtering (filtfilt).
 * This replaces the need for the JDSP library and mimics Scipy's behavior.
 */
object SdkFilter {

    /**
     * Applies a zero-phase Butterworth low-pass filter to each dimension of the data.
     */
    fun filterData(
        data: Array<DoubleArray>,
        sampleRate: Double,
        cutoff: Double
    ): Array<DoubleArray> {
        if (data.isEmpty()) return emptyArray()
        val numDimensions = data[0].size
        val filteredData = Array(data.size) { DoubleArray(numDimensions) }

        for (dim in 0 until numDimensions) {
            val column = DoubleArray(data.size) { i -> data[i][dim] }
            val filteredColumn = filtfilt(column, cutoff, sampleRate)
            for (i in filteredColumn.indices) {
                filteredData[i][dim] = filteredColumn[i]
            }
        }
        return filteredData
    }

    // ------------------- FIX START -------------------
    // 将下面的 private fun 修改为 internal fun

    /**
     * Calculates the coefficients for a 2nd-order Butterworth filter.
     * visibility changed from private to internal to be accessible from GaitAnalysisEngine.
     */
    internal fun getButterworthCoefficients(cutoff: Double, sampleRate: Double): Pair<DoubleArray, DoubleArray> {
        val wc = Tan().value(PI * cutoff / sampleRate)
        val k1 = sqrt(2.0) * wc
        val k2 = wc * wc

        val a0 = 1.0 + k1 + k2
        val a1 = 2 * (k2 - 1.0)
        val a2 = 1.0 - k1 + k2

        val b0 = k2
        val b1 = 2 * k2
        val b2 = k2

        // Normalizing
        val a = doubleArrayOf(1.0, a1 / a0, a2 / a0)
        val b = doubleArrayOf(b0 / a0, b1 / a0, b2 / a0)

        return Pair(b, a)
    }

    /**
     * Applies a forward-backward, zero-phase digital filter.
     * visibility changed from private to internal to be accessible from GaitAnalysisEngine.
     */
    internal fun filtfilt(data: DoubleArray, cutoff: Double, sampleRate: Double): DoubleArray {
        if (data.size < 10) return data // Not enough data to filter
        val (b, a) = getButterworthCoefficients(cutoff, sampleRate)

        // 1. Forward pass
        val forwardFiltered = applyFilter(data, b, a)

        // 2. Reverse the filtered signal
        val reversed = forwardFiltered.reversedArray()

        // 3. Backward pass
        val backwardFiltered = applyFilter(reversed, b, a)

        // 4. Reverse again to get the final result
        return backwardFiltered.reversedArray()
    }

    // ------------------- FIX END -------------------

    private fun applyFilter(data: DoubleArray, b: DoubleArray, a: DoubleArray): DoubleArray {
        val output = DoubleArray(data.size)
        for (i in data.indices) {
            var y = b[0] * data[i]
            if (i >= 1) {
                y += b[1] * data[i - 1] - a[1] * output[i - 1]
            }
            if (i >= 2) {
                y += b[2] * data[i - 2] - a[2] * output[i - 2]
            }
            output[i] = y
        }
        return output
    }
}
