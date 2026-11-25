package com.leefly.droidassist

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

object DiagnosticLog {
    private const val TAG = "DroidAssist"
    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 512 * 1024L
    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
    private val executor = Executors.newSingleThreadExecutor()
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries
    @Volatile private var logFile: File? = null

    fun initialize(context: Context) {
        if (logFile != null) return
        synchronized(this) {
            if (logFile != null) return
            val file = File(context.filesDir, "droidassist-diagnostic.log")
            logFile = file
            _entries.value = runCatching { file.readLines().takeLast(MAX_ENTRIES) }.getOrDefault(emptyList())
            info("App", "DroidAssist started on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        }
    }

    fun info(component: String, message: String) = record("I", component, message)
    fun warn(component: String, message: String) = record("W", component, message)
    fun error(component: String, message: String, error: Throwable? = null) {
        val detail = error?.let { " | ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        record("E", component, message + detail)
    }

    fun clear() {
        _entries.value = emptyList()
        executor.execute { runCatching { logFile?.writeText("") } }
    }

    fun reportHeader(versionName: String): String = buildString {
        appendLine("DroidAssist $versionName 诊断日志")
        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("生成时间：${formatter.format(Instant.now())}")
        appendLine("说明：日志不会包含设备令牌、连接密码、SDP 或 ICE 地址。")
        appendLine()
    }

    private fun record(level: String, component: String, message: String) {
        val line = "${formatter.format(Instant.now())} $level/$component ${message.replace('\n', ' ')}"
        _entries.value = (_entries.value + line).takeLast(MAX_ENTRIES)
        when (level) {
            "E" -> Log.e(TAG, line)
            "W" -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }
        executor.execute {
            val file = logFile ?: return@execute
            runCatching {
                if (file.length() >= MAX_FILE_BYTES) {
                    file.writeText(_entries.value.takeLast(MAX_ENTRIES / 2).joinToString("\n", postfix = "\n"))
                } else {
                    file.appendText("$line\n")
                }
            }
        }
    }
}
