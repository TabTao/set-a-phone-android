package com.setaphone.remote

import android.app.Activity
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import org.json.JSONObject
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** 仅负责 GL 线程的 ARCore 更新；失败时调用者继续使用惯性平移。 */
class ArTracker(private val activity: Activity, private val send: (JSONObject) -> Unit) : GLSurfaceView.Renderer {
    enum class StartResult { STARTED, INSTALL_REQUESTED, UNAVAILABLE }

    private var session: Session? = null
    private var textureId = 0
    private var origin: Pose? = null
    private var tracking = false
    private var lastSentAtNanos = 0L

    fun start(allowInstallPrompt: Boolean): StartResult = runCatching {
        when (ArCoreApk.getInstance().requestInstall(activity, allowInstallPrompt)) {
            ArCoreApk.InstallStatus.INSTALLED -> {
                session = Session(activity)
                StartResult.STARTED
            }
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> StartResult.INSTALL_REQUESTED
        }
    }.getOrDefault(StartResult.UNAVAILABLE)

    fun resume(): Boolean = runCatching { session?.resume(); session != null }.getOrDefault(false)
    fun pause() = runCatching { session?.pause() }.getOrDefault(Unit)
    fun close() { runCatching { session?.close() }; session = null }
    fun reset() { origin = null; tracking = false; lastSentAtNanos = 0L }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        session?.setCameraTextureName(textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        @Suppress("DEPRECATION")
        session?.setDisplayGeometry(activity.windowManager.defaultDisplay.rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val active = session ?: return
        val frame = runCatching { active.update() }.getOrNull() ?: return
        val camera = frame.camera
        val pose = camera.pose
        if (camera.trackingState != TrackingState.TRACKING) {
            if (tracking) send(JSONObject().put("type", "translation").put("tracking", false))
            tracking = false; origin = null
            return
        }
        val start = origin ?: pose.also { origin = it }
        tracking = true
        val now = System.nanoTime()
        if (now - lastSentAtNanos < 33_000_000L) return
        lastSentAtNanos = now
        val relative = start.inverse().compose(pose).translation
        // ARCore 相机坐标：X 向右、Y 向上、前进为负 Z。
        send(JSONObject().put("type", "translation").put("x", relative[0])
            .put("y", relative[1]).put("z", relative[2]).put("tracking", "arcore"))
    }
}
