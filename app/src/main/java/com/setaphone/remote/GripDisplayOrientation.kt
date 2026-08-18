package com.setaphone.remote

import kotlin.math.abs

data class GripDisplayOrientation(
    val protocolValue: String,
)

fun resolveGripDisplayOrientation(rollDegrees: Double): GripDisplayOrientation {
    val normalizedRoll = ((rollDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    if (abs(normalizedRoll) !in 45.0..135.0) {
        return GripDisplayOrientation("portrait")
    }
    return GripDisplayOrientation("landscape")
}
