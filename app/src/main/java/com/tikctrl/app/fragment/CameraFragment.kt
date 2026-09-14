/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tikctrl.app.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.hardware.camera2.CaptureRequest
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.tikctrl.app.BuildConfig
import com.tikctrl.app.GestureClassifier
import com.tikctrl.app.GestureMappingManager
import com.tikctrl.app.HandGestureService
import com.tikctrl.app.HandGestureService.Companion
import com.tikctrl.app.HandLandmarkerHelper
import com.tikctrl.app.MainViewModel
import com.tikctrl.app.R
import com.tikctrl.app.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), HandLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Hand Landmarker"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    // FPS calculation
    private var frameCount = 0
    private var lastAnalyzeTimeMs: Long = 0
    private val powerSavingIntervalMs: Long = 100 // 10 FPS = 100ms interval
    private var lastFpsCalculationTime = System.currentTimeMillis()
    private var currentFps = 0

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // Start the HandLandmarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (handLandmarkerHelper.isClose()) {
                handLandmarkerHelper.setupHandLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if(this::handLandmarkerHelper.isInitialized) {
            viewModel.setMaxHands(handLandmarkerHelper.maxNumHands)
            viewModel.setMinHandDetectionConfidence(handLandmarkerHelper.minHandDetectionConfidence)
            viewModel.setMinHandTrackingConfidence(handLandmarkerHelper.minHandTrackingConfidence)
            viewModel.setMinHandPresenceConfidence(handLandmarkerHelper.minHandPresenceConfidence)
            viewModel.setDelegate(handLandmarkerHelper.currentDelegate)

            // Close the HandLandmarkerHelper and release resources
            backgroundExecutor.execute { handLandmarkerHelper.clearHandLandmarker() }
        }

        // 通知 HandGestureService 重新绑定相机 use cases
        // CameraFragment 的 bindCameraUseCases 调用了 unbindAll，会解绑 service 的 use cases
        // 离开预览页时需要让 service 恢复相机绑定，否则悬浮窗画面会停止
        try {
            val intent = android.content.Intent(HandGestureService.ACTION_REBIND_CAMERA)
            intent.setPackage(requireContext().packageName)
            requireContext().sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ACTION_REBIND_CAMERA: ${e.message}")
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Create the HandLandmarkerHelper that will handle the inference
        backgroundExecutor.execute {
            val prefs = requireContext().getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
            val useGPU = prefs.getInt("inference_delegate", 0) == 1
            val powerSaving = prefs.getBoolean("power_saving_mode", false)
            val delegate = if (useGPU && !powerSaving) HandLandmarkerHelper.DELEGATE_GPU else HandLandmarkerHelper.DELEGATE_CPU

            handLandmarkerHelper = HandLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                maxNumHands = viewModel.currentMaxHands,
                currentDelegate = delegate,
                handLandmarkerHelperListener = this
            )
        }

        // Attach listeners to UI control widgets
        initBottomSheetControls()
    }

    private fun initBottomSheetControls() {
        // init bottom sheet settings
        fragmentCameraBinding.bottomSheetLayout.maxHandsValue.text =
            viewModel.currentMaxHands.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandPresenceConfidence
            )

        // When clicked, lower hand detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandDetectionConfidence >= 0.2) {
                handLandmarkerHelper.minHandDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandDetectionConfidence <= 0.8) {
                handLandmarkerHelper.minHandDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower hand tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandTrackingConfidence >= 0.2) {
                handLandmarkerHelper.minHandTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandTrackingConfidence <= 0.8) {
                handLandmarkerHelper.minHandTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower hand presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandPresenceConfidence >= 0.2) {
                handLandmarkerHelper.minHandPresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandPresenceConfidence <= 0.8) {
                handLandmarkerHelper.minHandPresenceConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, reduce the number of hands that can be detected at a
        // time
        fragmentCameraBinding.bottomSheetLayout.maxHandsMinus.setOnClickListener {
            if (handLandmarkerHelper.maxNumHands > 1) {
                handLandmarkerHelper.maxNumHands--
                updateControlsUi()
            }
        }

        // When clicked, increase the number of hands that can be detected
        // at a time
        fragmentCameraBinding.bottomSheetLayout.maxHandsPlus.setOnClickListener {
            if (handLandmarkerHelper.maxNumHands < 2) {
                handLandmarkerHelper.maxNumHands++
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference.
        // Current options are CPU and GPU
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        handLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch(e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "HandLandmarkerHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    // Update the values displayed in the bottom sheet. Reset Handlandmarker
    // helper.
    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxHandsValue.text =
            handLandmarkerHelper.maxNumHands.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandPresenceConfidence
            )

        // Needs to be cleared instead of reinitialized because the GPU
        // delegate needs to be initialized on the thread using it when applicable
        backgroundExecutor.execute {
            handLandmarkerHelper.clearHandLandmarker()
            handLandmarkerHelper.setupHandLandmarker()
        }
        fragmentCameraBinding.overlay.clear()
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        // 读取设置
        val prefs = requireContext().getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
        val useFrontCamera = prefs.getBoolean("front_camera", true)
        cameraFacing = if (useFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
                
                // 应用镜像设置
                applyPreviewMirror()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    private fun applyPreviewMirror() {
        val prefs = requireContext().getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
        val mirrorMode = prefs.getBoolean("mirror_mode", true)
        val isFront = cameraFacing == CameraSelector.LENS_FACING_FRONT
        
        fragmentCameraBinding.viewFinder.post {
            try {
                val textureView = fragmentCameraBinding.viewFinder.getChildAt(0) as? android.view.TextureView
                if (textureView != null) {
                    val matrix = android.graphics.Matrix()
                    if (isFront && mirrorMode) {
                        // 水平翻转以实现镜像效果
                        val centerX = textureView.width / 2f
                        val centerY = textureView.height / 2f
                        matrix.setScale(-1f, 1f, centerX, centerY)
                    }
                    textureView.setTransform(matrix)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply preview mirror: ${e.message}")
            }
        }
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .also { builder ->
                // 限制相机输出帧率 15-20fps，降低推理负载，减少发热
                Camera2Interop.Extender(builder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(15, 20)
                )
            }
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .also { builder ->
                    Camera2Interop.Extender(builder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(15, 20)
                    )
                }
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        // 省电模式：帧节流至10 FPS
                        val prefs = requireContext().getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
                        if (prefs.getBoolean("power_saving_mode", false)) {
                            val now = System.currentTimeMillis()
                            if (now - lastAnalyzeTimeMs < powerSavingIntervalMs) {
                                image.close()
                                return@setAnalyzer
                            }
                            lastAnalyzeTimeMs = now
                        }
                        detectHand(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectHand(imageProxy: ImageProxy) {
        handLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after hand have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)

                fragmentCameraBinding.overlay.setResults(
                    resultBundle.results.first(),
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                fragmentCameraBinding.overlay.invalidate()

                calculateAndUpdateFps()
            }
        }

        try {
            val classifier = GestureClassifier()
            val result = resultBundle.results.first()
            
            val mode = GestureMappingManager.getSingleHandMode(requireContext())
            var selectedHandIndex: Int? = null
            
            if (mode != GestureMappingManager.SingleHandMode.BOTH) {
                val landmarksList = result.landmarks()
                val handednessLists = result.handednesses()
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
            
            if (mode != GestureMappingManager.SingleHandMode.BOTH && selectedHandIndex == null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipping frame - no matching hand for single hand mode: $mode")
                return
            }
            
            val gesture = if (selectedHandIndex != null && selectedHandIndex < result.landmarks().size) {
                classifier.classifySingleHand(result.landmarks()[selectedHandIndex])
            } else {
                classifier.classify(result)
            }
            
            if (BuildConfig.DEBUG) Log.d(TAG, "Classified gesture (UI path): $gesture")

            // 更新 UI 显示当前手势
            activity?.runOnUiThread {
                if (_fragmentCameraBinding != null) {
                    fragmentCameraBinding.tvCurrentGesture.text = getGestureDisplayName(gesture)
                }
            }

            // 预览页面只显示手势名称，不执行操作
            // 手势执行由 HandGestureService 负责

            // 打印指尖坐标，方便调试
            if (BuildConfig.DEBUG) {
                try {
                    val landmarks = resultBundle.results.first().landmarks().firstOrNull()
                    if (landmarks != null) {
                        val tips = listOf(4, 8, 12, 16, 20).map { i ->
                            "#${i}:(x=${landmarks[i].x()},y=${landmarks[i].y()})"
                        }
                        Log.d(TAG, "Fingertips: ${tips.joinToString(",")}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to log fingertips: ${e.message}")
                }
            }

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to classify/broadcast from UI path: ${e.message}")
        }
    }


    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
//            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == HandLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    HandLandmarkerHelper.DELEGATE_CPU, false
//                    HandLandmarkerHelper.DELEGATE_GPU, false
                )
            }
        }
    }

    private fun getGestureDisplayName(gesture: GestureClassifier.Gesture): String {
        return when (gesture) {
            GestureClassifier.Gesture.NONE -> getString(R.string.gesture_none)
            GestureClassifier.Gesture.MIDDLE_FINGER -> getString(R.string.gesture_middle_finger)
            GestureClassifier.Gesture.PINKY_FINGER -> getString(R.string.gesture_pinky_finger)
            GestureClassifier.Gesture.INDEX_FINGER -> getString(R.string.gesture_index_finger)
            GestureClassifier.Gesture.PEACE_V -> getString(R.string.gesture_peace_v)
            GestureClassifier.Gesture.INDEX_MIDDLE_RING -> getString(R.string.gesture_index_middle_ring)
            GestureClassifier.Gesture.INDEX_MIDDLE_RING_PINKY -> getString(R.string.gesture_index_middle_ring_pinky)
            GestureClassifier.Gesture.SPIDER_MAN_SHOOTER -> getString(R.string.gesture_spider_man_shooter)
            GestureClassifier.Gesture.SPIDER_SHOOTER_NO_THUMB -> getString(R.string.gesture_spider_shooter_no_thumb)
            GestureClassifier.Gesture.OK -> getString(R.string.gesture_ok)
            GestureClassifier.Gesture.THUMB -> getString(R.string.gesture_thumb)
            GestureClassifier.Gesture.Aki_FOX_DEVIL -> getString(R.string.gesture_fox)
            GestureClassifier.Gesture.SIXSIXSIX -> getString(R.string.gesture_sixsixsix)
        }
    }

    private fun calculateAndUpdateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsCalculationTime

        if (elapsed >= 1000) {
            currentFps = (frameCount * 1000 / elapsed).toInt()
            frameCount = 0
            lastFpsCalculationTime = now

            fragmentCameraBinding.tvFps.text = "${currentFps}\nFPS"
        }
    }
}
