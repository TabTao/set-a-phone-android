package com.setaphone.remote

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Surface
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.BitmapFactory
import org.json.JSONArray
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
    private val pendingSensorSample = AtomicReference<ByteArray?>(null)
    private val sensorSampleSendScheduled = AtomicBoolean(false)
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
    private lateinit var previewModeButton: ImageButton
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
    private var latestRawAlignedMatrix: FloatArray? = null
    private var gripCoordinateCorrection180 = false
    private var calibrationFramesRemaining = 0
    @Volatile private var gripOrientation = "portrait"
    private var pendingInitialCalibration = false
    private var diagnosticMode = false
    private var sensorSampleSequence = 0L
    private var previousDeviceEuler: FloatArray? = null
    private var previousDisplayEuler: FloatArray? = null

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
        findViewById<View>(R.id.menuButton).setOnClickListener { toggleMenuOptions() }
        findViewById<ImageButton>(R.id.closeMenuButton).setOnClickListener { hideMenuOptions() }
        findViewById<ImageButton>(R.id.multiplierButton).setOnClickListener { adjustmentPanel.visibility = View.VISIBLE }
        findViewById<Button>(R.id.motionHoldButton).setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isSelected = true
                    sendButton("fn3", "down")
                    statusText.text = "已按住移动"
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isSelected = false
                    sendButton("fn3", "up")
                    if (connected) statusText.text = "已连接 $host:18888"
                    true
                }
                else -> true
            }
        }
        val diagnosticButton = findViewById<ImageButton>(R.id.diagnosticButton)
        diagnosticButton.setOnClickListener {
            diagnosticMode = !diagnosticMode
            diagnosticButton.isSelected = diagnosticMode
            diagnosticButton.contentDescription = if (diagnosticMode) "关闭诊断" else "诊断"
            statusText.text = if (diagnosticMode) "姿态原始数据诊断已开启" else "姿态诊断已关闭"
            if (!diagnosticMode) {
                pendingSensorSample.set(null)
                previousDeviceEuler = null
                previousDisplayEuler = null
            }
            send(JSONObject().put("type", "diagnostic_status").put("enabled", diagnosticMode))
        }
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
        previewModeButton.setImageResource(if (showingScene) R.drawable.ic_camera else R.drawable.ic_scene)
        previewModeButton.contentDescription = if (showingScene) "切换到相机" else "切换到场景"
        if (connected) cameraPreview.post { startPreview() }
    }

    private fun toggleMenuOptions() {
        if (menuOptions.visibility == View.VISIBLE) hideMenuOptions() else showMenuOptions()
    }

    private fun showMenuOptions() {
        menuOptions.translationX = slideDistance()
        menuOptions.alpha = 0f
        menuOptions.visibility = View.VISIBLE
        menuOptions.animate().translationXBy(-slideDistance()).alphaBy(1f).setDuration(180).start()
    }

    private fun hideMenuOptions() {
        if (menuOptions.visibility != View.VISIBLE) return
        menuOptions.animate().translationXBy(slideDistance()).alphaBy(-1f)
            .withEndAction { menuOptions.visibility = View.GONE }.start()
        adjustmentPanel.visibility = View.GONE
    }

    private fun toggleConnection() {
        if (connected) {
            send(JSONObject().put("type", "focus").put("active", false))
            connected = false
            latestAlignedMatrix = null
            latestRawAlignedMatrix = null
            poseReferenceMatrix = null
            gripCoordinateCorrection180 = false
            calibrationFramesRemaining = 0
            pendingInitialCalibration = false
            previousDeviceEuler = null
            previousDisplayEuler = null
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
        statusText.text = if (linearAccelerationSensor == null) {
            "已连接 $host:18888（设备不支持线性加速度）"
        } else {
            "已连接 $host:18888"
        }
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

    // 诊断样本也只保留最新值，不能挤占正常姿态控制包。
    private fun sendSensorSample(payload: JSONObject) {
        if (!connected || !diagnosticMode || host.isBlank()) return
        pendingSensorSample.set(payload.toString().toByteArray(Charsets.UTF_8))
        if (!sensorSampleSendScheduled.compareAndSet(false, true)) return
        sender.execute { drainSensorSamples() }
    }

    private fun drainSensorSamples() {
        while (true) {
            val bytes = pendingSensorSample.getAndSet(null) ?: break
            runCatching { sendBytes(bytes) }
        }
        sensorSampleSendScheduled.set(false)
        if (pendingSensorSample.get() != null && sensorSampleSendScheduled.compareAndSet(false, true)) {
            sender.execute { drainSensorSamples() }
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
        if (scale == 1f) return bitmap
        val matrix = Matrix().apply {
            postScale(scale, scale)
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
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        @Suppress("DEPRECATION")
        when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, aligned)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, aligned)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, aligned)
            else -> matrix.copyInto(aligned)
        }
        if (connected && diagnosticMode) {
            val rawOrientation = FloatArray(3)
            val displayOrientation = FloatArray(3)
            val quaternion = FloatArray(4)
            SensorManager.getOrientation(matrix, rawOrientation)
            SensorManager.getOrientation(aligned, displayOrientation)
            SensorManager.getQuaternionFromVector(quaternion, event.values)
            val deviceEulerDelta = eulerDelta(previousDeviceEuler, rawOrientation)
            val displayEulerDelta = eulerDelta(previousDisplayEuler, displayOrientation)
            previousDeviceEuler = rawOrientation.copyOf()
            previousDisplayEuler = displayOrientation.copyOf()
            @Suppress("DEPRECATION")
            val displayRotation = windowManager.defaultDisplay.rotation
            sendSensorSample(
                JSONObject().put("type", "sensor_sample")
                    .put("sequence", ++sensorSampleSequence)
                    .put("sensorNanos", event.timestamp)
                    .put("displayRotation", displayRotation)
                    .put("rotationVector", JSONArray(event.values.map { it.toDouble() }))
                    .put("quaternion", JSONArray(quaternion.map { it.toDouble() }))
                    .put("deviceMatrix", JSONArray(matrix.map { it.toDouble() }))
                    .put("displayMatrix", JSONArray(aligned.map { it.toDouble() }))
                    .put("deviceEuler", JSONArray(rawOrientation.map { Math.toDegrees(it.toDouble()) }))
                    .put("displayEuler", JSONArray(displayOrientation.map { Math.toDegrees(it.toDouble()) }))
                    .put("deviceEulerDelta", JSONArray(deviceEulerDelta.map { Math.toDegrees(it.toDouble()) }))
                    .put("displayEulerDelta", JSONArray(displayEulerDelta.map { Math.toDegrees(it.toDouble()) }))
            )
        }
        latestRawAlignedMatrix = aligned.copyOf()
        if (connected && pendingInitialCalibration) {
            pendingInitialCalibration = false
            calibratePose(aligned)
        }
        if (!connected) return
        val canonicalAligned = applyGripCorrection(aligned, gripCoordinateCorrection180)
        latestAlignedMatrix = canonicalAligned.copyOf()
        val reference = poseReferenceMatrix
        val relative = if (reference == null) {
            poseReferenceMatrix = canonicalAligned.copyOf()
            IDENTITY_ROTATION.copyOf()
        } else {
            relativeRotation(reference, canonicalAligned)
        }
        val calibrating = calibrationFramesRemaining > 0
        if (calibrating) calibrationFramesRemaining--
        val deviceRotation = if (calibrating) {
            DeviceRotationDegrees(0.0, 0.0, 0.0)
        } else {
            rotationVectorDegrees(relative)
        }
        val pose = mapDeviceRotationForGrip(gripOrientation, deviceRotation)
        when (motionPacketGate.next(pose, now, calibrating)) {
            MotionPacketKind.POSE -> {
                val sentPose = PoseAngles(roundPose(pose.pitch), roundPose(pose.yaw), roundPose(pose.roll))
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

    private fun eulerDelta(previous: FloatArray?, current: FloatArray): FloatArray {
        if (previous == null) return floatArrayOf(0f, 0f, 0f)
        return FloatArray(3) { index ->
            var delta = current[index].toDouble() - previous[index].toDouble()
            while (delta > Math.PI) delta -= Math.PI * 2.0
            while (delta < -Math.PI) delta += Math.PI * 2.0
            delta.toFloat()
        }
    }

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

    private fun calibratePose(currentRawMatrix: FloatArray? = latestRawAlignedMatrix) {
        val currentMatrix = currentRawMatrix
        if (currentMatrix == null) {
            pendingInitialCalibration = true
            if (connected) statusText.text = "正在等待姿态归零"
            return
        }
        val grip = detectGripOrientation(currentMatrix)
        gripOrientation = grip.protocolValue
        gripCoordinateCorrection180 = grip.coordinateCorrectionDegrees == 180
        val canonicalMatrix = applyGripCorrection(currentMatrix, gripCoordinateCorrection180)
        latestAlignedMatrix = canonicalMatrix.copyOf()
        poseReferenceMatrix = canonicalMatrix.copyOf()
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
        // matrix[6]/matrix[7] 是设备 X/Y 轴在世界竖直方向上的投影。
        return resolveGripDisplayOrientation(matrix[6].toDouble(), matrix[7].toDouble())
    }
    private fun applyGripCorrection(matrix: FloatArray, correction180: Boolean): FloatArray {
        if (!correction180) return matrix.copyOf()
        val corrected = FloatArray(9)
        // 绕显示坐标 Z 轴旋转 180°，将反向握持统一到按钮标准位置。
        SensorManager.remapCoordinateSystem(
            matrix,
            SensorManager.AXIS_MINUS_X,
            SensorManager.AXIS_MINUS_Y,
            corrected,
        )
        return corrected
    }
    override fun onPause() { stopPreview(); poseReferenceMatrix = null; latestAlignedMatrix = null; latestRawAlignedMatrix = null; gripCoordinateCorrection180 = false; calibrationFramesRemaining = 0; pendingInitialCalibration = false; previousDeviceEuler = null; previousDisplayEuler = null; motionPacketGate.reset(); if (connected) send(JSONObject().put("type", "focus").put("active", false)); sensorManager.unregisterListener(this); super.onPause() }
    override fun onDestroy() { stopPreview(); udpSocket?.close(); sender.shutdownNow(); previewReceiver.shutdownNow(); super.onDestroy() }

    companion object {
        private const val ACCELERATION_SEND_THRESHOLD = 0.3f
        private val IDENTITY_ROTATION = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}
