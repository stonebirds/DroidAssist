package com.leefly.droidassist.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.leefly.droidassist.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class ControlExecutionResult(
    val commandType: String,
    val success: Boolean,
    val reason: String? = null,
    val action: String? = null,
)

class RemoteControlService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        DiagnosticLog.initialize(this)
        instance = this
        _connectionState.value = true
        DiagnosticLog.info("Control", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() {
        DiagnosticLog.warn("Control", "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearConnection("unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearConnection("destroyed")
        super.onDestroy()
    }

    private fun clearConnection(reason: String) {
        if (instance !== this) return
        instance = null
        _connectionState.value = false
        DiagnosticLog.warn("Control", "Accessibility service disconnected ($reason)")
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
        private val _connectionState = MutableStateFlow(false)
        val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

        val isConnected: Boolean get() = instance != null

        fun execute(commandJson: String, onResult: (ControlExecutionResult) -> Unit = {}) {
            val command = runCatching { JSONObject(commandJson) }.getOrNull()
            if (command == null) {
                DiagnosticLog.warn("Control", "Rejected malformed control command")
                onResult(ControlExecutionResult("unknown", false, "控制指令格式无效"))
                return
            }
            val commandType = command.optString("type").ifBlank { "unknown" }
            val action = command.optString("action").takeIf { commandType == "global" && it.isNotBlank() }
            mainHandler.post {
                val service = instance
                if (service == null) {
                    DiagnosticLog.warn("Control", "Dropped $commandType command: accessibility service is not connected")
                    onResult(ControlExecutionResult(commandType, false, "被协助设备的无障碍服务未连接", action))
                    return@post
                }
                val success = when (commandType) {
                    "tap" -> service.tap(command.x("x"), command.x("y"))
                    "long_press" -> service.longPress(command.x("x"), command.x("y"), command.optLong("duration", 600))
                    "swipe" -> service.swipe(
                        command.x("fromX"), command.x("fromY"), command.x("toX"), command.x("toY"),
                        command.optLong("duration", 300),
                    )
                    "global" -> runCatching { GlobalAction.valueOf(command.optString("action")) }
                        .getOrNull()?.let(service::global) ?: false
                    else -> false
                }
                val reason = if (success) null else "Android 系统拒绝执行该控制操作"
                if (success) {
                    DiagnosticLog.info("Control", "Executed $commandType command")
                } else {
                    DiagnosticLog.warn("Control", "Android rejected $commandType command")
                }
                onResult(ControlExecutionResult(commandType, success, reason, action))
            }
        }

        private fun JSONObject.x(name: String) = optDouble(name, 0.0).toFloat().coerceIn(0f, 1f)
    }
}

enum class GlobalAction { BACK, HOME, RECENTS }
