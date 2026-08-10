package com.setaphone.remote

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.opengl.GLSurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class MainActivity : Activity(), SensorEventListener {
    private val sender = Executors.newSingleThreadExecutor()
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var linearAccelerationSensor: Sensor? = null
    private lateinit var hostInput: EditText
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var leftButtons: View
    private lateinit var rightButtons: View
    private var connected = false
    private var host = ""
    private var rotationScale = 0.2
    private var targetAddress: InetAddress? = null
    private var udpSocket: DatagramSocket? = null
    private var lastPoseAtNanos = 0L
    private var lastAccelerationAtNanos = 0L
    private var lastDisplayRotation = -1
    private var arTracker: ArTracker? = null
    private var arSurface: GLSurfaceView? = null
    private var arInstallPromptAllowed = true
    private var arUnavailable = false
    private var activityResumed = false
    private var arActive = false
    private var poseReferenceMatrix: FloatArray? = null
    private var latestAlignedMatrix: FloatArray? = null
    private var calibrationFramesRemaining = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        hostInput = findViewById(R.id.hostInput)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        calibrateButton = findViewById(R.id.calibrateButton)
        leftButtons = findViewById(R.id.leftButtons)
        rightButtons = findViewById(R.id.rightButtons)
        hostInput.setText(getPreferences(MODE_PRIVATE).getString("host", ""))

        connectButton.setOnClickListener { toggleConnection() }
        bindFunction(R.id.left1, "left1"); bindFunction(R.id.left2, "left2"); bindFunction(R.id.left3, "left3")
        bindFunction(R.id.right1, "right1"); bindFunction(R.id.right2, "right2"); bindFunction(R.id.right3, "right3")
        findViewById<Button>(R.id.shutterButton).setOnClickListener { sendButton("shutter", "tap") }
        calibrateButton.setOnClickListener { calibratePose() }
        findViewById<SeekBar>(R.id.pitchScale).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                rotationScale = 0.01 + value / 100.0
                findViewById<TextView>(R.id.scaleText).text = "姿态倍率 %.2fx".format(rotationScale)
                if (fromUser) send(JSONObject().put("type", "slider").put("value", rotationScale))
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        refreshGripButtons()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    private fun bindFunction(id: Int, name: String) {
        findViewById<Button>(id).apply {
            setOnClickListener { sendButton(name, "tap") }
            setOnLongClickListener { sendButton(name, "long"); true }
        }
    }

    private fun toggleConnection() {
        if (connected) {
            send(JSONObject().put("type", "focus").put("active", false))
            connected = false
            latestAlignedMatrix = null
            poseReferenceMatrix = null
            calibrationFramesRemaining = 0
            connectButton.text = "连接"
            statusText.text = "已断开"
            return
        }
        val value = hostInput.text.toString().trim()
        if (value.isBlank()) { statusText.text = "请输入电脑 IP"; return }
        host = value
        targetAddress = null
        getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
        connected = true
        connectButton.text = "断开"
        statusText.text = "已连接 $host:18888"
        calibratePose()
        send(JSONObject().put("type", "slider").put("value", rotationScale))
    }

    private fun sendButton(button: String, action: String) = send(
        JSONObject().put("type", "button").put("button", button).put("action", action)
    )

    private fun send(payload: JSONObject) {
        if (!connected || host.isBlank()) return
        sender.execute {
            runCatching {
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                val address = targetAddress ?: InetAddress.getByName(host).also { targetAddress = it }
                val socket = udpSocket ?: DatagramSocket().also { udpSocket = it }
                socket.send(DatagramPacket(bytes, bytes.size, address, 18888))
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!connected) return
        refreshGripButtons()
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            sendVerticalAcceleration(event)
            return
        }
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val now = System.nanoTime()
        if (now - lastPoseAtNanos < 16_000_000L) return
        lastPoseAtNanos = now
        val matrix = FloatArray(9)
        val aligned = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        @Suppress("DEPRECATION")
        when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, aligned)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, aligned)
            else -> matrix.copyInto(aligned)
        }
        latestAlignedMatrix = aligned.copyOf()
        val reference = poseReferenceMatrix
        val relative = if (reference == null) {
            poseReferenceMatrix = aligned.copyOf()
            IDENTITY_ROTATION.copyOf()
        } else {
            relativeRotation(reference, aligned)
        }
        SensorManager.getOrientation(relative, orientation)
        // 重映射后：X 轴是俯仰，Y 轴是水平转动，Z 轴是相机画面旋转。
        val calibrating = calibrationFramesRemaining > 0
        if (calibrating) calibrationFramesRemaining--
        val pitch = if (calibrating) 0.0 else Math.toDegrees(orientation[1].toDouble())
        val yaw = if (calibrating) 0.0 else Math.toDegrees(orientation[2].toDouble())
        val roll = if (calibrating) 0.0 else Math.toDegrees(orientation[0].toDouble())
        send(JSONObject().put("type", "pose").put("pitch", pitch).put("yaw", yaw).put("roll", roll).put("orientation", "landscape").put("calibrate", calibrating))
    }

    private fun sendVerticalAcceleration(event: SensorEvent) {
        val now = System.nanoTime()
        if (now - lastAccelerationAtNanos < 16_000_000L) return
        lastAccelerationAtNanos = now
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> event.values[1] to -event.values[0]
            Surface.ROTATION_270 -> -event.values[1] to event.values[0]
            else -> event.values[0] to event.values[1]
        }
        send(JSONObject().put("type", "acceleration").put("x", x).put("y", y).put("z", event.values[2]))
    }

    private fun calibratePose() {
        poseReferenceMatrix = latestAlignedMatrix?.copyOf()
        calibrationFramesRemaining = 3
        arTracker?.reset()
        if (connected) {
            send(JSONObject().put("type", "calibrate"))
            statusText.text = "已请求配对归零"
        }
    }

    private fun relativeRotation(reference: FloatArray, current: FloatArray): FloatArray {
        val result = FloatArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                var value = 0f
                for (index in 0..2) {
                    value += reference[index * 3 + row] * current[index * 3 + column]
                }
                result[row * 3 + column] = value
            }
        }
        return result
    }

    private fun refreshGripButtons() {
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        if (rotation == lastDisplayRotation) return
        lastDisplayRotation = rotation
        val useLeft = rotation != Surface.ROTATION_270
        leftButtons.isEnabled = useLeft
        rightButtons.isEnabled = !useLeft
        setGroupEnabled(leftButtons, useLeft)
        setGroupEnabled(rightButtons, !useLeft)
    }

    private fun setGroupEnabled(group: View, enabled: Boolean) {
        if (group is android.view.ViewGroup) for (index in 0 until group.childCount) group.getChildAt(index).isEnabled = enabled
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onResume() {
        super.onResume()
        activityResumed = true
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccelerationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && arTracker == null && !arUnavailable) setupArTracking()
        resumeArTracking()
        refreshGripButtons()
    }
    override fun onPause() { activityResumed = false; poseReferenceMatrix = null; calibrationFramesRemaining = 0; if (connected) send(JSONObject().put("type", "focus").put("active", false)); pauseArTracking(); sensorManager.unregisterListener(this); super.onPause() }
    override fun onDestroy() { arTracker?.close(); udpSocket?.close(); sender.shutdownNow(); super.onDestroy() }

    private fun setupArTracking() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }
        val tracker = arTracker ?: ArTracker(this, ::send)
        when (tracker.start(arInstallPromptAllowed)) {
            ArTracker.StartResult.INSTALL_REQUESTED -> {
                arInstallPromptAllowed = false
                statusText.text = "正在安装 ARCore"
                return
            }
            ArTracker.StartResult.UNAVAILABLE -> {
                arUnavailable = true
                statusText.text = "惯性平移"
                return
            }
            ArTracker.StartResult.STARTED -> Unit
        }
        arTracker = tracker
        arSurface = GLSurfaceView(this).also { surface ->
            surface.setEGLContextClientVersion(2)
            surface.setRenderer(tracker)
            surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            surface.alpha = 0.01f
            addContentView(surface, ViewGroup.LayoutParams(1, 1))
        }
        statusText.text = "AR 平移"
    }

    private fun resumeArTracking() {
        if (!activityResumed || arActive) return
        val tracker = arTracker ?: return
        if (!tracker.resume()) {
            tracker.close()
            arTracker = null
            arUnavailable = true
            statusText.text = "惯性平移"
            return
        }
        arSurface?.onResume()
        arActive = true
    }

    private fun pauseArTracking() {
        if (!arActive) return
        arSurface?.onPause()
        arTracker?.pause()
        arActive = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == CAMERA_PERMISSION_REQUEST && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            setupArTracking()
            resumeArTracking()
        }
        else if (requestCode == CAMERA_PERMISSION_REQUEST) statusText.text = "惯性平移"
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 21
        private val IDENTITY_ROTATION = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}
