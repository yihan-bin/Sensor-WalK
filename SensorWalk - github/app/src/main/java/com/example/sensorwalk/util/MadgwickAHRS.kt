package com.example.sensorwalk.util

import kotlin.math.sqrt

/**
 * Full MadgwickAHRS implementation based on the original C code.
 * This replaces the need for an external library.
 */
class MadgwickAHRS(private val samplePeriod: Float, private var beta: Float) {
    var quaternion = floatArrayOf(1f, 0f, 0f, 0f) // w, x, y, z

    fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, mx: Float, my: Float, mz: Float) {
        var (q0, q1, q2, q3) = quaternion

        var recipNorm: Float
        var s0: Float
        var s1: Float
        var s2: Float
        var s3: Float
        var qDot1: Float
        var qDot2: Float
        var qDot3: Float
        var qDot4: Float
        var hx: Float
        var hy: Float
        val _2q0mx: Float
        var _2q0my: Float
        var _2q0mz: Float
        var _2q1mx: Float
        val _2bx: Float
        var _2bz: Float
        var _4bx: Float
        var _4bz: Float
        val _2q0 = 2f * q0
        val _2q1 = 2f * q1
        val _2q2 = 2f * q2
        val _2q3 = 2f * q3
        val _2q0q2 = 2f * q0 * q2
        val _2q2q3 = 2f * q2 * q3
        val q0q0 = q0 * q0
        val q0q1 = q0 * q1
        val q0q2 = q0 * q2
        val q0q3 = q0 * q3
        val q1q1 = q1 * q1
        val q1q2 = q1 * q2
        val q1q3 = q1 * q3
        val q2q2 = q2 * q2
        val q2q3 = q2 * q3
        val q3q3 = q3 * q3

        // Use IMU algorithm if magnetometer measurement invalid (avoids NaN in magnetometer normalisation)
        if (mx == 0f && my == 0f && mz == 0f) {
            updateIMU(gx, gy, gz, ax, ay, az)
            return
        }

        // Rate of change of quaternion from gyroscope
        qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
        qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
        qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

        // Compute feedback only if accelerometer measurement valid (avoids NaN in accelerometer normalisation)
        if (!(ax == 0f && ay == 0f && az == 0f)) {
            // Normalise accelerometer measurement
            recipNorm = invSqrt(ax * ax + ay * ay + az * az)
            val axN = ax * recipNorm
            val ayN = ay * recipNorm
            val azN = az * recipNorm

            // Normalise magnetometer measurement
            recipNorm = invSqrt(mx * mx + my * my + mz * mz)
            val mxN = mx * recipNorm
            val myN = my * recipNorm
            val mzN = mz * recipNorm

            // Auxiliary variables to avoid repeated arithmetic
            _2q0mx = 2f * q0 * mxN
            _2q0my = 2f * q0 * myN
            _2q0mz = 2f * q0 * mzN
            _2q1mx = 2f * q1 * mxN
            hx = mxN * q0q0 - _2q0my * q3 + _2q0mz * q2 + mxN * q1q1 + _2q1 * myN * q2 + _2q1 * mzN * q3 - mxN * q2q2 - mxN * q3q3
            hy = _2q0mx * q3 + myN * q0q0 - _2q0mz * q1 + _2q1mx * q2 - myN * q1q1 + myN * q2q2 + _2q2 * mzN * q3 - myN * q3q3
            _2bx = sqrt(hx * hx + hy * hy)
            _2bz = -_2q0mx * q2 + _2q0my * q1 + mzN * q0q0 + _2q1mx * q3 - mzN * q1q1 + _2q2 * myN * q3 - mzN * q2q2 + mzN * q3q3
            _4bx = 2f * _2bx
            _4bz = 2f * _2bz

            // Gradient decent algorithm corrective step
            s0 = -_2q2 * (2f * q1q3 - _2q0q2 - axN) + _2q1 * (2f * q0q1 + _2q2q3 - ayN) - _2bz * q2 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxN) + (-_2bx * q3 + _2bz * q1) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myN) + _2bx * q2 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzN)
            s1 = _2q3 * (2f * q1q3 - _2q0q2 - axN) + _2q0 * (2f * q0q1 + _2q2q3 - ayN) - 4f * q1 * (1 - 2f * q1q1 - 2f * q2q2 - azN) + _2bz * q3 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxN) + (_2bx * q2 + _2bz * q0) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myN) + (_2bx * q3 - _4bz * q1) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzN)
            s2 = -_2q0 * (2f * q1q3 - _2q0q2 - axN) + _2q3 * (2f * q0q1 + _2q2q3 - ayN) - 4f * q2 * (1 - 2f * q1q1 - 2f * q2q2 - azN) + (-_4bx * q2 - _2bz * q0) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxN) + (_2bx * q1 + _2bz * q3) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myN) + (_2bx * q0 - _4bz * q2) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzN)
            s3 = _2q1 * (2f * q1q3 - _2q0q2 - axN) + _2q2 * (2f * q0q1 + _2q2q3 - ayN) + (-_4bx * q3 + _2bz * q1) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxN) + (-_2bx * q0 + _2bz * q2) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myN) + _2bx * q1 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzN)
            recipNorm = invSqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3) // normalise step magnitude
            s0 *= recipNorm
            s1 *= recipNorm
            s2 *= recipNorm
            s3 *= recipNorm

            // Apply feedback step
            qDot1 -= beta * s0
            qDot2 -= beta * s1
            qDot3 -= beta * s2
            qDot4 -= beta * s3
        }

        // Integrate rate of change of quaternion to yield quaternion
        q0 += qDot1 * samplePeriod
        q1 += qDot2 * samplePeriod
        q2 += qDot3 * samplePeriod
        q3 += qDot4 * samplePeriod

        // Normalise quaternion
        recipNorm = invSqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        quaternion[0] = q0 * recipNorm
        quaternion[1] = q1 * recipNorm
        quaternion[2] = q2 * recipNorm
        quaternion[3] = q3 * recipNorm
    }

    fun updateIMU(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        var (q0, q1, q2, q3) = quaternion
        var recipNorm: Float
        var s0: Float
        var s1: Float
        var s2: Float
        var s3: Float
        var qDot1: Float
        var qDot2: Float
        var qDot3: Float
        var qDot4: Float

        // Rate of change of quaternion from gyroscope
        qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
        qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
        qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

        // Compute feedback only if accelerometer measurement valid (avoids NaN in accelerometer normalisation)
        if (!(ax == 0.0f && ay == 0.0f && az == 0.0f)) {

            // Normalise accelerometer measurement
            recipNorm = invSqrt(ax * ax + ay * ay + az * az)
            val axN = ax * recipNorm
            val ayN = ay * recipNorm
            val azN = az * recipNorm

            // Auxiliary variables to avoid repeated arithmetic
            val _2q0 = 2.0f * q0
            val _2q1 = 2.0f * q1
            val _2q2 = 2.0f * q2
            val _2q3 = 2.0f * q3
            val _4q0 = 4.0f * q0
            val _4q1 = 4.0f * q1
            val _4q2 = 4.0f * q2
            val _8q1 = 8.0f * q1
            val _8q2 = 8.0f * q2
            val q0q0 = q0 * q0
            val q1q1 = q1 * q1
            val q2q2 = q2 * q2
            val q3q3 = q3 * q3

            // Gradient decent algorithm corrective step
            s0 = _4q0 * q2q2 + _2q2 * axN + _4q0 * q1q1 - _2q1 * ayN
            s1 = _4q1 * q3q3 - _2q3 * axN + 4.0f * q0q0 * q1 - _2q0 * ayN - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * azN
            s2 = 4.0f * q0q0 * q2 + _2q0 * axN + _4q2 * q3q3 - _2q3 * ayN - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * azN
            s3 = 4.0f * q1q1 * q3 - _2q1 * axN + 4.0f * q2q2 * q3 - _2q2 * ayN

            recipNorm = invSqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3) // normalise step magnitude
            s0 *= recipNorm
            s1 *= recipNorm
            s2 *= recipNorm
            s3 *= recipNorm

            // Apply feedback step
            qDot1 -= beta * s0
            qDot2 -= beta * s1
            qDot3 -= beta * s2
            qDot4 -= beta * s3
        }

        // Integrate rate of change of quaternion to yield quaternion
        q0 += qDot1 * samplePeriod
        q1 += qDot2 * samplePeriod
        q2 += qDot3 * samplePeriod
        q3 += qDot4 * samplePeriod

        // Normalise quaternion
        recipNorm = invSqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        quaternion[0] = q0 * recipNorm
        quaternion[1] = q1 * recipNorm
        quaternion[2] = q2 * recipNorm
        quaternion[3] = q3 * recipNorm
    }

    private fun invSqrt(x: Float): Float = 1.0f / sqrt(x)
}
