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
package com.google.mediapipe.examples.handlandmarker

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
import com.google.mediapipe.examples.handlandmarker.databinding.ActivityMainBinding
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat

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
                // Permission denied - we could show a dialog explaining why it's needed
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
