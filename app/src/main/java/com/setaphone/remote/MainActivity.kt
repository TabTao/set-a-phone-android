package com.setaphone.remote

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
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
    private lateinit var hostInput: EditText
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var leftButtons: View
    private lateinit var rightButtons: View
    private var connected = false
    private var host = ""
    private var pitchScale = 1.0
    private var targetAddress: InetAddress? = null
    private var udpSocket: DatagramSocket? = null
    private var lastPoseAtNanos = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        hostInput = findViewById(R.id.hostInput)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        leftButtons = findViewById(R.id.leftButtons)
        rightButtons = findViewById(R.id.rightButtons)
        hostInput.setText(getPreferences(MODE_PRIVATE).getString("host", ""))

        connectButton.setOnClickListener { toggleConnection() }
        bindFunction(R.id.left1, "left1"); bindFunction(R.id.left2, "left2"); bindFunction(R.id.left3, "left3")
        bindFunction(R.id.right1, "right1"); bindFunction(R.id.right2, "right2"); bindFunction(R.id.right3, "right3")
        findViewById<Button>(R.id.shutterButton).setOnClickListener { sendButton("shutter", "tap") }
        findViewById<SeekBar>(R.id.pitchScale).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                pitchScale = 0.2 + value / 10.0
                findViewById<TextView>(R.id.scaleText).text = "俯仰比例 %.1fx".format(pitchScale)
                if (fromUser) send(JSONObject().put("type", "slider").put("value", pitchScale))
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        refreshGripButtons()
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
        send(JSONObject().put("type", "slider").put("value", pitchScale))
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
        if (!connected || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val now = System.nanoTime()
        if (now - lastPoseAtNanos < 16_000_000L) return
        lastPoseAtNanos = now
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        SensorManager.getOrientation(matrix, orientation)
        val pitch = Math.toDegrees(orientation[1].toDouble())
        val yaw = Math.toDegrees(orientation[0].toDouble())
        val grip = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        send(JSONObject().put("type", "pose").put("pitch", pitch).put("yaw", yaw).put("orientation", grip))
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); refreshGripButtons() }

    private fun refreshGripButtons() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        leftButtons.isEnabled = landscape
        rightButtons.isEnabled = !landscape
        setGroupEnabled(leftButtons, landscape)
        setGroupEnabled(rightButtons, !landscape)
    }

    private fun setGroupEnabled(group: View, enabled: Boolean) {
        if (group is android.view.ViewGroup) for (index in 0 until group.childCount) group.getChildAt(index).isEnabled = enabled
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onResume() { super.onResume(); rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }; refreshGripButtons() }
    override fun onPause() { if (connected) send(JSONObject().put("type", "focus").put("active", false)); sensorManager.unregisterListener(this); super.onPause() }
    override fun onDestroy() { udpSocket?.close(); sender.shutdownNow(); super.onDestroy() }
}
