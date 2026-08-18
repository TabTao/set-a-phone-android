package com.setaphone.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class GripDisplayOrientationTest {
    @Test
    fun `竖持手机保持竖屏采样且不旋转预览`() {
        val orientation = resolveGripDisplayOrientation(0.0)

        assertEquals("portrait", orientation.protocolValue)
        assertEquals(0f, orientation.previewRotationDegrees, 0f)
    }

    @Test
    fun `向右横持手机使用横屏采样并顺时针旋转预览`() {
        val orientation = resolveGripDisplayOrientation(90.0)

        assertEquals("landscape", orientation.protocolValue)
        assertEquals(90f, orientation.previewRotationDegrees, 0f)
    }

    @Test
    fun `向左横持手机使用横屏采样并逆时针旋转预览`() {
        val orientation = resolveGripDisplayOrientation(-90.0)

        assertEquals("landscape", orientation.protocolValue)
        assertEquals(-90f, orientation.previewRotationDegrees, 0f)
    }
}
