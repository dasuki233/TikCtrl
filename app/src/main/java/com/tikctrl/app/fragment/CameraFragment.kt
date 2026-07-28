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
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
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
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
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

    // Gesture debounce / stability controls  修改命令执行时长
    private var lastDetectedGesture: GestureClassifier.Gesture? = null
    private var consecutiveDetections = 0
    private val requiredConsecutiveDetections = 3 // was 3 -> lower for faster response
    private var lastSentGesture: GestureClassifier.Gesture? = null
    private var lastSentTimeMs: Long = 0
    private val gestureCooldownMs: Long = 1500 // was 1500 -> shorter cooldown for faster repeat

    // FPS calculation
    private var frameCount = 0
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
            handLandmarkerHelper = HandLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                maxNumHands = viewModel.currentMaxHands,
                currentDelegate = viewModel.currentDelegate,
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
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
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
                Log.d(TAG, "Skipping frame - no matching hand for single hand mode: $mode")
                return
            }
            
            val gesture = if (selectedHandIndex != null && selectedHandIndex < result.landmarks().size) {
                classifier.classifySingleHand(result.landmarks()[selectedHandIndex])
            } else {
                classifier.classify(result)
            }
            
            Log.d(TAG, "Classified gesture (UI path): $gesture")

            activity?.runOnUiThread {
                if (_fragmentCameraBinding != null) {
                    fragmentCameraBinding.tvCurrentGesture.text = getGestureDisplayName(gesture)
                }
            }

            // 检查是否在冷却期内（1.5秒）
            val now = System.currentTimeMillis()
            if ((now - lastSentTimeMs) < 1500) {
                Log.d(TAG, "⏳ 手势冷却中，跳过识别: $gesture")
                return  // 在冷却期，不再发送广播
            }

            if (gesture != GestureClassifier.Gesture.NONE) {
                // 记录当前时间，进入冷却状态
                lastSentTimeMs = now

                // 发送广播
                val intent = android.content.Intent(
                    com.tikctrl.app.HandGestureService.ACTION_GESTURE
                )
                intent.putExtra(
                    com.tikctrl.app.HandGestureService.EXTRA_GESTURE,
                    gesture.name
                )
                intent.setPackage(requireContext().packageName)
                requireContext().sendBroadcast(intent)

                Log.d(TAG, "✅ 检测到手势，发送广播: ${gesture.name}")
            }

            // 打印指尖坐标，方便调试
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

        } catch (e: Exception) {
            Log.w(TAG, "Failed to classify/broadcast from UI path: ${e.message}")
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
            GestureClassifier.Gesture.NONE -> "未检测到手势"
            GestureClassifier.Gesture.MIDDLE_FINGER -> "点赞"
            GestureClassifier.Gesture.PINKY_FINGER -> "取消点赞"
            GestureClassifier.Gesture.INDEX_FINGER -> "下一个"
            GestureClassifier.Gesture.PEACE_V -> "比耶"
            GestureClassifier.Gesture.INDEX_MIDDLE_RING -> "手势三"
            GestureClassifier.Gesture.INDEX_MIDDLE_RING_PINKY -> "手势四"
            GestureClassifier.Gesture.SPIDER_MAN_SHOOTER -> "蜘蛛侠"
            GestureClassifier.Gesture.SPIDER_SHOOTER_NO_THUMB -> "无拇指蜘蛛侠"
            GestureClassifier.Gesture.OK -> "OK"
            GestureClassifier.Gesture.THUMB -> "大拇指"
            GestureClassifier.Gesture.Aki_FOX_DEVIL -> "秋的味道"
            GestureClassifier.Gesture.SIXSIXSIX -> "666"
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
