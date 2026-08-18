package com.setaphone.remote

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

data class GripDisplayOrientation(
    val protocolValue: String,
    val coordinateCorrectionDegrees: Int,
)

data class DeviceRotationDegrees(
    val x: Double,
    val y: Double,
    val z: Double,
)

fun resolveGripDisplayOrientation(
    verticalDeviceX: Double,
    verticalDeviceY: Double,
): GripDisplayOrientation {
    val longAxisFromVerticalDegrees = Math.toDegrees(
        acos(abs(verticalDeviceY).coerceIn(0.0, 1.0)),
    )
    return if (longAxisFromVerticalDegrees < 75.0) {
        GripDisplayOrientation("portrait", if (verticalDeviceY >= 0.0) 0 else 180)
    } else {
        GripDisplayOrientation("landscape", if (verticalDeviceX >= 0.0) 0 else 180)
    }
}

fun rotationVectorDegrees(matrix: FloatArray): DeviceRotationDegrees {
    require(matrix.size >= 9) { "旋转矩阵必须包含 9 个值" }
    val m00 = matrix[0].toDouble()
    val m11 = matrix[4].toDouble()
    val m22 = matrix[8].toDouble()
    val trace = m00 + m11 + m22
    val quaternion = DoubleArray(4)
    when {
        trace > 0.0 -> {
            val scale = sqrt(trace + 1.0) * 2.0
            quaternion[0] = 0.25 * scale
            quaternion[1] = (matrix[7] - matrix[5]) / scale
            quaternion[2] = (matrix[2] - matrix[6]) / scale
            quaternion[3] = (matrix[3] - matrix[1]) / scale
        }
        m00 > m11 && m00 > m22 -> {
            val scale = sqrt(1.0 + m00 - m11 - m22) * 2.0
            quaternion[0] = (matrix[7] - matrix[5]) / scale
            quaternion[1] = 0.25 * scale
            quaternion[2] = (matrix[1] + matrix[3]) / scale
            quaternion[3] = (matrix[2] + matrix[6]) / scale
        }
        m11 > m22 -> {
            val scale = sqrt(1.0 + m11 - m00 - m22) * 2.0
            quaternion[0] = (matrix[2] - matrix[6]) / scale
            quaternion[1] = (matrix[1] + matrix[3]) / scale
            quaternion[2] = 0.25 * scale
            quaternion[3] = (matrix[5] + matrix[7]) / scale
        }
        else -> {
            val scale = sqrt(1.0 + m22 - m00 - m11) * 2.0
            quaternion[0] = (matrix[3] - matrix[1]) / scale
            quaternion[1] = (matrix[2] + matrix[6]) / scale
            quaternion[2] = (matrix[5] + matrix[7]) / scale
            quaternion[3] = 0.25 * scale
        }
    }
    val length = sqrt(quaternion.sumOf { it * it })
    if (length < 1e-9) return DeviceRotationDegrees(0.0, 0.0, 0.0)
    for (index in quaternion.indices) quaternion[index] /= length
    if (quaternion[0] < 0.0) {
        for (index in quaternion.indices) quaternion[index] = -quaternion[index]
    }
    val vectorLength = sqrt(
        quaternion[1] * quaternion[1] +
            quaternion[2] * quaternion[2] +
            quaternion[3] * quaternion[3],
    )
    if (vectorLength < 1e-9) return DeviceRotationDegrees(0.0, 0.0, 0.0)
    val angleDegrees = Math.toDegrees(2.0 * atan2(vectorLength, quaternion[0]))
    val scale = angleDegrees / vectorLength
    return DeviceRotationDegrees(
        quaternion[1] * scale,
        quaternion[2] * scale,
        quaternion[3] * scale,
    )
}

fun mapDeviceRotationForGrip(orientation: String, rotation: DeviceRotationDegrees): PoseAngles {
    return if (orientation == "landscape") {
        PoseAngles(-rotation.y, rotation.x, -rotation.z)
    } else {
        PoseAngles(-rotation.x, rotation.y, -rotation.z)
    }
}
