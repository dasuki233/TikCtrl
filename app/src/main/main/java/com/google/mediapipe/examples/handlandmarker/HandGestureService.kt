package com.google.mediapipe.examples.handlandmarker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Bitmap
import java.nio.ByteBuffer
import androidx.core.app.NotificationCompat
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleService
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import android.view.WindowManager
import android.view.View
import android.view.LayoutInflater
import androidx.camera.view.PreviewView
import android.graphics.PixelFormat
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.view.MotionEvent
import android.view.ViewGroup
import android.content.Context
import android.widget.FrameLayout
import androidx.camera.core.Preview

class HandGestureService : LifecycleService() {

    companion object {
        private const val TAG = "HandGestureService"    // 日志标签
        private const val CHANNEL_ID = "hand_gesture_channel"   // 通知渠道ID
        const val ACTION_GESTURE = "com.google.mediapipe.examples.handlandmarker.ACTION_GESTURE"    // 广播动作
        const val EXTRA_GESTURE = "gesture"     // 广播中携带手势数据的键名
    }
    
    // Safely restore from minimized icon to full floating preview
    private fun restoreFromMinimized() {
        try {
            Log.i(TAG, "restoreFromMinimized called")
            if (minimizedView != null) {
                try { windowManager?.removeView(minimizedView) } catch (e: Exception) { Log.w(TAG, "Failed to remove minimizedView: ${e.message}") }
                minimizedView = null
            }
            // Recreate the large preview
            try { createFloatingPreview() } catch (e: Exception) { Log.w(TAG, "createFloatingPreview failed in restore: ${e.message}") }
            // Rebind camera to restore preview
            try { bindCameraUseCases() } catch (e: Exception) { Log.w(TAG, "bindCameraUseCases failed in restore: ${e.message}") }
        } catch (e: Exception) {
            Log.w(TAG, "restoreFromMinimized unexpected: ${e.message}")
        }
    }

    private var handLandmarker: HandLandmarker? = null  // MediaPipe手部关键点检测器
    private val cameraExecutor = Executors.newSingleThreadExecutor()    // 相机操作线程池
    private val gestureClassifier = GestureClassifier() // 手势分类器
    // Floating window
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var minimizedView: View? = null
    private var previewView: PreviewView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private val mainHandler = Handler(Looper.getMainLooper())

