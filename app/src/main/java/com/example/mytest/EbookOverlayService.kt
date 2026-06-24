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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class EbookOverlayService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var resultTextView: TextView? = null
    private var isOverlayShowing = false
    private var currentTargetApp: String? = null
    private val targetAppPackages = setOf(
        "com.yes24.ebook.fourth",
        "com.kyobo.ebook.common.b2c",
        "kr.co.aladin.ebook"
    )
    private val handler = Handler(Looper.getMainLooper())
    private val ocrResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_OCR_RESULT) return
            val text = intent.getStringExtra(ScreenCaptureService.EXTRA_OCR_TEXT)
                ?: "OCR 결과가 없습니다."
            showOverlay(text)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerOcrResultReceiver()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
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
                if (Settings.canDrawOverlays(applicationContext)) {
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

    private fun showOverlay(message: String = "책 검색에 필요한 후보만 찾으려면 OCR을 실행하세요.") {
        if (isOverlayShowing) {
            resultTextView?.text = message
            return
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
                val cropBounds = detectOcrCropBounds()
                hideOverlay()
                handler.postDelayed({
                    ScreenCaptureService.requestCapture(
                        applicationContext,
                        cropBounds.left,
                        cropBounds.top,
                        cropBounds.right,
                        cropBounds.bottom,
                        targetPackage
                    )
                }, 250L)
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
            e.printStackTrace()
        }
    }

    private fun hideOverlay() {
        if (!isOverlayShowing) return

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

        var top = fallbackTop
        var bottom = fallbackBottom
        val root = rootInActiveWindow ?: return Rect(fallbackLeft, top, fallbackRight, bottom)
        val nodeBounds = Rect()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.getBoundsInScreen(nodeBounds)

            val className = node.className?.toString().orEmpty()
            val isUiControl = node.isClickable ||
                node.isFocusable ||
                node.isEditable ||
                className.contains("Button", ignoreCase = true) ||
                className.contains("EditText", ignoreCase = true) ||
                className.contains("Toolbar", ignoreCase = true)

            val isReasonableSize = nodeBounds.width() > 0 &&
                nodeBounds.height() > 0 &&
                nodeBounds.height() < screenHeight * MAX_UI_CONTROL_HEIGHT_RATIO

            if (isUiControl && isReasonableSize) {
                if (nodeBounds.top < screenHeight * TOP_UI_SEARCH_RATIO) {
                    top = maxOf(top, nodeBounds.bottom)
                }
                if (nodeBounds.bottom > screenHeight * BOTTOM_UI_SEARCH_RATIO) {
                    bottom = minOf(bottom, nodeBounds.top)
                }
            }

            for (index in 0 until node.childCount) {
                visit(node.getChild(index))
            }
        }

        visit(root)

        val maxTop = (screenHeight * MAX_TOP_CROP_RATIO).toInt()
        if (top > maxTop) top = fallbackTop
        if (bottom <= top + MIN_OCR_AREA_HEIGHT_DP.dp()) bottom = fallbackBottom

        return Rect(fallbackLeft, top, fallbackRight, bottom)
    }

    private fun registerOcrResultReceiver() {
        val filter = IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ocrResultReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(ocrResultReceiver, filter)
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val FALLBACK_HORIZONTAL_CROP_RATIO = 0.04f
        private const val FALLBACK_TOP_CROP_RATIO = 0.06f
        private const val FALLBACK_BOTTOM_CROP_RATIO = 0.06f
        private const val TOP_UI_SEARCH_RATIO = 0.35f
        private const val BOTTOM_UI_SEARCH_RATIO = 0.78f
        private const val MAX_UI_CONTROL_HEIGHT_RATIO = 0.25f
        private const val MAX_TOP_CROP_RATIO = 0.45f
        private const val MIN_OCR_AREA_HEIGHT_DP = 240
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            unregisterReceiver(ocrResultReceiver)
        } catch (_: Exception) {
        }
        hideOverlay()
        super.onDestroy()
    }
}
