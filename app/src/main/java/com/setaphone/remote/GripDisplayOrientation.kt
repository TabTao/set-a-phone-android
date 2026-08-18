package com.setaphone.remote

import kotlin.math.abs

data class GripDisplayOrientation(
    val protocolValue: String,
    val coordinateCorrectionDegrees: Int,
)

fun resolveGripDisplayOrientation(rollDegrees: Double): GripDisplayOrientation {
    val normalizedRoll = ((rollDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return when {
        abs(normalizedRoll) < 45.0 -> GripDisplayOrientation("portrait", 0)
        normalizedRoll >= 45.0 && normalizedRoll < 135.0 -> GripDisplayOrientation("landscape", 0)
        normalizedRoll <= -45.0 && normalizedRoll > -135.0 -> GripDisplayOrientation("landscape", 180)
        else -> GripDisplayOrientation("portrait", 180)
    }
}
