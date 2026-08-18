package com.setaphone.remote

import kotlin.math.abs

data class GripDisplayOrientation(
    val protocolValue: String,
    val coordinateCorrectionDegrees: Int,
)

fun resolveGripDisplayOrientation(rollDegrees: Double): GripDisplayOrientation {
    val normalizedRoll = ((rollDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return when {
        abs(normalizedRoll) < 75.0 -> GripDisplayOrientation("portrait", 0)
        normalizedRoll >= 75.0 && normalizedRoll < 105.0 -> GripDisplayOrientation("landscape", 0)
        normalizedRoll <= -75.0 && normalizedRoll > -105.0 -> GripDisplayOrientation("landscape", 180)
        else -> GripDisplayOrientation("portrait", 180)
    }
}

fun mapRelativePoseForGrip(orientation: String, rawPitch: Double, rawYaw: Double, rawRoll: Double): PoseAngles {
    return if (orientation == "landscape") {
        // 横握后手机长短轴互换：原始 yaw 变为软件 Pitch，原始 pitch 变为软件 Yaw。
        PoseAngles(rawYaw, rawPitch, -rawRoll)
    } else {
        // 竖握保持 Pitch 轴，校正手机传感器对 Yaw/Roll 的正方向。
        PoseAngles(rawPitch, -rawYaw, -rawRoll)
    }
}
