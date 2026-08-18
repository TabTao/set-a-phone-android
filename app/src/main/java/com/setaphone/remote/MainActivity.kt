package com.setaphone.remote

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.BitmapFactory
import android.graphics.Matrix
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity(), SensorEventListener {
    private val sender = Executors.newSingleThreadExecutor()
    private val previewReceiver = Executors.newSingleThreadExecutor()
    private val previewRunning = AtomicBoolean(false)
    private val previewGeneration = AtomicLong(0)
    private val pendingPose = AtomicReference<ByteArray?>(null)
    private val poseSendScheduled = AtomicBoolean(false)
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var linearAccelerationSensor: Sensor? = null
    private lateinit var hostInput: EditText
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var cameraPreview: ImageView
    private lateinit var menuOptions: View
    private lateinit var adjustmentPanel: View
    private lateinit var previewModeButton: Button
    private var previewPath = "camera.jpg"
    private var showingScene = false
    private var connected = false
    private var host = ""
    private var rotationScale = 0.2
    private var targetAddress: InetAddress? = null
    private var udpSocket: DatagramSocket? = null
    private var lastPoseAtNanos = 0L
    private var poseSequence = 0L
    private var lastAccelerationAtNanos = 0L
    private val motionPacketGate = MotionPacketGate()
    private var poseReferenceMatrix: FloatArray? = null
    private var latestAlignedMatrix: FloatArray? = null
    private var calibrationFramesRemaining = 0
    @Volatile private var gripOrientation = "portrait"
    @Volatile private var previewRotationDegrees = 0f
    private var pendingInitialCalibration = false

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
        cameraPreview = findViewById(R.id.cameraPreview)
        menuOptions = findViewById(R.id.menuOptions)
        adjustmentPanel = findViewById(R.id.adjustmentPanel)
        previewModeButton = findViewById(R.id.previewModeButton)
        hostInput.setText(getPreferences(MODE_PRIVATE).getString("host", ""))

        connectButton.setOnClickListener { toggleConnection() }
        findViewById<Button>(R.id.shutterButton).setOnClickListener { sendButton("shutter", "tap") }
        calibrateButton.setOnClickListener { calibratePose() }
        findViewById<View>(R.id.menuButton).setOnClickListener {
            if (menuOptions.visibility == View.VISIBLE) {
                menuOptions.animate().translationXBy(slideDistance()).alphaBy(-1f)
                    .withEndAction { menuOptions.visibility = View.GONE }.start()
                adjustmentPanel.visibility = View.GONE
            } else {
                menuOptions.translationX = slideDistance()
                menuOptions.alpha = 0f
                menuOptions.visibility = View.VISIBLE
                menuOptions.animate().translationXBy(-slideDistance()).alphaBy(1f).setDuration(180).start()
            }
        }
        findViewById<Button>(R.id.multiplierButton).setOnClickListener { adjustmentPanel.visibility = View.VISIBLE }
        findViewById<Button>(R.id.placeholderButton).setOnClickListener { adjustmentPanel.visibility = View.GONE }
        previewModeButton.setOnClickListener { togglePreviewSource() }
        findViewById<SeekBar>(R.id.pitchScale).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                rotationScale = 0.01 + value / 100.0
                findViewById<TextView>(R.id.scaleText).text = "倍率 %.2fx".format(rotationScale)
                if (fromUser) send(JSONObject().put("type", "slider").put("value", rotationScale))
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun togglePreviewSource() {
        showingScene = !showingScene
        previewPath = if (showingScene) "scene.jpg" else "camera.jpg"
        previewModeButton.text = if (showingScene) "相机" else "场景"
        if (connected) cameraPreview.post { startPreview() }
    }

    private fun toggleConnection() {
        if (connected) {
            send(JSONObject().put("type", "focus").put("active", false))
            connected = false
            latestAlignedMatrix = null
            poseReferenceMatrix = null
            calibrationFramesRemaining = 0
            pendingInitialCalibration = false
            motionPacketGate.reset()
            stopPreview()
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
                sendBytes(payload.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    // 姿态只保留最新值，避免传感器频率高时排队发送旧姿态。
    private fun sendPose(payload: JSONObject) {
        if (!connected || host.isBlank()) return
        pendingPose.set(payload.toString().toByteArray(Charsets.UTF_8))
        if (!poseSendScheduled.compareAndSet(false, true)) return
        sender.execute { drainPoses() }
    }

    private fun drainPoses() {
        while (true) {
            val bytes = pendingPose.getAndSet(null) ?: break
            runCatching { sendBytes(bytes) }
        }
        poseSendScheduled.set(false)
        if (pendingPose.get() != null && poseSendScheduled.compareAndSet(false, true)) {
            sender.execute { drainPoses() }
        }
    }

    private fun sendBytes(bytes: ByteArray) {
        val address = targetAddress ?: InetAddress.getByName(host).also { targetAddress = it }
        val socket = udpSocket ?: DatagramSocket().also { udpSocket = it }
        socket.send(DatagramPacket(bytes, bytes.size, address, 18888))
    }

    private fun startPreview() {
        stopPreview()
        if (!connected || host.isBlank()) return
        val generation = previewGeneration.incrementAndGet()
        previewRunning.set(true)
        val shortSide = minOf(cameraPreview.width, cameraPreview.height).coerceAtLeast(1)
        previewReceiver.execute {
            while (previewRunning.get() && previewGeneration.get() == generation && connected) {
                var connection: HttpURLConnection? = null
                try {
                    val endpoint = URL("http://$host:18889/$previewPath?shortSide=$shortSide")
                    connection = endpoint.openConnection() as HttpURLConnection
                    connection.connectTimeout = 1_200
                    connection.readTimeout = 1_800
                    connection.useCaches = false
                    connection.inputStream.use { stream ->
                        val bitmap = transformPreview(BitmapFactory.decodeStream(stream), shortSide)
                        if (bitmap != null && previewRunning.get() && previewGeneration.get() == generation) {
                            runOnUiThread {
                                if (previewRunning.get() && previewGeneration.get() == generation) {
                                    cameraPreview.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 预览和控制通道分离，单帧失败不影响姿态与按键发送。
                } finally {
                    connection?.disconnect()
                }
                try {
                    Thread.sleep(80)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private fun stopPreview() {
        previewRunning.set(false)
        previewGeneration.incrementAndGet()
    }

    private fun transformPreview(bitmap: Bitmap?, requestedShortSide: Int): Bitmap? {
        if (bitmap == null || requestedShortSide <= 0) return bitmap
        val sourceShortSide = minOf(bitmap.width, bitmap.height)
        val scale = if (sourceShortSide > requestedShortSide) {
            requestedShortSide.toFloat() / sourceShortSide.toFloat()
        } else {
            1f
        }
        val rotation = previewRotationDegrees
        if (scale == 1f && rotation == 0f) return bitmap
        val matrix = Matrix().apply {
            postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun slideDistance(): Float = 220f * resources.displayMetrics.density

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            if (connected) sendVerticalAcceleration(event)
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
        if (connected && pendingInitialCalibration) {
            pendingInitialCalibration = false
            calibratePose()
        }
        if (!connected) return
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
        val pose = PoseAngles(pitch, yaw, roll)
        when (motionPacketGate.next(pose, now, calibrating)) {
            MotionPacketKind.POSE -> {
                val sentPose = PoseAngles(roundPose(pitch), roundPose(yaw), roundPose(roll))
                sendPose(
                    JSONObject().put("type", "pose").put("pitch", sentPose.pitch)
                        .put("yaw", sentPose.yaw).put("roll", sentPose.roll).put("orientation", gripOrientation)
                        .put("sequence", ++poseSequence).put("sensorNanos", now)
                        .put("calibrate", calibrating)
                )
            }
            MotionPacketKind.HEARTBEAT -> send(JSONObject().put("type", "heartbeat"))
            MotionPacketKind.NONE -> Unit
        }
    }

    private fun roundPose(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private fun sendVerticalAcceleration(event: SensorEvent) {
        val now = System.nanoTime()
        if (now - lastAccelerationAtNanos < 16_000_000L) return
        lastAccelerationAtNanos = now
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val (screenX, screenY) = when (rotation) {
            Surface.ROTATION_90 -> event.values[1] to -event.values[0]
            Surface.ROTATION_270 -> -event.values[1] to event.values[0]
            else -> event.values[0] to event.values[1]
        }
        val z = event.values[2]
        if (maxOf(kotlin.math.abs(screenX), kotlin.math.abs(screenY), kotlin.math.abs(z)) < ACCELERATION_SEND_THRESHOLD) return
        // 协议 X 固定表示手机抬举，Y 表示左右平移。
        send(JSONObject().put("type", "acceleration").put("x", screenY).put("y", screenX).put("z", z))
    }

    private fun calibratePose() {
        val currentMatrix = latestAlignedMatrix
        if (currentMatrix == null) {
            pendingInitialCalibration = true
            if (connected) statusText.text = "正在等待姿态归零"
            return
        }
        val grip = detectGripOrientation(currentMatrix)
        gripOrientation = grip.protocolValue
        previewRotationDegrees = grip.previewRotationDegrees
        poseReferenceMatrix = currentMatrix.copyOf()
        calibrationFramesRemaining = 3
        motionPacketGate.reset()
        if (connected) {
            send(JSONObject().put("type", "calibrate").put("orientation", gripOrientation))
            statusText.text = "已请求配对归零"
            cameraPreview.post { startPreview() }
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccelerationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (connected) {
            pendingInitialCalibration = true
        }
    }

    private fun detectGripOrientation(matrix: FloatArray): GripDisplayOrientation {
        val angles = FloatArray(3)
        SensorManager.getOrientation(matrix, angles)
        val rollDegrees = Math.toDegrees(angles[2].toDouble())
        return resolveGripDisplayOrientation(rollDegrees)
    }
    override fun onPause() { stopPreview(); poseReferenceMatrix = null; calibrationFramesRemaining = 0; pendingInitialCalibration = false; motionPacketGate.reset(); if (connected) send(JSONObject().put("type", "focus").put("active", false)); sensorManager.unregisterListener(this); super.onPause() }
    override fun onDestroy() { stopPreview(); udpSocket?.close(); sender.shutdownNow(); previewReceiver.shutdownNow(); super.onDestroy() }

    companion object {
        private const val ACCELERATION_SEND_THRESHOLD = 0.3f
        private val IDENTITY_ROTATION = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}
