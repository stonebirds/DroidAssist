package com.leefly.droidassist

import android.app.Activity
import android.app.AlertDialog
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.zxing.client.android.Intents
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class QrScannerActivity : CaptureActivity() {
    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var torchButton: ImageButton
    private var torchEnabled = false
    private val galleryScanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_qr_scanner)
        scanner = findViewById(R.id.zxing_barcode_scanner)
        return scanner
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        super.onCreate(savedInstanceState)

        scanner.viewFinder.visibility = View.GONE
        scanner.statusView.visibility = View.GONE
        findViewById<View>(R.id.qr_close).setOnClickListener { finish() }
        torchButton = findViewById(R.id.qr_torch)
        torchButton.setOnClickListener { toggleTorch() }
        findViewById<View>(R.id.qr_gallery).setOnClickListener { openGallery() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.qr_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_GALLERY) return
        if (resultCode != Activity.RESULT_OK) {
            scanner.resume()
            return
        }
        val uri = data?.data ?: run {
            scanner.resume()
            return
        }
        scanner.pause()
        Toast.makeText(this, "正在识别相册二维码", Toast.LENGTH_SHORT).show()
        val image = runCatching { InputImage.fromFilePath(this, uri) }.getOrElse {
            showGalleryDecodeFailure()
            return
        }
        galleryScanner.process(image)
            .addOnSuccessListener(::handleGalleryBarcodes)
            .addOnFailureListener {
                DiagnosticLog.error("Scanner", "Unable to recognize QR code from gallery", it)
                showGalleryDecodeFailure()
            }
    }

    override fun onDestroy() {
        galleryScanner.close()
        super.onDestroy()
    }

    private fun toggleTorch() {
        torchEnabled = !torchEnabled
        runCatching {
            if (torchEnabled) scanner.setTorchOn() else scanner.setTorchOff()
        }.onFailure {
            torchEnabled = false
            Toast.makeText(this, "当前设备无法开启手电筒", Toast.LENGTH_SHORT).show()
        }
        torchButton.isSelected = torchEnabled
        torchButton.alpha = if (torchEnabled) 1f else 0.78f
    }

    private fun openGallery() {
        scanner.pause()
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            REQUEST_GALLERY,
        )
    }

    private fun handleGalleryBarcodes(barcodes: List<Barcode>) {
        if (isFinishing || isDestroyed) return
        val values = barcodes
            .mapNotNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .sortedByDescending(::isWebAddress)
        when (values.size) {
            0 -> showGalleryDecodeFailure()
            1 -> returnGalleryResult(values.first())
            else -> showBarcodeSelection(values)
        }
    }

    private fun showBarcodeSelection(values: List<String>) {
        DiagnosticLog.info("Scanner", "${values.size} QR codes recognized from gallery")
        AlertDialog.Builder(this)
            .setTitle("选择要使用的二维码")
            .setItems(values.map(::barcodeLabel).toTypedArray()) { _, index ->
                returnGalleryResult(values[index])
            }
            .setNegativeButton("取消") { _, _ -> scanner.resume() }
            .setOnCancelListener { scanner.resume() }
            .show()
    }

    private fun barcodeLabel(value: String): String {
        val uri = runCatching { Uri.parse(value) }.getOrNull()
        val title = if (isWebAddress(value)) {
            "服务器地址 · ${uri?.host.orEmpty()}"
        } else {
            "二维码内容"
        }
        val singleLine = value.replace(Regex("\\s+"), " ")
        val summary = if (singleLine.length > 72) singleLine.take(69) + "..." else singleLine
        return "$title\n$summary"
    }

    private fun isWebAddress(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    private fun showGalleryDecodeFailure() {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, "未识别到二维码，请选择清晰的原图", Toast.LENGTH_SHORT).show()
        scanner.resume()
    }

    private fun returnGalleryResult(value: String) {
        DiagnosticLog.info("Scanner", "QR code recognized from gallery")
        setResult(
            Activity.RESULT_OK,
            Intent(Intents.Scan.ACTION)
                .putExtra(Intents.Scan.RESULT, value)
                .putExtra(Intents.Scan.RESULT_FORMAT, "QR_CODE"),
        )
        finish()
    }

    companion object {
        private const val REQUEST_GALLERY = 4201
    }
}

class QrScanOverlayView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3D000000 }
    private val scanBandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scanProgress = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_600L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            scanProgress = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        val centerY = height * scanProgress
        val halfHeight = 34f * density
        scanBandPaint.shader = LinearGradient(
            0f,
            centerY - halfHeight,
            0f,
            centerY + halfHeight,
            intArrayOf(
                Color.TRANSPARENT,
                0x2451D7D0,
                0xB351D7D0.toInt(),
                0x2451D7D0,
                Color.TRANSPARENT,
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, centerY - halfHeight, width.toFloat(), centerY + halfHeight, scanBandPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
