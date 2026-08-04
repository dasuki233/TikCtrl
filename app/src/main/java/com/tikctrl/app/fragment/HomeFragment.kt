package com.tikctrl.app.fragment

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.google.android.material.snackbar.Snackbar
import com.tikctrl.app.GestureClassifier
import com.tikctrl.app.GestureMappingManager
import com.tikctrl.app.GestureStatistics
import com.tikctrl.app.HandGestureService
import com.tikctrl.app.MainActivity
import com.tikctrl.app.R

class HomeFragment : Fragment() {
    private var isUpdatingSwitch = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_home, container, false)

        // 初始化手势统计
        GestureStatistics.init(requireContext())

        val serviceSwitch = root.findViewById<SwitchCompat>(R.id.switch_visual_feedback)

        // 观察服务状态变化，实时更新 UI
        HandGestureService.serviceRunning.observe(viewLifecycleOwner, Observer { running ->
            isUpdatingSwitch = true
            serviceSwitch.isChecked = running
            isUpdatingSwitch = false
        })

        serviceSwitch.apply {
            isChecked = isServiceRunning(HandGestureService::class.java)
            setOnCheckedChangeListener { _, checked ->
                if (isUpdatingSwitch) return@setOnCheckedChangeListener

                val activity = activity as? MainActivity
                if (activity == null) {
                    android.widget.Toast.makeText(requireContext(), "无法获取主界面", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                
                if (checked) {
                    val hasCameraPermission = ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    val hasOverlayPermission = android.provider.Settings.canDrawOverlays(requireContext())
                    
                    if (hasCameraPermission && hasOverlayPermission) {
                        activity.startHandGestureService()
                        android.widget.Toast.makeText(requireContext(), "悬浮窗已开启", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        isChecked = false
                        if (!hasCameraPermission) {
                            android.widget.Toast.makeText(requireContext(), "请先授予相机权限", android.widget.Toast.LENGTH_SHORT).show()
                            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1001)
                        } else if (!hasOverlayPermission) {
                            android.widget.Toast.makeText(requireContext(), "请先授予悬浮窗权限", android.widget.Toast.LENGTH_SHORT).show()
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${requireContext().packageName}"))
                            startActivity(intent)
                        }
                    }
                } else {
                    activity.stopHandGestureService()
                    android.widget.Toast.makeText(requireContext(), "悬浮窗已关闭", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 更新统计数据
        updateStatistics(root)

        // 点击 Recognitions 显示历史记录
        root.findViewById<LinearLayout>(R.id.layout_recognitions).setOnClickListener {
            showHistoryDialog()
        }

        // 点击总识别次数显示详情
        root.findViewById<LinearLayout>(R.id.layout_accuracy).setOnClickListener {
            showHistoryDialog()
        }

        val mappingContainer = root.findViewById<LinearLayout>(R.id.home_mapping_container)

        // 定义所有 12 个手势
        val gestures = listOf(
            GestureClassifier.Gesture.MIDDLE_FINGER,
            GestureClassifier.Gesture.PINKY_FINGER,
            GestureClassifier.Gesture.INDEX_FINGER,
            GestureClassifier.Gesture.PEACE_V,
            GestureClassifier.Gesture.INDEX_MIDDLE_RING,
            GestureClassifier.Gesture.INDEX_MIDDLE_RING_PINKY,
            GestureClassifier.Gesture.SPIDER_MAN_SHOOTER,
            GestureClassifier.Gesture.SPIDER_SHOOTER_NO_THUMB,
            GestureClassifier.Gesture.OK,
            GestureClassifier.Gesture.THUMB,
            GestureClassifier.Gesture.Aki_FOX_DEVIL,
            GestureClassifier.Gesture.SIXSIXSIX
        )

        gestures.forEach { gesture ->
            mappingContainer.addView(createMappingRow(gesture))
        }

        // Reset All 按钮 - 确认后重置所有手势为 None
        root.findViewById<android.widget.Button>(R.id.btn_reset_all).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.dialog_reset_confirm_title))
                .setMessage(getString(R.string.dialog_reset_confirm_message))
                .setPositiveButton(getString(R.string.confirm)) { dialog, _ ->
                    gestures.forEach { gesture ->
                        GestureMappingManager.setActionForGesture(requireContext(), gesture, GestureMappingManager.Action.NONE)
                    }
                    // 刷新页面
                    mappingContainer.removeAllViews()
                    gestures.forEach { gesture ->
                        mappingContainer.addView(createMappingRow(gesture))
                    }
                    // 显示反馈
                    Snackbar.make(it, getString(R.string.dialog_reset_done), Snackbar.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        GestureStatistics.init(requireContext())
        view?.let { updateStatistics(it) }
        isUpdatingSwitch = true
        view?.findViewById<SwitchCompat>(R.id.switch_visual_feedback)?.isChecked = isServiceRunning(HandGestureService::class.java)
        isUpdatingSwitch = false
    }

    private fun updateStatistics(root: View) {
        // 更新 Recognitions（今日滑动次数）
        val todayCount = GestureStatistics.getTodayCount()
        root.findViewById<TextView>(R.id.tv_recognitions).text = todayCount.toString()

        // 更新总识别次数
        val totalCount = GestureStatistics.getTotalCount()
        root.findViewById<TextView>(R.id.tv_total_count).text = totalCount.toString()
    }

    private fun showHistoryDialog() {
        val history = GestureStatistics.getHistory(7)
        val todayCount = GestureStatistics.getTodayCount()
        val totalCount = GestureStatistics.getTotalCount()

        // 使用自定义布局
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_history, null)
        
        // 设置今日统计
        dialogView.findViewById<TextView>(R.id.tv_today_count).text = todayCount.toString()
        dialogView.findViewById<TextView>(R.id.tv_total_count).text = "Total: $totalCount"

        // 填充历史记录
        val historyContainer = dialogView.findViewById<LinearLayout>(R.id.history_container)
        val maxCount = history.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1

        history.forEach { (date, count) ->
            val itemView = LayoutInflater.from(requireContext()).inflate(R.layout.item_history, historyContainer, false)
            itemView.findViewById<TextView>(R.id.tv_date).text = date
            itemView.findViewById<TextView>(R.id.tv_count).text = "$count"
            
            // 设置进度条宽度（基于最大值）
            val progressView = itemView.findViewById<View>(R.id.view_progress)
            val emptyView = itemView.findViewById<View>(R.id.view_empty)
            val ratio = if (maxCount > 0) count.toFloat() / maxCount else 0f
            progressView.layoutParams = LinearLayout.LayoutParams(0, 6).apply { weight = ratio }
            emptyView.layoutParams = LinearLayout.LayoutParams(0, 6).apply { weight = 1f - ratio }
            
            // 今日高亮
            if (date == history.lastOrNull()?.first) {
                itemView.findViewById<TextView>(R.id.tv_date).setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.color_primary)
                )
                itemView.findViewById<TextView>(R.id.tv_count).setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.color_primary)
                )
            }
            
            historyContainer.addView(itemView)
        }

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .create()
            .show()
    }

    private fun createMappingRow(gesture: GestureClassifier.Gesture): View {
        val context = requireContext()
        val action = GestureMappingManager.getActionForGesture(context, gesture)
        val gestureName = GestureMappingManager.getGestureDisplayName(context, gesture)

        // 获取动作列表用于 Spinner
        val actionList = GestureMappingManager.getAllActionsForDisplay()
        val actionLabels = actionList.map { GestureMappingManager.getDisplayName(context, it) }

        // 容器（垂直布局）
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(10), dp(10))
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_card_small)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 手势名称
        row.addView(TextView(context).apply {
            text = gestureName
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.color_primary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        // 动作名称
        row.addView(TextView(context).apply {
            val currentActionName = GestureMappingManager.getDisplayName(context, action)
            text = currentActionName
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.color_text_primary))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(12), 0, 0, 0)
            }
            tag = "actionText" // 用于后续更新
        })

        // Spinner 下拉框
        val spinner = Spinner(context, Spinner.MODE_DROPDOWN).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            adapter = ArrayAdapter(context, R.layout.spinner_item_dark, actionLabels).apply {
                setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
            }
            background = ContextCompat.getDrawable(context, R.drawable.bg_spinner_dark)
            setSelection(actionList.indexOf(action).coerceAtLeast(0))
        }

        // Share 目标输入框（仅当动作为 SHARE 时显示）
        val paramInput = android.widget.EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(18), dp(4), dp(18), 0) }
            hint = "分享目标（可选），用逗号隔开"
            textSize = 13f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = if (action == GestureMappingManager.Action.SHARE) android.view.View.VISIBLE else android.view.View.GONE
            setText(GestureMappingManager.getActionParam(context, gesture) ?: "")
        }

        // Spinner 选择监听
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedAction = actionList[position]
                GestureMappingManager.setActionForGesture(context, gesture, selectedAction)
                // 更新动作名称显示
                row.findViewWithTag<TextView>("actionText")?.text = GestureMappingManager.getDisplayName(context, selectedAction)
                // 显示/隐藏 share 目标输入框
                paramInput.visibility = if (selectedAction == GestureMappingManager.Action.SHARE) android.view.View.VISIBLE else android.view.View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 监听输入框内容变化
        paramInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim()
                if (text.isNullOrEmpty()) {
                    GestureMappingManager.setActionParam(context, gesture, null)
                } else {
                    GestureMappingManager.setActionParam(context, gesture, text)
                }
            }
        })

        row.addView(spinner)
        container.addView(row)
        container.addView(paramInput)

        return container
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = requireContext().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                val activity = activity as? MainActivity
                if (activity != null && android.provider.Settings.canDrawOverlays(requireContext())) {
                    activity.startHandGestureService()
                    isUpdatingSwitch = true
                    view?.findViewById<SwitchCompat>(R.id.switch_visual_feedback)?.isChecked = true
                    isUpdatingSwitch = false
                    android.widget.Toast.makeText(requireContext(), "悬浮窗已开启", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(requireContext(), "请先授予悬浮窗权限", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                android.widget.Toast.makeText(requireContext(), "相机权限被拒绝", android.widget.Toast.LENGTH_SHORT).show()
                view?.findViewById<SwitchCompat>(R.id.switch_visual_feedback)?.isChecked = false
            }
        }
    }
}
