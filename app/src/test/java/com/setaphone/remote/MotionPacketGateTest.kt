package com.setaphone.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionPacketGateTest {
    @Test
    fun `微小姿态颤动不会发送运动包`() {
        val gate = MotionPacketGate()
        assertEquals(MotionPacketKind.POSE, gate.next(PoseAngles(0.0, 0.0, 0.0), 0L))
        assertEquals(MotionPacketKind.NONE, gate.next(PoseAngles(0.01, -0.02, 0.02), 20_000_000L))
    }

    @Test
    fun `缓慢转动累计超过阈值后发送`() {
        val gate = MotionPacketGate()
        gate.next(PoseAngles(0.0, 0.0, 0.0), 0L)
        assertEquals(MotionPacketKind.NONE, gate.next(PoseAngles(0.0, 0.07, 0.0), 20_000_000L))
        assertEquals(MotionPacketKind.POSE, gate.next(PoseAngles(0.0, 0.13, 0.0), 40_000_000L))
    }

    @Test
    fun `静止时仅发送心跳维持连接`() {
        val gate = MotionPacketGate()
        val pose = PoseAngles(1.0, 2.0, 3.0)
        gate.next(pose, 0L)
        assertEquals(MotionPacketKind.NONE, gate.next(pose, 999_000_000L))
        assertEquals(MotionPacketKind.HEARTBEAT, gate.next(pose, 1_000_000_000L))
    }

    @Test
    fun `跨越正负一百八十度时按最短角度判断`() {
        val gate = MotionPacketGate(poseThresholdDegrees = 3.0)
        gate.next(PoseAngles(0.0, 179.0, 0.0), 0L)
        assertEquals(MotionPacketKind.NONE, gate.next(PoseAngles(0.0, -179.0, 0.0), 20_000_000L))
    }
}
