package com.example.mytest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class ScreenCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val ocrProcessor = OcrProcessor()
    private val ocrResultAnalyzer = SearchOcrAnalyzer()
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(intent)
            ACTION_CAPTURE -> captureAndRecognize(intent)
        }
        return START_STICKY
    }

    private fun startProjection(intent: Intent) {
        startForeground(NOTIFICATION_ID, createNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            broadcastResult("화면 캡처 권한을 다시 승인해 주세요.")
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        releaseProjection(stopProjection = true)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            broadcastResult("화면 캡처 세션을 만들지 못했습니다.")
            return
        }

        mediaProjection = projection.apply {
            registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        releaseProjection(stopProjection = false)
                    }
                },
                handler
            )
        }
        createVirtualDisplay()
        broadcastResult("OCR 준비 완료")
    }

    private fun createVirtualDisplay() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ebook-ocr-capture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    }

    private fun captureAndRecognize(intent: Intent) {
        if (mediaProjection == null || imageReader == null) {
            broadcastResult("먼저 앱에서 화면 캡처 OCR 권한을 켜 주세요.")
            return
        }

        handler.postDelayed({
            val bitmap = imageReader?.acquireLatestImage()?.use { image ->
                image.toBitmap()
            }

            if (bitmap == null) {
                broadcastResult("화면 캡처 이미지를 가져오지 못했습니다.")
                return@postDelayed
            }

            val ocrBitmap = bitmap.cropForOcr(intent.toCropBounds(bitmap.width, bitmap.height))
            if (ocrBitmap !== bitmap) {
                bitmap.recycle()
            }

            ocrProcessor.recognize(ocrBitmap)
                .addOnSuccessListener { result ->
                    val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
                    val text = ocrResultAnalyzer.analyze(result, targetPackage)
                    broadcastResult(text.ifBlank { "인식된 한글 텍스트가 없습니다." })
                }
                .addOnFailureListener { error ->
                    broadcastResult("OCR 실패: ${error.message ?: "알 수 없는 오류"}")
                }
                .addOnCompleteListener {
                    ocrBitmap.recycle()
                }
        }, CAPTURE_DELAY_MS)
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(buffer)
        val bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        paddedBitmap.recycle()
        return bitmap
    }

    private fun Bitmap.cropForOcr(bounds: Rect): Bitmap {
        val left = bounds.left.coerceIn(0, width - 1)
        val top = bounds.top.coerceIn(0, height - 1)
        val right = bounds.right.coerceIn(left + 1, width)
        val bottom = bounds.bottom.coerceIn(top + 1, height)
        val cropWidth = right - left
        val cropHeight = bottom - top

        if (cropWidth <= 0 || cropHeight <= 0) return this
        return Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
    }

    private fun Intent.toCropBounds(bitmapWidth: Int, bitmapHeight: Int): Rect {
        val fallbackLeft = (bitmapWidth * FALLBACK_HORIZONTAL_CROP_RATIO).toInt()
        val fallbackTop = (bitmapHeight * FALLBACK_TOP_CROP_RATIO).toInt()
        val fallbackRight = bitmapWidth - fallbackLeft
        val fallbackBottom = bitmapHeight - (bitmapHeight * FALLBACK_BOTTOM_CROP_RATIO).toInt()

        val left = getIntExtra(EXTRA_CROP_LEFT, fallbackLeft)
        val top = getIntExtra(EXTRA_CROP_TOP, fallbackTop)
        val right = getIntExtra(EXTRA_CROP_RIGHT, fallbackRight)
        val bottom = getIntExtra(EXTRA_CROP_BOTTOM, fallbackBottom)
        return Rect(left, top, right, bottom)
    }

    private fun broadcastResult(text: String) {
        val intent = Intent(ACTION_OCR_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_OCR_TEXT, text)
        }
        sendBroadcast(intent)
    }

    private fun createNotification(): Notification {
        val channelId = "screen_capture_ocr"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen OCR",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("이북 OCR 실행 중")
            .setContentText("현재 화면을 OCR로 읽을 준비가 되었습니다.")
            .setOngoing(true)
            .build()
    }

    private fun releaseProjection(stopProjection: Boolean) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        if (stopProjection) {
            mediaProjection?.stop()
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        releaseProjection(stopProjection = true)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.mytest.action.START_SCREEN_CAPTURE"
        const val ACTION_CAPTURE = "com.example.mytest.action.CAPTURE_SCREEN"
        const val ACTION_OCR_RESULT = "com.example.mytest.action.OCR_RESULT"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_OCR_TEXT = "extra_ocr_text"
        const val EXTRA_CROP_LEFT = "extra_crop_left"
        const val EXTRA_CROP_TOP = "extra_crop_top"
        const val EXTRA_CROP_RIGHT = "extra_crop_right"
        const val EXTRA_CROP_BOTTOM = "extra_crop_bottom"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"

        private const val NOTIFICATION_ID = 1001
        private const val CAPTURE_DELAY_MS = 300L
        private const val FALLBACK_HORIZONTAL_CROP_RATIO = 0.04f
        private const val FALLBACK_TOP_CROP_RATIO = 0.06f
        private const val FALLBACK_BOTTOM_CROP_RATIO = 0.06f

        fun requestCapture(
            context: Context,
            cropLeft: Int,
            cropTop: Int,
            cropRight: Int,
            cropBottom: Int,
            targetPackage: String
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE
                putExtra(EXTRA_CROP_LEFT, cropLeft)
                putExtra(EXTRA_CROP_TOP, cropTop)
                putExtra(EXTRA_CROP_RIGHT, cropRight)
                putExtra(EXTRA_CROP_BOTTOM, cropBottom)
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            }
            context.startService(intent)
        }
    }
}
