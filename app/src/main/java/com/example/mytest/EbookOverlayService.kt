package com.example.mytest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class EbookOverlayService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var resultTextView: TextView? = null
    private var isOverlayShowing = false
    private var latestOverlayMessage = DEFAULT_OVERLAY_MESSAGE
    private var currentTargetApp: String? = null
    private var isOcrResultReceiverRegistered = false
    private val targetAppPackages = setOf(
        "com.yes24.commerce",
        "mok.android",
        "kr.co.aladin.third_shop"
    )
    private val handler = Handler(Looper.getMainLooper())
    private val ocrResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_OCR_RESULT) return
            val text = intent.getStringExtra(ScreenCaptureService.EXTRA_OCR_TEXT)
                ?: "OCR 결과가 없습니다."
            Log.d(TAG, "Received OCR result broadcast")
            handler.post {
                showOverlay("[OCR]\n$text")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerOcrResultReceiver()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = 0
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val currentApp = event.packageName?.toString() ?: return

            // ★★★ [진짜 핵심 방어벽] ★★★
            // 오버레이가 뜰 때 내 앱 이름("com.example.mytest")이 감지되면
            // 크롬이 아니라고 착각해서 지우지 못하도록 무조건 무시하고 강제 리턴시킵니다.
            if (currentApp == packageName ||
                currentApp == "android" ||
                currentApp == "com.android.systemui" ||
                currentApp.contains("inputmethod")) {
                return
            }

            // 지원 대상 앱이 켜졌을 때만 OCR 오버레이를 띄웁니다.
            if (isTargetApp(currentApp)) {
                currentTargetApp = currentApp
                if (Settings.canDrawOverlays(applicationContext) && !isOverlayShowing) {
                    showOverlay()
                }
            } else {
                // 지원 대상이 아닌 완전히 다른 앱(바탕화면, 설정 등)이 확실할 때만 내리기
                currentTargetApp = null
                hideOverlay()
            }
        }
    }

    private fun isTargetApp(currentApp: String): Boolean {
        return currentApp in targetAppPackages ||
            currentApp.contains("yes24", ignoreCase = true) ||
            currentApp.contains("kyobo", ignoreCase = true) ||
            currentApp.contains("aladin", ignoreCase = true)
    }

    private fun showOverlay(message: String = DEFAULT_OVERLAY_MESSAGE) {
        showOverlay(message, OVERLAY_SHOW_RETRY_COUNT)
    }

    private fun showOverlay(message: String, attemptsLeft: Int) {
        latestOverlayMessage = message

        if (!Settings.canDrawOverlays(applicationContext)) {
            Toast.makeText(applicationContext, "다른 앱 위에 표시 권한을 켜 주세요.", Toast.LENGTH_SHORT).show()
            isOverlayShowing = false
            return
        }

        if (isOverlayShowing && overlayView?.isAttachedToWindow == true) {
            resultTextView?.text = message
            return
        }

        if (overlayView != null) {
            hideOverlay()
        }

        resultTextView = TextView(applicationContext).apply {
            text = message
            setTextColor(Color.WHITE)
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            textSize = 14f
            maxLines = 12
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
        }

        val captureButton = Button(applicationContext).apply {
            text = "책 후보 찾기"
            setOnClickListener {
                val targetPackage = currentTargetApp
                if (targetPackage == null) {
                    Toast.makeText(applicationContext, "지원 대상 앱에서 다시 실행해 주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Toast.makeText(applicationContext, "책 후보를 찾습니다.", Toast.LENGTH_SHORT).show()
                findBookCandidates(targetPackage)
            }
        }

        overlayView = LinearLayout(applicationContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD263238"))
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            addView(
                captureButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                resultTextView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            setOnLongClickListener {
                hideOverlay()
                true
            }
        }

        val params = WindowManager.LayoutParams(
            320.dp(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 80.dp()
        }

        try {
            windowManager?.addView(overlayView, params)
            isOverlayShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
            e.printStackTrace()
            overlayView = null
            resultTextView = null
            isOverlayShowing = false
            if (attemptsLeft > 1) {
                handler.postDelayed({
                    showOverlay(latestOverlayMessage, attemptsLeft - 1)
                }, OVERLAY_SHOW_RETRY_DELAY_MS)
            } else {
                Toast.makeText(applicationContext, "결과 창을 표시하지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun findBookCandidates(targetPackage: String) {
        runScreenOcr(targetPackage)
    }

    private fun runScreenOcr(targetPackage: String) {
        Toast.makeText(applicationContext, "현재 화면을 OCR로 확인합니다.", Toast.LENGTH_SHORT).show()
        val cropBounds = detectOcrCropBounds()
        hideOverlay()
        handler.postDelayed({
            val started = ScreenCaptureService.requestCapture(
                applicationContext,
                cropBounds.left,
                cropBounds.top,
                cropBounds.right,
                cropBounds.bottom,
                targetPackage
            )
            if (!started) {
                showOverlay("[OCR]\n먼저 앱에서 화면 캡처 OCR 권한을 켜 주세요.")
            }
        }, 250L)
    }

    private fun hideOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        overlayView = null
        resultTextView = null
        isOverlayShowing = false
    }

    private fun detectOcrCropBounds(): Rect {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val fallbackLeft = (screenWidth * FALLBACK_HORIZONTAL_CROP_RATIO).toInt()
        val fallbackTop = (screenHeight * FALLBACK_TOP_CROP_RATIO).toInt()
        val fallbackRight = screenWidth - fallbackLeft
        val fallbackBottom = screenHeight - (screenHeight * FALLBACK_BOTTOM_CROP_RATIO).toInt()
        return Rect(fallbackLeft, fallbackTop, fallbackRight, fallbackBottom)
    }

    private fun registerOcrResultReceiver() {
        if (isOcrResultReceiverRegistered) return

        val filter = IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT)
        ContextCompat.registerReceiver(
            this,
            ocrResultReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isOcrResultReceiverRegistered = true
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val DEFAULT_OVERLAY_MESSAGE = "책 검색에 필요한 후보만 찾으려면 실행하세요."
        private const val TAG = "EbookOverlayService"
        private const val OVERLAY_SHOW_RETRY_COUNT = 3
        private const val OVERLAY_SHOW_RETRY_DELAY_MS = 250L
        private const val FALLBACK_HORIZONTAL_CROP_RATIO = 0.04f
        private const val FALLBACK_TOP_CROP_RATIO = 0.06f
        private const val FALLBACK_BOTTOM_CROP_RATIO = 0.06f
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            if (isOcrResultReceiverRegistered) {
                unregisterReceiver(ocrResultReceiver)
                isOcrResultReceiverRegistered = false
            }
        } catch (_: Exception) {
        }
        hideOverlay()
        super.onDestroy()
    }
}
