package com.tikctrl.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
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
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import android.view.WindowManager
import android.view.View
import android.view.LayoutInflater
import androidx.camera.view.PreviewView
import android.graphics.PixelFormat
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.Preview

class HandGestureService : LifecycleService() {

    companion object {
        private const val TAG = "HandGestureService"    // 日志标签
        private const val CHANNEL_ID = "hand_gesture_channel"   // 通知渠道ID
        const val ACTION_GESTURE = "com.tikctrl.app.ACTION_GESTURE"    // 广播动作
        const val EXTRA_GESTURE = "gesture"     // 广播中携带手势数据的键名

        val serviceRunning = MutableLiveData<Boolean>()
    }

    private var handLandmarker: HandLandmarker? = null
    private var cameraExecutor: ExecutorService? = null
    private val gestureClassifier = GestureClassifier()
    private var isDestroyed = false
    // Floating window
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var minimizedView: View? = null
    private var previewView: PreviewView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var minimizedLayoutParams: WindowManager.LayoutParams? = null
    private var prefs: android.content.SharedPreferences? = null
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "floating_alpha") {
            updateFloatingAlpha()
        } else if (key == "inference_delegate") {
            setupHandLandmarker()
        } else if (key == "mirror_mode") {
            applyFloatingPreviewMirror()
        } else if (key == "front_camera") {
            val useFrontCamera = prefs?.getBoolean("front_camera", true) ?: true
            currentCameraSelector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            mainHandler.post {
                try {
                    bindCameraUseCases()
                    applyFloatingPreviewMirror()
                } catch (e: Exception) {
                    Log.e(TAG, "Camera rebind on front_camera change failed: ${e.message}")
                }
            }
        }
    }
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
    private var isCreatingFloatingPreview = false
    private var lastAnalyzeTimeMs: Long = 0
    private val powerSavingIntervalMs: Long = 100 // 10 FPS = 100ms interval
    private var hasFallenBackToCpu = false
    private val pendingImageProxies = java.util.concurrent.ConcurrentLinkedQueue<ImageProxy>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HandGestureService onCreate() called")
        serviceRunning.postValue(true)
        cameraExecutor = Executors.newSingleThreadExecutor()
        prefs = getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        startForegroundService()
        setupHandLandmarker()
        
        val hasCameraPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasOverlayPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)
        
        Log.d(TAG, "onCreate - Camera: $hasCameraPermission, Overlay: $hasOverlayPermission")
        
        if (hasCameraPermission) {
            if (hasOverlayPermission) {
                createFloatingPreview()
            }
            startCamera()
        } else {
            Log.e(TAG, "Camera permission not granted! Service cannot start camera.")
            android.widget.Toast.makeText(this, getString(R.string.permission_camera_required), android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "HandGestureService onStartCommand() called")
        
        if (floatingView == null) {
            val hasCameraPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val hasOverlayPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)
            
            Log.d(TAG, "onStartCommand - Camera: $hasCameraPermission, Overlay: $hasOverlayPermission, floatingView: ${floatingView != null}")
            
            if (hasCameraPermission && hasOverlayPermission) {
                createFloatingPreview()
                mainHandler.post {
                    try { 
                        bindCameraUseCases()
                        applyFloatingPreviewMirror()
                    } catch (e: Exception) { Log.e(TAG, "bindCameraUseCases failed: ${e.message}") }
                }
            } else if (!hasCameraPermission) {
                Log.e(TAG, "Camera permission not granted!")
                android.widget.Toast.makeText(this, getString(R.string.permission_camera_required), android.widget.Toast.LENGTH_LONG).show()
                stopSelf()
            } else if (!hasOverlayPermission) {
                Log.w(TAG, "Overlay permission not granted, cannot create floating preview")
            }
        }
        
        return START_STICKY
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
        hasFallenBackToCpu = false
        try {
            // 初始化手势统计
            GestureStatistics.init(this)
            // 固定检测置信度阈值
            val minConfidence = 0.7f

            // 读取推理引擎设置
            val useGPU = prefs?.getInt("inference_delegate", 0) == 1
            // 省电模式强制CPU
            val powerSaving = prefs?.getBoolean("power_saving_mode", false) ?: false
            val delegate = if (useGPU && !powerSaving) Delegate.GPU else Delegate.CPU

            // 关闭旧的检测器
            try { handLandmarker?.close() } catch (_: Exception) {}

            val baseOptions = BaseOptions.builder()
                .setDelegate(delegate)
                .setModelAssetPath("hand_landmarker.task")  // 模型文件路径
                .build()

            // 手部关键点检测器选项
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)    // 设置基础配置
                .setNumHands(2) // 检测最多2只手
                .setMinHandDetectionConfidence(minConfidence)
                .setRunningMode(RunningMode.LIVE_STREAM)    // 实时流模式
                .setResultListener { result: HandLandmarkerResult, _: com.google.mediapipe.framework.image.MPImage ->
                    handleHandLandmarkerResult(result)  // 结果回调处理
                }
                .build()

            // 创建手部关键点检测器实例
            handLandmarker = HandLandmarker.createFromOptions(this, options)
            Log.d(TAG, "HandLandmarker initialized with delegate: $delegate, minConfidence: $minConfidence")
        } catch (e: Exception) {
            Log.e(TAG, "HandLandmarker init failed: ${e.message}")
            // GPU失败时回退到CPU
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
                    .setModelAssetPath("hand_landmarker.task")
                    .build()
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.7f)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { result: HandLandmarkerResult, _: com.google.mediapipe.framework.image.MPImage ->
                        handleHandLandmarkerResult(result)
                    }
                    .build()
                handLandmarker = HandLandmarker.createFromOptions(this, options)
                Log.w(TAG, "Falled back to CPU delegate after GPU init failed")
            } catch (e2: Exception) {
                Log.e(TAG, "CPU fallback also failed: ${e2.message}")
            }
        }
    }

    // 启动相机
    private fun startCamera() {
        // 读取前置摄像头设置
        val useFrontCamera = prefs?.getBoolean("front_camera", true) ?: true
        currentCameraSelector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            // 配置图像分析器，请求RGBA_8888格式以便高效转换为Bitmap
            // We'll bind the use cases via helper so we can rebind on camera switch
            mainHandler.post {
                try {
                    bindCameraUseCases()
                    applyFloatingPreviewMirror()
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind failed: ${e.message}")
                }
            }

        }, cameraExecutor!!)
    }
    
    private fun applyFloatingPreviewMirror() {
        val mirrorMode = prefs?.getBoolean("mirror_mode", true) ?: true
        val useFrontCamera = prefs?.getBoolean("front_camera", true) ?: true
        val shouldMirror = useFrontCamera && mirrorMode
        
        if (previewView != null) {
            previewView!!.post {
                try {
                    previewView!!.scaleX = if (shouldMirror) -1f else 1f
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply floating preview mirror: ${e.message}")
                }
            }
        }
    }

    // Bind Preview and Analysis according to currentCameraSelector and previewView availability
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val analyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analyzer.setAnalyzer(cameraExecutor!!) { imageProxy: ImageProxy ->
            try {
                if (isDestroyed || handLandmarker == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                // 省电模式：帧节流至10 FPS
                val powerSaving = prefs?.getBoolean("power_saving_mode", false) ?: false
                if (powerSaving) {
                    val now = System.currentTimeMillis()
                    if (now - lastAnalyzeTimeMs < powerSavingIntervalMs) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastAnalyzeTimeMs = now
                }

                // 使用图像帧的真实 timestamp，保证单调递增
                val timestamp = imageProxy.imageInfo.timestamp

                val mpImage = imageProxy.toMpImage()
                if (mpImage == null) {
                    Log.w(TAG, "ImageProxy -> MPImage conversion returned null")
                    imageProxy.close()
                    return@setAnalyzer
                }

                val landmarker = handLandmarker
                if (landmarker != null && !isDestroyed) {
                    // detectAsync 是异步的，MediaPipe 内部会持有 mpImage
                    // 所以不能立即 close imageProxy，需要在结果回调中释放
                    // 使用 closePool 延迟关闭
                    pendingImageProxies.add(imageProxy)
                    landmarker.detectAsync(mpImage, timestamp)
                } else {
                    imageProxy.close()
                }
            } catch (e: com.google.mediapipe.framework.MediaPipeException) {
                Log.e(TAG, "MediaPipe error: ${e.message}")
                imageProxy.close()

                // 首次遇到错误时，立即置空 handLandmarker 阻止后续帧使用旧 GPU 实例
                if (!hasFallenBackToCpu) {
                    hasFallenBackToCpu = true
                    Log.w(TAG, "MediaPipe error detected, falling back to CPU")
                    val oldLandmarker = handLandmarker
                    handLandmarker = null
                    // 不能立即 close，因为 detectAsync 可能还在另一个线程跑
                    // 延迟关闭，给 native graph 时间完成
                    mainHandler.postDelayed({
                        try { oldLandmarker?.close() } catch (_: Exception) {}
                        prefs?.edit()?.putInt("inference_delegate", 0)?.apply()
                        setupHandLandmarker()
                        try { bindCameraUseCases() } catch (_: Exception) {}
                    }, 500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analyzer error: ${e.message}")
                imageProxy.close()
            }
        }

        val cameraSelector = currentCameraSelector

        mainHandler.post {
            provider.unbindAll()
            Log.d(TAG, "bindCameraUseCases - floatingView=${floatingView != null}, previewView=${previewView != null}")
            if (floatingView != null && previewView != null) {
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                preview.setSurfaceProvider(previewView!!.surfaceProvider)
                try {
                    provider.bindToLifecycle(this, cameraSelector, preview, analyzer)
                    Log.d(TAG, "bindCameraUseCases - bound preview+analyzer successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "bindToLifecycle preview+analyzer failed: ${e.message}")
                }
            } else {
                Log.w(TAG, "bindCameraUseCases - floatingView or previewView is null, using analyzer-only mode")
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

    private fun createFloatingPreview() {
        // 防止重复创建悬浮窗
        if (floatingView != null || minimizedView != null || isCreatingFloatingPreview) {
            Log.d(TAG, "createFloatingPreview skipped: floatingView=${floatingView != null}, minimizedView=${minimizedView != null}, isCreating=$isCreatingFloatingPreview")
            return
        }
        
        isCreatingFloatingPreview = true
        var container: FrameLayout? = null
        
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)
            container = inflater.inflate(R.layout.float_preview, null) as FrameLayout

            previewView = container.findViewById(R.id.float_preview_view)

            val closeBtn = container.findViewById<ImageButton>(R.id.btn_close)
            closeBtn.setOnClickListener {
                stopSelf()
            }

            val switchBtn = container.findViewById<ImageButton>(R.id.btn_switch_camera)
            switchBtn.setOnClickListener {
                switchCamera()
            }

            val singleHandBtn = container.findViewById<ImageButton>(R.id.btn_single_hand)
            fun updateSingleHandIcon() {
                val mode = GestureMappingManager.getSingleHandMode(this)
                when (mode) {
                    GestureMappingManager.SingleHandMode.BOTH -> {
                        singleHandBtn.setImageResource(R.drawable.ic_baseline_hand_24)
                        singleHandBtn.contentDescription = "双手模式"
                    }
                    GestureMappingManager.SingleHandMode.LEFT -> {
                        singleHandBtn.setImageResource(R.drawable.ic_baseline_arrow_back_24)
                        singleHandBtn.contentDescription = "仅左手"
                    }
                    GestureMappingManager.SingleHandMode.RIGHT -> {
                        singleHandBtn.setImageResource(R.drawable.ic_baseline_arrow_forward_24)
                        singleHandBtn.contentDescription = "仅右手"
                    }
                }
            }
            updateSingleHandIcon()

            singleHandBtn.setOnClickListener {
                val current = GestureMappingManager.getSingleHandMode(this)
                val next = when (current) {
                    GestureMappingManager.SingleHandMode.BOTH -> GestureMappingManager.SingleHandMode.LEFT
                    GestureMappingManager.SingleHandMode.LEFT -> GestureMappingManager.SingleHandMode.RIGHT
                    GestureMappingManager.SingleHandMode.RIGHT -> GestureMappingManager.SingleHandMode.BOTH
                }
                GestureMappingManager.setSingleHandMode(this, next)
                updateSingleHandIcon()
                val modeName = when (next) {
                    com.tikctrl.app.GestureMappingManager.SingleHandMode.BOTH -> getString(R.string.floating_single_hand_both)
                    com.tikctrl.app.GestureMappingManager.SingleHandMode.LEFT -> getString(R.string.floating_single_hand_left)
                    com.tikctrl.app.GestureMappingManager.SingleHandMode.RIGHT -> getString(R.string.floating_single_hand_right)
                }
                android.widget.Toast.makeText(this, modeName, android.widget.Toast.LENGTH_SHORT).show()
            }

            val minimizeBtn = container.findViewById<ImageButton>(R.id.btn_minimize)

            val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            layoutParams = WindowManager.LayoutParams(
                480,
                640,
                type,
                flag,
                PixelFormat.TRANSLUCENT
            )
            layoutParams!!.gravity = Gravity.TOP or Gravity.START
            layoutParams!!.x = 50
            layoutParams!!.y = 200

            val prefs = getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
            val alphaPercent = prefs.getInt("floating_alpha", 70)
            layoutParams!!.alpha = alphaPercent / 100f

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
                            windowManager?.updateViewLayout(container!!, layoutParams)
                        } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }

            minimizeBtn.setOnClickListener {
                try {
                    val savedX = layoutParams!!.x
                    val savedY = layoutParams!!.y
                    windowManager?.removeView(container!!)
                    floatingView = null
                    previewView = null

                    val iconView = inflater.inflate(R.layout.float_icon, null) as LinearLayout

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
                    iconLp.alpha = alphaPercent / 100f
                    minimizedLayoutParams = iconLp

                    var iInitialX = 0
                    var iInitialY = 0
                    var iTouchX = 0f
                    var iTouchY = 0f
                    var isDragging = false
                    iconView.setOnTouchListener { _: View, event: MotionEvent ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                iInitialX = iconLp.x
                                iInitialY = iconLp.y
                                iTouchX = event.rawX
                                iTouchY = event.rawY
                                isDragging = false
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = (event.rawX - iTouchX).toInt()
                                val dy = (event.rawY - iTouchY).toInt()
                                if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                                    isDragging = true
                                    iconLp.x = iInitialX + dx
                                    iconLp.y = iInitialY + dy
                                    try { windowManager?.updateViewLayout(iconView, iconLp) } catch (_: Exception) {}
                                }
                                true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!isDragging) {
                                    iconView.performClick()
                                }
                                false
                            }
                            else -> false
                        }
                    }

                    iconView.setOnClickListener {
                        try {
                            windowManager?.removeView(iconView)
                        } catch (_: Exception) {}
                        minimizedView = null
                        createFloatingPreview()
                        try { 
                            bindCameraUseCases()
                            applyFloatingPreviewMirror()
                        } catch (_: Exception) {}
                    }

                    try {
                        windowManager?.addView(iconView, iconLp)
                        minimizedView = iconView
                        try { bindCameraUseCases() } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add minimized icon: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Minimize failed: ${e.message}")
                }
            }

            windowManager?.addView(container!!, layoutParams)
            floatingView = container
            previewView = container?.findViewById(R.id.float_preview_view)
            isCreatingFloatingPreview = false
            Log.d(TAG, "Floating preview created successfully, floatingView=$floatingView, previewView=$previewView")
            
            mainHandler.post {
                try { 
                    bindCameraUseCases()
                    applyFloatingPreviewMirror()
                } catch (e: Exception) { Log.e(TAG, "bindCameraUseCases after createFloatingPreview failed: ${e.message}") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create floating preview: ${e.message}", e)
            // 如果悬浮窗已经添加，需要移除
            try {
                container?.let { windowManager?.removeViewImmediate(it) }
            } catch (_: Exception) {}
            floatingView = null
            previewView = null
            isCreatingFloatingPreview = false
        }
    }

    private fun updateFloatingAlpha() {
        val alphaPercent = prefs?.getInt("floating_alpha", 70) ?: return
        val alphaValue = alphaPercent / 100f
        if (layoutParams != null && floatingView != null) {
            layoutParams!!.alpha = alphaValue
            try {
                windowManager?.updateViewLayout(floatingView, layoutParams)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update floating alpha: ${e.message}")
            }
        }
        if (minimizedLayoutParams != null && minimizedView != null) {
            minimizedLayoutParams!!.alpha = alphaValue
            try {
                windowManager?.updateViewLayout(minimizedView, minimizedLayoutParams)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update minimized icon alpha: ${e.message}")
            }
        }
    }

    private fun updateGestureStatus(status: String) {
        mainHandler.post {
            val statusView = floatingView?.findViewById<TextView>(R.id.tv_gesture_status)
            if (statusView != null) {
                statusView.text = status
            }
        }
    }

    private fun handleHandLandmarkerResult(result: HandLandmarkerResult) {
        // detectAsync 完成后，释放对应的 ImageProxy
        pendingImageProxies.poll()?.close()

        val mode = GestureMappingManager.getSingleHandMode(this)

        // result.landmarks() is a list of hands; handednesses() is parallel list of categories
        val landmarksList = result.landmarks()
        val handednessLists = result.handednesses()

        // Determine which hand index to use (or null to use default behavior)
        var selectedHandIndex: Int? = null
        if (mode != GestureMappingManager.SingleHandMode.BOTH) {
            // Search for matching handedness in all detected hands
            for (i in 0 until handednessLists.size) {
                val handCats = handednessLists[i]
                for (j in 0 until handCats.size) {
                    val name = handCats[j].categoryName()
                    if ((mode == GestureMappingManager.SingleHandMode.LEFT && name.equals("Left", true)) ||
                        (mode == GestureMappingManager.SingleHandMode.RIGHT && name.equals("Right", true))) {
                        selectedHandIndex = i
                        break
                    }
                }
                if (selectedHandIndex != null) break
            }
        }

        // If single-hand mode is enabled but no matching hand is detected, skip this frame
        if (mode != GestureMappingManager.SingleHandMode.BOTH && selectedHandIndex == null) {
            lastDetectedGesture = null
            consecutiveDetections = 0
            return
        }

        val gesture = if (selectedHandIndex != null && selectedHandIndex < landmarksList.size) {
            // Classify only the selected hand's landmarks
            gestureClassifier.classifySingleHand(landmarksList[selectedHandIndex])
        } else {
            // Default: classifier can pick first available
            gestureClassifier.classify(result)
        }
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

        updateGestureStatus("已识别: ${gesture.name}")
        Log.i(TAG, "Broadcasting gesture: ${gesture.name}")
        val intent = Intent(ACTION_GESTURE)
        intent.putExtra(EXTRA_GESTURE, gesture.name)
        intent.setPackage(packageName)
        sendBroadcast(intent)

        lastSentGesture = gesture
        lastSentTimeMs = now
        consecutiveDetections = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroyed = true
        Log.d(TAG, "HandGestureService onDestroy() called")
        serviceRunning.postValue(false)

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) { /* ignore */ }

        // 等待 analyzer 线程处理完 pending 帧
        try {
            cameraExecutor?.shutdown()
            cameraExecutor?.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
            cameraExecutor = null
        } catch (e: Exception) { /* ignore */ }

        // 释放所有未处理的 ImageProxy
        pendingImageProxies.forEach { it.close() }
        pendingImageProxies.clear()

        try {
            handLandmarker?.close()
            handLandmarker = null
        } catch (e: Exception) { /* ignore */ }

        // Remove floating views and preview safely
        try {
            floatingView?.let { windowManager?.removeViewImmediate(it) }
        } catch (e: Exception) { /* ignore */ }

        try {
            minimizedView?.let { windowManager?.removeViewImmediate(it) }
        } catch (e: Exception) { /* ignore */ }

        try {
            prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) { /* ignore */ }

        try {
            previewView?.let { pv ->
                val parent = pv.parent
                if (parent is ViewGroup) {
                    try { parent.removeView(pv) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { /* ignore */ }

        // Clear references to help GC
        floatingView = null
        minimizedView = null
        previewView = null
        layoutParams = null
        cameraProvider = null
        windowManager = null
        isCreatingFloatingPreview = false

        try {
            stopForeground(true)
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
