package com.setaphone.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class GripDisplayOrientationTest {
    @Test
    fun `竖持手机判定为竖屏且不旋转预览`() {
        val orientation = resolveGripDisplayOrientation(0.0)

        assertEquals(GripDisplayOrientation("portrait", 0), orientation)
    }

    @Test
    fun `向右横持手机判定为横屏且不旋转预览`() {
        val orientation = resolveGripDisplayOrientation(90.0)

        assertEquals(GripDisplayOrientation("landscape", 0), orientation)
    }

    @Test
    fun `向左横持手机判定为横屏且不旋转预览`() {
        val orientation = resolveGripDisplayOrientation(-90.0)

        assertEquals(GripDisplayOrientation("landscape", 180), orientation)
    }

    @Test
    fun `归零边界接近竖直时判定为竖屏`() {
        assertEquals("portrait", resolveGripDisplayOrientation(74.9).protocolValue)
        assertEquals("landscape", resolveGripDisplayOrientation(75.1).protocolValue)
        assertEquals("portrait", resolveGripDisplayOrientation(180.0).protocolValue)
        assertEquals(180, resolveGripDisplayOrientation(180.0).coordinateCorrectionDegrees)
    }

    @Test
    fun `角度归一化覆盖反向横竖握持`() {
        assertEquals(GripDisplayOrientation("portrait", 180), resolveGripDisplayOrientation(-180.0))
        assertEquals(GripDisplayOrientation("landscape", 0), resolveGripDisplayOrientation(450.0))
        assertEquals(GripDisplayOrientation("landscape", 180), resolveGripDisplayOrientation(-450.0))
    }

    @Test
    fun `横竖握姿分别转换协议三轴并反向校正滚转`() {
        assertEquals(PoseAngles(2.0, -3.0, -4.0), mapRelativePoseForGrip("portrait", 2.0, 3.0, 4.0))
        assertEquals(PoseAngles(3.0, 2.0, -4.0), mapRelativePoseForGrip("landscape", 2.0, 3.0, 4.0))
    }
}
