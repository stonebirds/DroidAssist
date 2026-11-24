package com.leefly.droidassist.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class RemoteControlService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float): Boolean = gesture(path(x, y), 1, 80)

    fun longPress(x: Float, y: Float, durationMillis: Long = 600): Boolean =
        gesture(path(x, y), 1, durationMillis.coerceIn(400, 2_000))

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long = 300): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(fromX.coerceIn(0f, 1f) * width, fromY.coerceIn(0f, 1f) * height)
            lineTo(toX.coerceIn(0f, 1f) * width, toY.coerceIn(0f, 1f) * height)
        }
        return gesture(path, 0, durationMillis.coerceIn(100, 2_000))
    }

    fun global(action: GlobalAction): Boolean = performGlobalAction(
        when (action) {
            GlobalAction.BACK -> GLOBAL_ACTION_BACK
            GlobalAction.HOME -> GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
        }
    )

    private fun path(x: Float, y: Float) = Path().apply {
        moveTo(x.coerceIn(0f, 1f) * resources.displayMetrics.widthPixels, y.coerceIn(0f, 1f) * resources.displayMetrics.heightPixels)
    }

    private fun gesture(path: Path, start: Long, duration: Long): Boolean = dispatchGesture(
        GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, start, duration)).build(), null, null,
    )

    companion object {
        @Volatile private var instance: RemoteControlService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        val isConnected: Boolean get() = instance != null

        fun execute(commandJson: String) {
            mainHandler.post {
                val service = instance ?: return@post
                val command = runCatching { JSONObject(commandJson) }.getOrNull() ?: return@post
                when (command.optString("type")) {
                    "tap" -> service.tap(command.x("x"), command.x("y"))
                    "long_press" -> service.longPress(command.x("x"), command.x("y"), command.optLong("duration", 600))
                    "swipe" -> service.swipe(
                        command.x("fromX"), command.x("fromY"), command.x("toX"), command.x("toY"),
                        command.optLong("duration", 300),
                    )
                    "global" -> runCatching { GlobalAction.valueOf(command.optString("action")) }
                        .getOrNull()?.let(service::global)
                }
            }
        }

        private fun JSONObject.x(name: String) = optDouble(name, 0.0).toFloat().coerceIn(0f, 1f)
    }
}

enum class GlobalAction { BACK, HOME, RECENTS }
