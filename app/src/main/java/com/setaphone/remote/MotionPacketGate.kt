package com.setaphone.remote

import kotlin.math.abs

data class PoseAngles(val pitch: Double, val yaw: Double, val roll: Double)

enum class MotionPacketKind { POSE, HEARTBEAT, NONE }

class MotionPacketGate(
    private val poseThresholdDegrees: Double = 0.03,
    private val motionContinueThresholdDegrees: Double = 0.002,
    private val movingPoseIntervalNanos: Long = 33_333_333L,
    private val motionHoldNanos: Long = 250_000_000L,
    private val heartbeatIntervalNanos: Long = 1_000_000_000L,
) {
    private var lastSentPose: PoseAngles? = null
    private var lastObservedPose: PoseAngles? = null
    private var lastPacketAtNanos = 0L
    private var movingUntilNanos = 0L

    fun next(pose: PoseAngles, nowNanos: Long, forcePose: Boolean = false): MotionPacketKind {
        val previousSent = lastSentPose
        val previousObserved = lastObservedPose
        lastObservedPose = pose
        if (forcePose || previousSent == null) {
            lastSentPose = pose
            lastPacketAtNanos = nowNanos
            return MotionPacketKind.POSE
        }

        val startedMoving = changed(previousSent, pose, poseThresholdDegrees)
        val stillMoving = previousObserved != null &&
            changed(previousObserved, pose, motionContinueThresholdDegrees)
        if (startedMoving || stillMoving) {
            movingUntilNanos = nowNanos + motionHoldNanos
        }
        if (startedMoving ||
            (nowNanos <= movingUntilNanos && nowNanos - lastPacketAtNanos >= movingPoseIntervalNanos)
        ) {
            lastSentPose = pose
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
        lastSentPose = null
        lastObservedPose = null
        lastPacketAtNanos = 0L
        movingUntilNanos = 0L
    }

    private fun changed(previous: PoseAngles, current: PoseAngles, threshold: Double): Boolean =
        angleDistance(previous.pitch, current.pitch) >= threshold ||
            angleDistance(previous.yaw, current.yaw) >= threshold ||
            angleDistance(previous.roll, current.roll) >= threshold

    private fun angleDistance(first: Double, second: Double): Double =
        abs((second - first + 540.0) % 360.0 - 180.0)
}
