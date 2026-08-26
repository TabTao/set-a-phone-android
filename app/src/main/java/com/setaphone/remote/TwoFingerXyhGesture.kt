package com.setaphone.remote

data class TouchPoint(val id: Int, val x: Float, val y: Float)

data class XyhTouchDisplacement(val x: Float, val y: Float, val h: Float)

/**
 * 记录一次双指 XYH 操作。第二根手指落下时决定左右角色，之后角色和基准均保持不变。
 */
class TwoFingerXyhGesture {
    private var leftStart: TouchPoint? = null
    private var rightStart: TouchPoint? = null

    val active: Boolean
        get() = leftStart != null && rightStart != null

    fun begin(points: List<TouchPoint>): Boolean {
        if (points.size != 2) {
            end()
            return false
        }
        val ordered = points.sortedWith(compareBy<TouchPoint> { it.x }.thenBy { it.id })
        leftStart = ordered[0]
        rightStart = ordered[1]
        return true
    }

    fun update(points: List<TouchPoint>): XyhTouchDisplacement? {
        val left = leftStart ?: return null
        val right = rightStart ?: return null
        val pointsById = points.associateBy { it.id }
        val currentLeft = pointsById[left.id] ?: return null
        val currentRight = pointsById[right.id] ?: return null
        return XyhTouchDisplacement(
            x = right.x - currentRight.x,
            y = currentRight.y - right.y,
            h = left.y - currentLeft.y,
        )
    }

    fun end(): Boolean {
        val wasActive = active
        leftStart = null
        rightStart = null
        return wasActive
    }
}
