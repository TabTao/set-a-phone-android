package com.setaphone.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingerXyhGestureTest {
    @Test
    fun `第二根手指落下时按左右位置冻结角色和基准`() {
        val gesture = TwoFingerXyhGesture()
        assertTrue(gesture.begin(listOf(
            TouchPoint(7, 600f, 700f),
            TouchPoint(3, 120f, 400f),
        )))

        val displacement = gesture.update(listOf(
            TouchPoint(7, 660f, 650f),
            TouchPoint(3, 40f, 460f),
        ))

        assertEquals(-60f, displacement?.x)
        assertEquals(-50f, displacement?.y)
        assertEquals(-60f, displacement?.h)
    }

    @Test
    fun `单指或第三根手指不能建立双指会话`() {
        val gesture = TwoFingerXyhGesture()

        assertFalse(gesture.begin(listOf(TouchPoint(1, 1f, 1f))))
        assertNull(gesture.update(listOf(TouchPoint(1, 1f, 1f))))
        assertFalse(gesture.begin(listOf(
            TouchPoint(1, 1f, 1f),
            TouchPoint(2, 2f, 2f),
            TouchPoint(3, 3f, 3f),
        )))
    }

    @Test
    fun `结束会话后不再产生XYH位移`() {
        val gesture = TwoFingerXyhGesture()
        gesture.begin(listOf(TouchPoint(1, 10f, 10f), TouchPoint(2, 20f, 20f)))

        assertTrue(gesture.end())
        assertFalse(gesture.active)
        assertNull(gesture.update(listOf(TouchPoint(1, 0f, 0f), TouchPoint(2, 0f, 0f))))
    }
}
