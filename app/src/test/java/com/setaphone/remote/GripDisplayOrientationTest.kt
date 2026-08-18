package com.setaphone.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GripDisplayOrientationTest {
    @Test
    fun `设备长轴竖直时判定标准竖屏`() {
        assertEquals(GripDisplayOrientation("portrait", 0), resolveGripDisplayOrientation(0.0, 1.0))
    }

    @Test
    fun `设备横轴竖直时判定标准横屏`() {
        assertEquals(GripDisplayOrientation("landscape", 0), resolveGripDisplayOrientation(1.0, 0.0))
    }

    @Test
    fun `反向握持通过重力投影产生一百八十度修正`() {
        assertEquals(GripDisplayOrientation("portrait", 180), resolveGripDisplayOrientation(0.0, -1.0))
        assertEquals(GripDisplayOrientation("landscape", 180), resolveGripDisplayOrientation(-1.0, 0.0))
    }

    @Test
    fun `长轴偏离竖直七十五度后判定横屏`() {
        fun projection(angleDegrees: Double): Pair<Double, Double> {
            val radians = Math.toRadians(angleDegrees)
            return sin(radians) to cos(radians)
        }

        val portrait = projection(74.9)
        val landscape = projection(75.1)
        assertEquals("portrait", resolveGripDisplayOrientation(portrait.first, portrait.second).protocolValue)
        assertEquals("landscape", resolveGripDisplayOrientation(landscape.first, landscape.second).protocolValue)
    }

    @Test
    fun `实机采集的重力投影稳定区分横竖握姿`() {
        assertEquals("portrait", resolveGripDisplayOrientation(-0.010, 0.998).protocolValue)
        assertEquals("portrait", resolveGripDisplayOrientation(-0.009, 1.000).protocolValue)
        assertEquals("landscape", resolveGripDisplayOrientation(1.000, 0.018).protocolValue)
        assertEquals("landscape", resolveGripDisplayOrientation(0.998, 0.019).protocolValue)
    }

    @Test
    fun `旋转矩阵转换为稳定的设备旋转向量`() {
        fun axisMatrix(axis: String, degrees: Double): FloatArray {
            val radians = Math.toRadians(degrees)
            val cosine = cos(radians).toFloat()
            val sine = sin(radians).toFloat()
            return when (axis) {
                "x" -> floatArrayOf(1f, 0f, 0f, 0f, cosine, -sine, 0f, sine, cosine)
                "y" -> floatArrayOf(cosine, 0f, sine, 0f, 1f, 0f, -sine, 0f, cosine)
                else -> floatArrayOf(cosine, -sine, 0f, sine, cosine, 0f, 0f, 0f, 1f)
            }
        }

        val x = rotationVectorDegrees(axisMatrix("x", 30.0))
        val y = rotationVectorDegrees(axisMatrix("y", -40.0))
        val z = rotationVectorDegrees(axisMatrix("z", 50.0))
        val halfTurn = rotationVectorDegrees(axisMatrix("x", 180.0))
        assertEquals(30.0, x.x, 0.001)
        assertEquals(-40.0, y.y, 0.001)
        assertEquals(50.0, z.z, 0.001)
        assertEquals(180.0, kotlin.math.abs(halfTurn.x), 0.001)
    }

    @Test
    fun `按实机二次反馈修正横竖握姿的PRY方向`() {
        val rotation = DeviceRotationDegrees(2.0, 3.0, 4.0)
        assertEquals(PoseAngles(-2.0, -3.0, 4.0), mapDeviceRotationForGrip("portrait", rotation))
        assertEquals(PoseAngles(3.0, -2.0, 4.0), mapDeviceRotationForGrip("landscape", rotation))
    }

    @Test
    fun `加速度与横竖握姿复用PRY坐标映射`() {
        val acceleration = mapLinearAccelerationForGrip("portrait", 2.0, 3.0, 4.0)
        assertEquals(AccelerationAxes(3.0, 2.0, 4.0), acceleration)

        val landscape = mapLinearAccelerationForGrip("landscape", 2.0, 3.0, 4.0)
        assertEquals(AccelerationAxes(-2.0, 3.0, 4.0), landscape)
    }

    @Test
    fun `反向握持只翻转屏幕平面加速度`() {
        assertEquals(
            AccelerationAxes(2.0, -3.0, 4.0),
            mapLinearAccelerationForGrip("landscape", 2.0, 3.0, 4.0, 180),
        )
    }
}
