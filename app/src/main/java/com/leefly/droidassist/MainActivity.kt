package com.leefly.droidassist

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leefly.droidassist.service.ScreenShareService
import com.leefly.droidassist.ui.DroidAssistApp
import com.leefly.droidassist.ui.theme.DroidAssistTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DroidAssistRoot() }
    }
}

@Composable
private fun DroidAssistRoot(viewModel: AssistViewModel = viewModel()) {
    val context = LocalContext.current
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::setServerUrlFromScan)
    }
    val capturePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            DiagnosticLog.info("Capture", "System granted full-screen capture permission")
            ContextCompat.startForegroundService(context, ScreenShareService.startIntent(context, result.resultCode, result.data!!))
            Toast.makeText(context, "已允许屏幕共享，正在准备连接", Toast.LENGTH_SHORT).show()
        } else {
            DiagnosticLog.warn("Capture", "System screen capture permission was denied")
            Toast.makeText(context, "未允许屏幕录制", Toast.LENGTH_SHORT).show()
        }
    }
    val launchCapturePermission = {
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        capturePermission.launch(captureIntent)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchCapturePermission()
    }
    val requestScreenCapture = {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchCapturePermission()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.screenCaptureRequests.collect {
            DiagnosticLog.info("Capture", "Launching system screen capture request")
            requestScreenCapture()
        }
    }
    DroidAssistTheme {
        DroidAssistApp(
            state = state,
            onServerUrlChange = viewModel::setServerUrl,
            onScanServerUrl = {
                qrScanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setCaptureActivity(QrScannerActivity::class.java)
                        .setPrompt("扫描电脑页面上的服务器二维码")
                        .setBeepEnabled(false)
                        .setOrientationLocked(true),
                )
            },
            onRegister = viewModel::register,
            onCreateGrant = viewModel::createGrant,
            onConnect = viewModel::connect,
            onClearRecentMatchingCodes = viewModel::clearRecentMatchingCodes,
            onRespondToIncoming = viewModel::respondToIncoming,
            onRefresh = viewModel::refreshManagement,
            onRevoke = viewModel::revoke,
            onDisconnect = viewModel::disconnect,
            onStopSharing = { context.startService(ScreenShareService.stopIntent(context)) },
            onDismissMessage = viewModel::clearMessage,
        )
    }
}
