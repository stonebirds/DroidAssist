package com.leefly.droidassist.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.leefly.droidassist.AssistUiState
import com.leefly.droidassist.protocol.GrantType
import com.leefly.droidassist.protocol.ManagedDevice
import com.leefly.droidassist.service.RemoteControlService
import com.leefly.droidassist.webrtc.WebRtcConnectionStatus
import com.leefly.droidassist.webrtc.WebRtcManager
import org.webrtc.SurfaceViewRenderer
import kotlin.math.hypot

private enum class MainDestination(val label: String) {
    DEVICES("设备"),
    THIS_DEVICE("本机"),
    SETTINGS("设置"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DroidAssistApp(
    state: AssistUiState,
    onServerUrlChange: (String) -> Unit,
    onRegister: () -> Unit,
    onCreateGrant: (GrantType) -> Unit,
    onConnect: (String, String) -> Unit,
    onClearRecentMatchingCodes: () -> Unit,
    onRespondToIncoming: (Boolean, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRevoke: (String) -> Unit,
    onStopSharing: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var destinationIndex by rememberSaveable { mutableIntStateOf(0) }
    var remoteControlOpen by rememberSaveable { mutableStateOf(false) }
    var trustController by rememberSaveable { mutableStateOf(false) }
    val destination = MainDestination.entries[destinationIndex]
    val snackbarHostState = remember { SnackbarHostState() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            onDismissMessage()
        }
    }
    LaunchedEffect(state.remoteVideoReady) {
        if (state.remoteVideoReady) remoteControlOpen = true
    }

    state.incomingSession?.let { incoming ->
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Outlined.Security, contentDescription = null) },
            title = { Text("远程协助请求") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${incoming.controllerDisplayName} 请求查看并控制此设备。")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = trustController, onCheckedChange = { trustController = it })
                        Text("将此设备设为可信设备")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onRespondToIncoming(true, trustController)
                    trustController = false
                }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onRespondToIncoming(false, false)
                    trustController = false
                }) { Text("拒绝") }
            },
        )
    }

    if (remoteControlOpen && state.remoteVideoReady) {
        RemoteControlScreen(onClose = { remoteControlOpen = false })
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(destination.label) }) },
        bottomBar = {
            NavigationBar {
                MainDestination.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = destinationIndex == index,
                        onClick = { destinationIndex = index },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    MainDestination.DEVICES -> Icons.Outlined.Devices
                                    MainDestination.THIS_DEVICE -> Icons.Outlined.PhoneAndroid
                                    MainDestination.SETTINGS -> Icons.Outlined.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                MainDestination.DEVICES -> DevicesTab(
                    state = state,
                    onConnect = onConnect,
                    onClearRecentMatchingCodes = onClearRecentMatchingCodes,
                    onRefresh = onRefresh,
                    onRevoke = onRevoke,
                    onOpenRemoteControl = { remoteControlOpen = true },
                )
                MainDestination.THIS_DEVICE -> ThisDeviceTab(
                    state = state,
                    onCreateGrant = onCreateGrant,
                    onStopSharing = onStopSharing,
                )
                MainDestination.SETTINGS -> SettingsTab(
                    state = state,
                    onServerUrlChange = onServerUrlChange,
                    onRegister = onRegister,
                )
            }
            if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicesTab(
    state: AssistUiState,
    onConnect: (String, String) -> Unit,
    onClearRecentMatchingCodes: () -> Unit,
    onRefresh: () -> Unit,
    onRevoke: (String) -> Unit,
    onOpenRemoteControl: () -> Unit,
) {
    var remoteCode by rememberSaveable { mutableStateOf("") }
    var remotePassword by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.deviceId) {
        if (state.deviceId.isNotBlank()) onRefresh()
    }
    LaunchedEffect(state.recentMatchingCodes) {
        if (remoteCode.isBlank()) remoteCode = state.recentMatchingCodes.firstOrNull().orEmpty()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (state.remoteVideoReady) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.DesktopWindows, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("远程屏幕已连接", fontWeight = FontWeight.SemiBold)
                        Text("WebRTC P2P", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onOpenRemoteControl) { Text("打开") }
                }
            }
        }

        SectionTitle("连接设备")
        OutlinedTextField(
            value = remoteCode,
            onValueChange = { remoteCode = it.filter(Char::isDigit).take(9) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("对方匹配码") },
            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
            singleLine = true,
        )
        if (state.recentMatchingCodes.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最近连接", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearRecentMatchingCodes) { Text("清空") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.recentMatchingCodes.forEach { code ->
                    FilterChip(
                        selected = remoteCode == code,
                        onClick = { remoteCode = code },
                        label = { Text(code.chunked(3).joinToString(" ")) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = remotePassword,
            onValueChange = { remotePassword = it.take(12) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("连接密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            onClick = { onConnect(remoteCode, remotePassword) },
            enabled = remoteCode.length == 9 && remotePassword.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("发起连接")
        }

        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("在线设备", Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = state.deviceId.isNotBlank() && !state.busy) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新设备")
            }
        }
        if (state.management.onlineDevices.isEmpty()) {
            EmptyText("暂无其他在线设备")
        } else {
            state.management.onlineDevices.forEach { DeviceRow(it) }
        }

        HorizontalDivider()
        SectionTitle("可信设备")
        if (state.management.authorizedDevices.isEmpty()) {
            EmptyText("暂无可信设备")
        } else {
            state.management.authorizedDevices.forEach { device ->
                DeviceRow(device, onRevoke = { onRevoke(device.deviceId) })
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ThisDeviceTab(
    state: AssistUiState,
    onCreateGrant: (GrantType) -> Unit,
    onStopSharing: () -> Unit,
) {
    val context = LocalContext.current
    val accessibilityEnabled = rememberRemoteControlEnabled()
    val deviceName = remember { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.deviceId.isBlank()) "未注册" else "本机在线",
                    color = if (state.deviceId.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本机匹配码", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.matchingCode.ifBlank { "注册后生成" }.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DroidAssist 匹配码", state.matchingCode))
                            Toast.makeText(context, "匹配码已复制", Toast.LENGTH_SHORT).show()
                        },
                        enabled = state.matchingCode.isNotBlank(),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制匹配码")
                    }
                }
            }
        }

        SectionTitle("连接授权")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onCreateGrant(GrantType.TEMPORARY) },
                enabled = state.deviceId.isNotBlank() && !state.busy,
                modifier = Modifier.weight(1f),
            ) { Text("临时授权") }
            OutlinedButton(
                onClick = { onCreateGrant(GrantType.PERMANENT) },
                enabled = state.deviceId.isNotBlank() && !state.busy,
                modifier = Modifier.weight(1f),
            ) { Text("永久授权") }
        }
        state.grant?.let { grant ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("连接密码", style = MaterialTheme.typography.labelLarge)
                    Text(grant.password, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (grant.expiresAtEpochMillis == null) "首次绑定有效" else "10 分钟内使用一次")
                }
            }
        }

        HorizontalDivider()
        SectionTitle("远程控制权限")
        StatusRow("屏幕共享", screenShareStatus(state), state.isScreenSharing)
        StatusRow("无障碍控制", if (accessibilityEnabled) "已开启" else "未开启", accessibilityEnabled)
        AssistConnectionStatus(state.connectionStatus)
        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Security, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (accessibilityEnabled) "查看无障碍设置" else "开启无障碍控制")
        }
        if (state.isScreenSharing) {
            Button(
                onClick = onStopSharing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("停止共享")
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SettingsTab(
    state: AssistUiState,
    onServerUrlChange: (String) -> Unit,
    onRegister: () -> Unit,
) {
    val context = LocalContext.current
    val accessibilityEnabled = rememberRemoteControlEnabled()
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SectionTitle("服务连接")
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onServerUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器地址") },
            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
            singleLine = true,
        )
        Button(onClick = onRegister, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.deviceId.isBlank()) "注册本机" else "重新连接服务")
        }
        StatusRow("设备注册", if (state.deviceId.isBlank()) "未注册" else "已注册", state.deviceId.isNotBlank())

        HorizontalDivider()
        SectionTitle("诊断")
        StatusRow("长连接", if (state.deviceId.isBlank()) "未连接" else "运行中", state.deviceId.isNotBlank())
        StatusRow("远程控制", if (accessibilityEnabled) "可用" else "需要授权", accessibilityEnabled)
        state.deviceId.takeIf(String::isNotBlank)?.let { id ->
            Text("设备标识 ${id.take(8)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider()
        SectionTitle("关于")
        StatusRow("DroidAssist", "版本 $versionName", true)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StatusRow(label: String, value: String, active: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            value,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun screenShareStatus(state: AssistUiState): String = when {
    state.isScreenSharing -> "共享中"
    state.connectionStatus == WebRtcConnectionStatus.WAITING_FOR_CAPTURE -> "等待系统授权"
    else -> "等待连接"
}

@Composable
private fun AssistConnectionStatus(status: WebRtcConnectionStatus) {
    val text = when (status) {
        WebRtcConnectionStatus.IDLE -> return
        WebRtcConnectionStatus.CAPTURE_READY -> "屏幕共享已就绪"
        WebRtcConnectionStatus.WAITING_FOR_CAPTURE -> "连接已允许，正在请求屏幕共享权限…"
        WebRtcConnectionStatus.CONNECTING -> "正在建立远程画面连接…"
        WebRtcConnectionStatus.CONNECTED -> "远程画面已连接"
        WebRtcConnectionStatus.DISCONNECTED -> "远程画面连接已断开"
        WebRtcConnectionStatus.FAILED -> "远程画面连接失败"
    }
    Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun rememberRemoteControlEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(isRemoteControlEnabled(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = isRemoteControlEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}

private fun isRemoteControlEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java)
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { service ->
        service.resolveInfo.serviceInfo.packageName == context.packageName &&
            service.resolveInfo.serviceInfo.name == RemoteControlService::class.java.name
    }
}

@Composable
private fun RemoteVideo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context) }
    DisposableEffect(renderer) {
        WebRtcManager.attachRenderer(renderer)
        onDispose { WebRtcManager.detachRenderer(renderer) }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteControlScreen(onClose: () -> Unit) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    BackHandler {
        if (controlsVisible) controlsVisible = false else onClose()
    }
    val aspectRatio by WebRtcManager.remoteAspectRatio.collectAsState()
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val containerRatio = maxWidth.value / maxHeight.value
        val videoModifier = if (aspectRatio >= containerRatio) {
            Modifier.fillMaxWidth().aspectRatio(aspectRatio)
        } else {
            Modifier.fillMaxHeight().aspectRatio(aspectRatio)
        }
        Box(videoModifier) {
            RemoteVideo(Modifier.fillMaxSize())
            Box(Modifier.matchParentSize().remoteGestures())
        }
        if (!controlsVisible) {
            FloatingActionButton(
                onClick = { controlsVisible = true },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = "打开远程控制")
            }
        }
    }
    if (controlsVisible) {
        ModalBottomSheet(
            onDismissRequest = { controlsVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    Text("远程控制", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("已连接", color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ WebRtcManager.sendGlobal("BACK") }, Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("返回")
                    }
                    Button({ WebRtcManager.sendGlobal("HOME") }, Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Home, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Home")
                    }
                    OutlinedButton({ WebRtcManager.sendGlobal("RECENTS") }, Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Apps, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("任务")
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { controlsVisible = false }) { Text("收起控制") }
                    OutlinedButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("退出远控页面")
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

private fun Modifier.remoteGestures() = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            val startTime = down.uptimeMillis
            var end = start
            var endTime = startTime
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                end = change.position
                endTime = change.uptimeMillis
                change.consume()
                if (!change.pressed) break
            }
            val width = size.width.toFloat().coerceAtLeast(1f)
            val height = size.height.toFloat().coerceAtLeast(1f)
            val fromX = (start.x / width).coerceIn(0f, 1f)
            val fromY = (start.y / height).coerceIn(0f, 1f)
            val toX = (end.x / width).coerceIn(0f, 1f)
            val toY = (end.y / height).coerceIn(0f, 1f)
            val duration = (endTime - startTime).coerceAtLeast(1L)
            val distance = hypot(end.x - start.x, end.y - start.y)
            when {
                distance > 18f -> WebRtcManager.sendSwipe(fromX, fromY, toX, toY, duration)
                duration >= 500 -> WebRtcManager.sendLongPress(fromX, fromY, duration)
                else -> WebRtcManager.sendTap(fromX, fromY)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(
    text = text,
    modifier = modifier,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold,
)

@Composable
private fun EmptyText(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun DeviceRow(device: ManagedDevice, onRevoke: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Outlined.PhoneAndroid,
            contentDescription = null,
            tint = if (device.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(device.displayName, fontWeight = FontWeight.Medium)
            Text(if (device.online) "在线" else "离线", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRevoke != null) TextButton(onClick = onRevoke) { Text("撤销") }
    }
}
