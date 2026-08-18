package com.setaphone.remote

import kotlin.math.abs

data class GripDisplayOrientation(
    val protocolValue: String,
    val previewRotationDegrees: Float,
)

fun resolveGripDisplayOrientation(rollDegrees: Double): GripDisplayOrientation {
    val normalizedRoll = ((rollDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    if (abs(normalizedRoll) !in 45.0..135.0) {
        return GripDisplayOrientation("portrait", 0f)
    }
    val rotation = if (normalizedRoll >= 0.0) 90f else -90f
    return GripDisplayOrientation("landscape", rotation)
}
