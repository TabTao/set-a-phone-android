package com.setaphone.remote

import kotlin.math.abs

data class PoseAngles(val pitch: Double, val yaw: Double, val roll: Double)

enum class MotionPacketKind { POSE, HEARTBEAT, NONE }

class MotionPacketGate(
    private val poseThresholdDegrees: Double = 0.03,
    private val heartbeatIntervalNanos: Long = 1_000_000_000L,
) {
    private var lastPose: PoseAngles? = null
    private var lastPacketAtNanos = 0L

    fun next(pose: PoseAngles, nowNanos: Long, forcePose: Boolean = false): MotionPacketKind {
        val previous = lastPose
        if (forcePose || previous == null || changed(previous, pose)) {
            lastPose = pose
            lastPacketAtNanos = nowNanos
            return MotionPacketKind.POSE
        }
        if (nowNanos - lastPacketAtNanos >= heartbeatIntervalNanos) {
            lastPacketAtNanos = nowNanos
            return MotionPacketKind.HEARTBEAT
        }
        return MotionPacketKind.NONE
    }

    fun reset() {
        lastPose = null
        lastPacketAtNanos = 0L
    }

    private fun changed(previous: PoseAngles, current: PoseAngles): Boolean =
        angleDistance(previous.pitch, current.pitch) >= poseThresholdDegrees ||
            angleDistance(previous.yaw, current.yaw) >= poseThresholdDegrees ||
            angleDistance(previous.roll, current.roll) >= poseThresholdDegrees

    private fun angleDistance(first: Double, second: Double): Double =
        abs((second - first + 540.0) % 360.0 - 180.0)
}
