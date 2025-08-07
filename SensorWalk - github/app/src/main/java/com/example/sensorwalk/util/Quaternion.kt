package com.example.sensorwalk.util

import kotlin.math.*

data class EulerAngles(val roll: Double, val pitch: Double, val yaw: Double)

data class Quaternion(var w: Float, var x: Float, var y: Float, var z: Float) {

    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    operator fun times(q: Quaternion): Quaternion {
        return Quaternion(
            w * q.w - x * q.x - y * q.y - z * q.z,
            w * q.x + x * q.w + y * q.z - z * q.y,
            w * q.y - x * q.z + y * q.w + z * q.x,
            w * q.z + x * q.y - y * q.x + z * q.w
        )
    }

    companion object {
        /**
         * Rotates a vector by a quaternion.
         */
        fun rotateVector(vector: FloatArray, q: FloatArray): FloatArray {
            val (qw, qx, qy, qz) = q
            val (vx, vy, vz) = vector

            val p = floatArrayOf(0f, vx, vy, vz)

            // Quaternion multiplication: q * p
            val qp = floatArrayOf(
                qw*p[0] - qx*p[1] - qy*p[2] - qz*p[3],
                qw*p[1] + qx*p[0] + qy*p[3] - qz*p[2],
                qw*p[2] - qx*p[3] + qy*p[0] + qz*p[1],
                qw*p[3] + qx*p[2] - qy*p[1] + qz*p[0]
            )

            // Conjugate of q
            val qConj = floatArrayOf(qw, -qx, -qy, -qz)

            // Quaternion multiplication: (q*p) * q_conj
            val res = floatArrayOf(
                qp[0]*qConj[0] - qp[1]*qConj[1] - qp[2]*qConj[2] - qp[3]*qConj[3],
                qp[0]*qConj[1] + qp[1]*qConj[0] + qp[2]*qConj[3] - qp[3]*qConj[2],
                qp[0]*qConj[2] - qp[1]*qConj[3] + qp[2]*qConj[0] + qp[3]*qConj[1],
                qp[0]*qConj[3] + qp[1]*qConj[2] - qp[2]*qConj[1] + qp[3]*qConj[0]
            )

            return floatArrayOf(res[1], res[2], res[3])
        }
    }
}
