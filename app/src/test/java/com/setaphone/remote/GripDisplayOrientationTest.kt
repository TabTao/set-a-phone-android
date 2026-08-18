package com.setaphone.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class GripDisplayOrientationTest {
    @Test
    fun `竖持手机判定为竖屏且不旋转预览`() {
        val orientation = resolveGripDisplayOrientation(0.0)

        assertEquals("portrait", orientation.protocolValue)
    }

    @Test
    fun `向右横持手机判定为横屏且不旋转预览`() {
        val orientation = resolveGripDisplayOrientation(90.0)

        assertEquals("landscape", orientation.protocolValue)
    }

    @Test
    fun `向左横持手机判定为横屏且不旋转预览`() {
        val orientation = resolveGripDisplayOrientation(-90.0)

        assertEquals("landscape", orientation.protocolValue)
    }

    @Test
    fun `归零边界接近竖直时判定为竖屏`() {
        assertEquals("portrait", resolveGripDisplayOrientation(44.9).protocolValue)
        assertEquals("landscape", resolveGripDisplayOrientation(45.1).protocolValue)
    }
}
