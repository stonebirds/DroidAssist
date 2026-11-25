package com.leefly.droidassist

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.client.android.Intents
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import java.util.concurrent.Executors
import kotlin.math.min

class QrScannerActivity : CaptureActivity() {
    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var torchButton: ImageButton
    private var torchEnabled = false
    private val galleryExecutor = Executors.newSingleThreadExecutor()

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
        if (requestCode != REQUEST_GALLERY || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        Toast.makeText(this, "正在识别相册二维码", Toast.LENGTH_SHORT).show()
        galleryExecutor.execute {
            val result = runCatching { decodeGalleryQr(uri) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.getOrNull()?.let(::returnResult) ?: Toast.makeText(
                    this,
                    "未识别到二维码，请选择清晰的原图",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    override fun onDestroy() {
        galleryExecutor.shutdownNow()
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
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            REQUEST_GALLERY,
        )
    }

    private fun decodeGalleryQr(uri: Uri): Result {
        val bitmap = loadSampledBitmap(uri)
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                    ),
                )
            }
            runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }
                .recoverCatching {
                    reader.reset()
                    reader.decodeWithState(BinaryBitmap(HybridBinarizer(InvertedLuminanceSource(source))))
                }
                .getOrThrow()
        } finally {
            bitmap.recycle()
        }
    }

    private fun loadSampledBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Unable to open selected image" }
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Unable to reopen selected image" }
            requireNotNull(BitmapFactory.decodeStream(stream, null, options)) { "Unable to decode selected image" }
        }
    }

    private fun returnResult(result: Result) {
        DiagnosticLog.info("Scanner", "QR code recognized from gallery")
        setResult(
            Activity.RESULT_OK,
            Intent(Intents.Scan.ACTION)
                .putExtra(Intents.Scan.RESULT, result.text)
                .putExtra(Intents.Scan.RESULT_FORMAT, result.barcodeFormat.toString()),
        )
        finish()
    }

    companion object {
        private const val REQUEST_GALLERY = 4201
        private const val MAX_IMAGE_EDGE = 2048
    }
}

class QrScanOverlayView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.SQUARE
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff51d7d0.toInt()
        strokeWidth = 2f * density
    }
    private val frame = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frameSize = min(width * 0.72f, 280f * density)
        val left = (width - frameSize) / 2f
        val top = (height - frameSize) * 0.38f
        val right = left + frameSize
        val bottom = top + frameSize
        frame.set(left, top, right, bottom)

        canvas.drawRect(0f, 0f, width.toFloat(), top, maskPaint)
        canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawRect(0f, top, left, bottom, maskPaint)
        canvas.drawRect(right, top, width.toFloat(), bottom, maskPaint)

        val corner = 28f * density
        drawCorner(canvas, frame.left, frame.top, corner, 1f, 1f)
        drawCorner(canvas, frame.right, frame.top, corner, -1f, 1f)
        drawCorner(canvas, frame.left, frame.bottom, corner, 1f, -1f)
        drawCorner(canvas, frame.right, frame.bottom, corner, -1f, -1f)
        canvas.drawLine(left + corner, frame.centerY(), right - corner, frame.centerY(), scanPaint)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, size: Float, xDirection: Float, yDirection: Float) {
        canvas.drawLine(x, y, x + size * xDirection, y, cornerPaint)
        canvas.drawLine(x, y, x, y + size * yDirection, cornerPaint)
    }
}
