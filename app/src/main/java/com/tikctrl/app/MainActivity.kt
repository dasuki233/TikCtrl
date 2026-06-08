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
package com.tikctrl.app

import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import android.content.ComponentName
import android.text.TextUtils
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.tikctrl.app.databinding.ActivityMainBinding
import android.net.Uri
import android.os.Build
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.content.SharedPreferences
import androidx.appcompat.widget.SwitchCompat
import android.graphics.Color

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding
    private val viewModel : MainViewModel by viewModels()
    private var startedGestureService = false
    private val REQ_CAMERA_PERM = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only start the HandGestureService if we have overlay permission and camera permission.
        // Otherwise request permissions or overlay first.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)) {
            // check camera permission
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startHandGestureServiceIfNeeded()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA_PERM)
            }
        } else {
            // Ask user to grant overlay permission. They must return to the app and
            // the onResume will attempt to start the service again.
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        // Check if our AccessibilityService is enabled; if not, prompt the user to enable it
        val expectedComponent = ComponentName(this, GestureActionService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val isEnabled = !enabledServices.isNullOrEmpty() && enabledServices.contains(expectedComponent.flattenToString())
        if (!isEnabled) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage("为了让手势触发系统操作，请在设置中启用应用的无障碍服务。\n\n点击前往开启。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .show()
        }

        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController



        activityMainBinding.navigation.setupWithNavController(navController)
        activityMainBinding.navigation.setOnNavigationItemReselectedListener {
            // ignore the reselection
        }

        // FAB: 打开手势映射界面
        try {
            val fab = activityMainBinding.root.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_gesture_mapping)
            fab?.setOnClickListener {
                startActivity(Intent(this, GestureMappingActivity::class.java))
            }
        } catch (e: Exception) {
            // 如果没有找到 FAB（向后兼容），忽略
        }

        // 权限检测按钮，用户可点击以检查/请求相机权限
        try {
            val checkBtn = activityMainBinding.root.findViewById<android.widget.Button>(R.id.btn_check_permissions)
            checkBtn?.setOnClickListener {
                checkAndRequestCameraAndOverlayPermission()
            }
        } catch (e: Exception) {
            // ignore if not present
        }

        // Visual feedback switch and color button
        try {
            val prefs: SharedPreferences = getSharedPreferences("gesture_prefs", MODE_PRIVATE)
            val switch = activityMainBinding.root.findViewById<SwitchCompat>(R.id.switch_visual_feedback)
            val colorBtn = activityMainBinding.root.findViewById<android.widget.Button>(R.id.btn_feedback_color)

            val colors = listOf(Color.parseColor("#00FF00"), Color.parseColor("#FF0000"), Color.parseColor("#FFFF00"), Color.parseColor("#00FFFF"), Color.parseColor("#FFFFFF"))
            var colorIndex = prefs.getInt("feedback_color_index", 0).coerceIn(0, colors.size - 1)

            val enabled = prefs.getBoolean("visual_feedback_enabled", true)
            switch?.isChecked = enabled
            colorBtn?.isEnabled = enabled

            fun updateColorButton() {
                try {
                    val c = colors[colorIndex]
                    colorBtn?.setBackgroundColor(c)
                    colorBtn?.setTextColor(if (isColorDark(c)) Color.WHITE else Color.BLACK)
                    colorBtn?.text = String.format("#%06X", 0xFFFFFF and c)
                } catch (_: Exception) {}
            }

            updateColorButton()

            switch?.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("visual_feedback_enabled", isChecked).apply()
                colorBtn?.isEnabled = isChecked
            }

            colorBtn?.setOnClickListener {
                colorIndex = (colorIndex + 1) % colors.size
                prefs.edit().putInt("feedback_color_index", colorIndex).apply()
                updateColorButton()
            }

        } catch (e: Exception) {
            // ignore if views not present
        }
    }

    private fun isColorDark(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // Perceived luminance
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
        return luminance < 150
    }

    private fun checkAndRequestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startHandGestureServiceIfNeeded()
            return
        }

        // If user previously denied with "Don't ask again", shouldShowRequestPermissionRationale returns false
        val shouldExplain = ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.CAMERA)
        if (shouldExplain) {
            // Show rationale and re-request
            AlertDialog.Builder(this)
                .setTitle("需要相机权限")
                .setMessage("应用需要相机权限来检测手势。请允许相机权限以继续。")
                .setPositiveButton("允许") { _, _ ->
                    ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA_PERM)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // Possibly first-time or permanently denied. Request permission first; if permanently denied, guide to settings.
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA_PERM)
        }
    }

    private fun checkAndRequestCameraAndOverlayPermission() {
        // First ensure overlay permission (悬浮窗) is granted on Android M+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("检测结果需要在其他应用上方显示悬浮窗，请允许“在其他应用上显示”权限。点击前往设置。")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 如果悬浮窗已允许，继续检测无障碍服务是否已开启
        val expectedComponent = ComponentName(this, GestureActionService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val isAccessibilityEnabled = !enabledServices.isNullOrEmpty() && enabledServices.contains(expectedComponent.flattenToString())
        if (!isAccessibilityEnabled) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage("为了让手势触发系统操作，请在设置中启用应用的无障碍服务。点击前往开启。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .show()
            return
        }

        // If overlay and accessibility are allowed, continue to check/request camera permission
        checkAndRequestCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        // If user granted overlay permission while away from the app, start the service now.
        if (!startedGestureService && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startHandGestureServiceIfNeeded()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA_PERM)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Only start if overlay is available too
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                    startHandGestureServiceIfNeeded()
                }
            } else {
                // Permission denied - show guidance and, if permanently denied, offer to open app settings
                val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.CAMERA)
                if (permanentlyDenied) {
                    AlertDialog.Builder(this)
                        .setTitle("相机权限被拒绝")
                        .setMessage("您已拒绝相机权限且选择不再询问。请前往应用设置手动开启相机权限。")
                        .setPositiveButton("去设置") { _, _ ->
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                            startActivity(intent)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("需要相机权限")
                        .setMessage("应用需要相机权限来检测手势。请允许相机权限以继续。")
                        .setPositiveButton("重试") { _, _ ->
                            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA_PERM)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    private fun startHandGestureServiceIfNeeded() {
        if (startedGestureService) return
        val intent = Intent(this, HandGestureService::class.java)
        ContextCompat.startForegroundService(this, intent)
        startedGestureService = true
    }

    override fun onBackPressed() {
        finish()
    }
}