    // Gesture debounce / stability controls
    private var lastDetectedGesture: GestureClassifier.Gesture? = null
    private var consecutiveDetections = 0
    private val requiredConsecutiveDetections = 3   
    private var lastSentGesture: GestureClassifier.Gesture? = null
    private var lastSentTimeMs: Long = 0
    private val gestureCooldownMs: Long = 1500  // 发送广播冷却
    // Instant feedback debounce
    private var lastFeedbackTimeMs: Long = 0
    private val feedbackCooldownMs: Long = 1000 // 最多每秒一次反馈

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        setupHandLandmarker()
        // Register receiver for test feedback broadcasts
        val filter = android.content.IntentFilter(ACTION_GESTURE)
        registerReceiver(gestureTestReceiver, filter)
        // Create floating preview if we have overlay permission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)) {
            createFloatingPreview()
            startCamera()
            // If feedback view was created, show READY briefly so user can confirm overlay
            mainHandler.post {
                try {
                    feedbackView?.text = "READY"
                    feedbackView?.alpha = 1f
                    feedbackView?.visibility = View.VISIBLE
                    mainHandler.postDelayed({
                        try { feedbackView?.animate()?.alpha(0f)?.withEndAction { feedbackView?.visibility = View.GONE } } catch (_: Exception) {}
                    }, 1200)
                } catch (_: Exception) {}
            }
        } else {
            // If no overlay permission, still start camera without preview to keep detection running
            startCamera()
        }
    }

    private val gestureTestReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val g = intent?.getStringExtra(EXTRA_GESTURE) ?: return
                if (g == "TEST") {
                    // show test feedback on overlay
                    mainHandler.post {
                        try {
                            if (feedbackView != null) {
                                feedbackView?.text = "手势: TEST"
                                feedbackView?.alpha = 1f
                                feedbackView?.visibility = View.VISIBLE
                                mainHandler.postDelayed({
                                    try { feedbackView?.animate()?.alpha(0f)?.withEndAction { feedbackView?.visibility = View.GONE } } catch (_: Exception) {}
                                }, 1200)
                            } else {
                                android.widget.Toast.makeText(this@HandGestureService, "手势: TEST", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // 启动前台服务，显示持续运行的通知
    private fun startForegroundService() {
        // Android 8.0及以上需要创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hand Gesture Service", // 渠道名称
                NotificationManager.IMPORTANCE_LOW  // 低重要性，不会打扰用户
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        // 构建通知
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hand Gesture Service")
            .setContentText("识别手势中…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(1, notification)    // 启动前台服务，ID为1
    }

    // 设置手部关键点检测器
    private fun setupHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)  // 使用CPU推理
                .setModelAssetPath("hand_landmarker.task")  // 模型文件路径
                .build()

            // 手部关键点检测器选项
                .setNumHands(2) // 检测最多1只手
                .setRunningMode(RunningMode.LIVE_STREAM)    // 实时流模式
                .setResultListener { result: HandLandmarkerResult, _: com.google.mediapipe.framework.image.MPImage ->
                    handleHandLandmarkerResult(result)  // 结果回调处理
                }
                .build()


    // 启动相机
    private fun startCamera() {
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            // 配置图像分析器，请求RGBA_8888格式以便高效转换为Bitmap
            // We'll bind the use cases via helper so we can rebind on camera switch
                return@setAnalyzer
            }
            handLandmarker?.detectAsync(mpImage, System.currentTimeMillis())
            imageProxy.close()
        }

        val cameraSelector = currentCameraSelector

        mainHandler.post {
            provider.unbindAll()
            if (floatingView != null && previewView != null) {
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                preview.setSurfaceProvider(previewView!!.surfaceProvider)
                try {
                    provider.bindToLifecycle(this, cameraSelector, preview, analyzer)
                } catch (e: Exception) {
                    Log.e(TAG, "bindToLifecycle preview+analyzer failed: ${e.message}")
                }
            } else {
                try {
                    provider.bindToLifecycle(this, cameraSelector, analyzer)
                } catch (e: Exception) {
                    Log.e(TAG, "bindToLifecycle analyzer-only failed: ${e.message}")
                }
            }
        }
    }

    private fun switchCamera() {
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        Log.i(TAG, "Switching camera. New selector: ${if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) "FRONT" else "BACK"}")
        try {
            bindCameraUseCases()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch camera: ${e.message}")
        }
    }

    // Create a small floating PreviewView and attach to WindowManager
    private fun createFloatingPreview() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // Create a programmatic container to avoid inflating a full-screen system layout
            val container = android.widget.FrameLayout(this)

            val PREVIEW_W = 480
            val PREVIEW_H = 640
            previewView = PreviewView(this)
            val previewLp = ViewGroup.LayoutParams(PREVIEW_W, PREVIEW_H)
            previewView!!.layoutParams = previewLp
            container.addView(previewView)

            // Close button (top-right)
            val closeBtn = ImageButton(this)
            closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            val closeParams = FrameLayout.LayoutParams(80, 80)
            closeParams.gravity = Gravity.TOP or Gravity.END
            closeBtn.layoutParams = closeParams
            container.addView(closeBtn)
            closeBtn.setOnClickListener {
                stopSelf()
            }

            // Switch camera button (top-left)
            val switchBtn = ImageButton(this)
            switchBtn.setImageResource(android.R.drawable.ic_menu_camera)
            val switchParams = FrameLayout.LayoutParams(80, 80)
            switchParams.gravity = Gravity.TOP or Gravity.START
            switchBtn.layoutParams = switchParams
            container.addView(switchBtn)
            switchBtn.setOnClickListener {
                switchCamera()
            }

            // Minimize button (top-center)
            val minimizeBtn = ImageButton(this)
            minimizeBtn.setImageResource(android.R.drawable.ic_menu_zoom)
            val minimizeParams = FrameLayout.LayoutParams(80, 80)
            minimizeParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            minimizeBtn.layoutParams = minimizeParams
            container.addView(minimizeBtn)

            // Layout params for overlay
            val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            layoutParams = WindowManager.LayoutParams(
                PREVIEW_W,
                PREVIEW_H,
                type,
                flag,
                PixelFormat.TRANSLUCENT
            )
            layoutParams!!.gravity = Gravity.TOP or Gravity.START
            layoutParams!!.x = 50
            layoutParams!!.y = 200

            // Add basic drag support
            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f
            container.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams!!.x
                        initialY = layoutParams!!.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        layoutParams!!.x = initialX + dx
                        layoutParams!!.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(container, layoutParams)
                        } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }

            // Minimize behavior: replace the large container with a small floating icon
            minimizeBtn.setOnClickListener {
                try {
                    // Remember current position
                    val savedX = layoutParams!!.x
                    val savedY = layoutParams!!.y
                    // Remove large container
                    windowManager?.removeView(container)
                    floatingView = null
                    previewView = null

                    // Create small icon view by inflating layout
                    val inflater = LayoutInflater.from(this)
                    val iconView = inflater.inflate(R.layout.float_icon, null) as ImageView

                    val iconLp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        flag,
                        PixelFormat.TRANSLUCENT
                    )
                    iconLp.gravity = Gravity.TOP or Gravity.START
                    iconLp.x = savedX
                    iconLp.y = savedY

                    // Drag support for icon
                    var iInitialX = 0
                    var iInitialY = 0
                    var iTouchX = 0f
                    var iTouchY = 0f
                    iconView.setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                iInitialX = iconLp.x
                                iInitialY = iconLp.y
                                iTouchX = event.rawX
                                iTouchY = event.rawY
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = (event.rawX - iTouchX).toInt()
                                val dy = (event.rawY - iTouchY).toInt()
                                iconLp.x = iInitialX + dx
                                iconLp.y = iInitialY + dy
                                try { windowManager?.updateViewLayout(iconView, iconLp) } catch (_: Exception) {}
                                true
                            }
                            else -> false
                        }
                    }

                    // Restore on click - call service helper to handle safely
                    iconView.setOnClickListener {
                        try {
                            restoreFromMinimized()
                        } catch (e: Exception) {
                            Log.w(TAG, "restoreOnClick failed: ${e.message}")
                        }
                    }

                    try {
                        // If there is an existing minimizedView, remove it first
                        if (minimizedView != null) {
                            try { windowManager?.removeView(minimizedView) } catch (_: Exception) {}
                            minimizedView = null
                        }
                        windowManager?.addView(iconView, iconLp)
                        minimizedView = iconView
                        // Rebind camera as analyzer-only (no preview)
                        try { bindCameraUseCases() } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add minimized icon: ${e.message}")
                    }
                } catch (e: Exception) {
                }
            }
            // Feedback TextView (hidden by default)
            val fb = TextView(this)
            fb.setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
            fb.setTextColor(android.graphics.Color.WHITE)
            fb.setPadding(20, 8, 20, 8)
            fb.visibility = View.GONE
            val fbLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            fbLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            fb.layoutParams = fbLp
            container.addView(fb)
            feedbackView = fb

            windowManager?.addView(container, layoutParams)
            floatingView = container
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create floating preview: ${e.message}")
            floatingView = null
        }
    }
    // 处理手部关键点检测结果
    private fun handleHandLandmarkerResult(result: HandLandmarkerResult) {
        // 使用分类器对手势进行分类
        val gesture = gestureClassifier.classify(result)
        Log.d(TAG, "Classified gesture: $gesture")  // 调试日志

        // If no gesture (NONE), reset stability counters
        if (gesture == GestureClassifier.Gesture.NONE) {
            lastDetectedGesture = null
            consecutiveDetections = 0
            return
        }

        // Stability: require consecutive detections of same gesture
        if (lastDetectedGesture == gesture) {
            consecutiveDetections++
        } else {
            lastDetectedGesture = gesture
            consecutiveDetections = 1
        }

        if (consecutiveDetections < requiredConsecutiveDetections) {
            Log.d(TAG, "Gesture $gesture seen $consecutiveDetections times, waiting for $requiredConsecutiveDetections")
            return
        }

        // Cooldown: avoid repeating the same gesture too frequently
        val now = System.currentTimeMillis()
        if (lastSentGesture == gesture && now - lastSentTimeMs < gestureCooldownMs) {
            Log.d(TAG, "Gesture $gesture ignored due to cooldown (elapsed=${now - lastSentTimeMs}ms)")
            return
        }

        // Passed stability and cooldown checks -> send broadcast
        Log.i(TAG, "Broadcasting gesture: ${gesture.name}")
        val intent = Intent(ACTION_GESTURE)
        intent.putExtra(EXTRA_GESTURE, gesture.name)
        intent.setPackage(packageName)
        sendBroadcast(intent)

        // Instant feedback: prefer on-screen overlay (feedbackView) when available; otherwise fallback to Toast
        try {
            if (GestureMappingManager.isInstantFeedbackEnabled(this)) {
                val nowFb = System.currentTimeMillis()
                if (nowFb - lastFeedbackTimeMs >= feedbackCooldownMs) {
                    lastFeedbackTimeMs = nowFb
                    val text = "手势: ${gesture.name}"
                    if (feedbackView != null) {
                        mainHandler.post {
                            try {
                                feedbackView?.text = text
                                feedbackView?.alpha = 1f
                                feedbackView?.visibility = View.VISIBLE
                                // Fade out after 1200ms
                                mainHandler.removeCallbacksAndMessages("fb")
                                mainHandler.postDelayed({
                                    try {
                                        feedbackView?.animate()?.alpha(0f)?.withEndAction { feedbackView?.visibility = View.GONE }
                                    } catch (e: Exception) {}
                                }, 1200)
                            } catch (e: Exception) { /* ignore */ }
                        }
                    } else {
                        mainHandler.post {
                            try {
                                android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instant feedback failed: ${e.message}")
        }

        lastSentGesture = gesture
        lastSentTimeMs = now
        // reset consecutive detections to avoid immediate re-send; will require requiredConsecutiveDetections again
        consecutiveDetections = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        handLandmarker?.close()
        cameraExecutor.shutdown()
        try {
            unregisterReceiver(gestureTestReceiver)
        } catch (e: Exception) { /* ignore */ }
        try {
            floatingView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { /* ignore */ }
    }
}

// 扩展方法：直接把 ImageProxy 转成 MPImage
fun ImageProxy.toMpImage(): com.google.mediapipe.framework.image.MPImage? {
    val bitmap = this.toBitmap() ?: return null
    // Apply rotation so that the MPImage matches the expected orientation
    val rotation = this.imageInfo.rotationDegrees
    if (rotation != 0) {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(rotation.toFloat())
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return com.google.mediapipe.framework.image.BitmapImageBuilder(rotated).build()
    }
    return com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
}

fun ImageProxy.toBitmap(): Bitmap? {
    try {
        // Heuristic: treat single-plane ImageProxy as RGBA_8888 (many CameraX configs provide a single plane)
        if (this.planes.size != 1) return null

        val plane = this.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        buffer.rewind()

        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bitmap = Bitmap.createBitmap(this.width, this.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        return bitmap
    } catch (e: Exception) {
        Log.e("HandGestureService", "toBitmap conversion failed: ${e.message}")
        return null
    }
}
