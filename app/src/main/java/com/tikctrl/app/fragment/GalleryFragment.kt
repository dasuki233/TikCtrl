package com.tikctrl.app.fragment

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.tikctrl.app.GestureActionService
import com.tikctrl.app.GestureStatistics
import com.tikctrl.app.MainActivity
import com.tikctrl.app.MainViewModel
import com.tikctrl.app.R

class GalleryFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private val THEME_MODE_SYSTEM = 0
    private val THEME_MODE_LIGHT = 1
    private val THEME_MODE_DARK = 2

    private val COLOR_DEFAULT = "#12BCA5"
    private val COLOR_BLUE = "#1887F2"
    private val COLOR_PURPLE = "#7C3AED"
    private val COLOR_ORANGE = "#FF9518"
    private val COLOR_RED = "#FF4F42"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_gallery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化手势统计
        GestureStatistics.init(requireContext())

        val prefs = requireContext().getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)

        bindPercentSeekBar(
            view.findViewById(R.id.seek_alpha),
            view.findViewById(R.id.tv_alpha_value),
            prefs.getInt("floating_alpha", 70)
        ) { value ->
            prefs.edit().putInt("floating_alpha", value).apply()
        }

        bindSwitch(view.findViewById(R.id.switch_mirror), prefs, "mirror_mode", true)
        bindSwitch(view.findViewById(R.id.switch_front_camera), prefs, "front_camera", true)

        view.findViewById<TextView>(R.id.btn_check_permissions).setOnClickListener {
            (requireActivity() as? com.tikctrl.app.MainActivity)?.checkAndRequestCameraAndOverlayPermission()
        }

        updatePermissionStatus(view)

        bindSingleHandMode(view)

        bindThemeMode(view)
        bindThemeColor(view)
    }

    private fun bindThemeMode(view: View) {
        val prefs = requireContext().getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
        val tvThemeMode = view.findViewById<TextView>(R.id.tv_theme_mode)
        val currentMode = prefs.getInt("theme_mode", THEME_MODE_SYSTEM)
        updateThemeModeText(tvThemeMode, currentMode)

        tvThemeMode.setOnClickListener {
            val modes = arrayOf("跟随系统", "浅色", "深色")
            AlertDialog.Builder(requireContext())
                .setTitle("主题模式")
                .setSingleChoiceItems(modes, currentMode) { dialog, which ->
                    prefs.edit().putInt("theme_mode", which).apply()
                    updateThemeModeText(tvThemeMode, which)
                    dialog.dismiss()
                    recreateActivity()
                }
                .show()
        }
    }

    private fun updateThemeModeText(textView: TextView, mode: Int) {
        val modeName = when (mode) {
            THEME_MODE_SYSTEM -> "跟随系统"
            THEME_MODE_LIGHT -> "浅色"
            THEME_MODE_DARK -> "深色"
            else -> "跟随系统"
        }
        textView.text = "主体模式                                                 $modeName  >"
    }

    private fun bindThemeColor(view: View) {
        val prefs = requireContext().getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE)
        val currentColor = prefs.getString("theme_color", COLOR_DEFAULT) ?: COLOR_DEFAULT

        val colorSwatches = listOf(
            view.findViewById<View>(R.id.swatch_blue) to COLOR_BLUE,
            view.findViewById<View>(R.id.swatch_purple) to COLOR_PURPLE,
            view.findViewById<View>(R.id.swatch_orange) to COLOR_ORANGE,
            view.findViewById<View>(R.id.swatch_red) to COLOR_RED
        )

        colorSwatches.forEach { (swatch, color) ->
            swatch.setOnClickListener {
                prefs.edit().putString("theme_color", color).apply()
                updateCurrentColorSwatch(view, color)
                applyThemeColor(color)
            }
        }

        updateCurrentColorSwatch(view, currentColor)
    }

    private fun updateCurrentColorSwatch(view: View, color: String) {
        val swatchCurrent = view.findViewById<View>(R.id.swatch_current)
        swatchCurrent.setBackgroundColor(android.graphics.Color.parseColor(color))
    }

    private fun applyThemeColor(color: String) {
        val activity = requireActivity() as MainActivity
        activity.applyThemeColor(color)
    }

    private fun recreateActivity() {
        val intent = Intent(requireContext(), MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        requireActivity().finish()
    }

    private fun bindSingleHandMode(view: View) {
        val tvSingleHandMode = view.findViewById<TextView>(R.id.tv_single_hand_mode)
        val currentMode = com.tikctrl.app.GestureMappingManager.getSingleHandMode(requireContext())
        updateSingleHandModeText(tvSingleHandMode, currentMode)

        tvSingleHandMode.setOnClickListener {
            val modes = arrayOf("双手", "左手", "右手")
            val currentIndex = when (currentMode) {
                com.tikctrl.app.GestureMappingManager.SingleHandMode.BOTH -> 0
                com.tikctrl.app.GestureMappingManager.SingleHandMode.LEFT -> 1
                com.tikctrl.app.GestureMappingManager.SingleHandMode.RIGHT -> 2
            }
            AlertDialog.Builder(requireContext())
                .setTitle("单手识别模式")
                .setSingleChoiceItems(modes, currentIndex) { dialog, which ->
                    val mode = when (which) {
                        1 -> com.tikctrl.app.GestureMappingManager.SingleHandMode.LEFT
                        2 -> com.tikctrl.app.GestureMappingManager.SingleHandMode.RIGHT
                        else -> com.tikctrl.app.GestureMappingManager.SingleHandMode.BOTH
                    }
                    com.tikctrl.app.GestureMappingManager.setSingleHandMode(requireContext(), mode)
                    updateSingleHandModeText(tvSingleHandMode, mode)
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun updateSingleHandModeText(textView: TextView, mode: com.tikctrl.app.GestureMappingManager.SingleHandMode) {
        val modeName = when (mode) {
            com.tikctrl.app.GestureMappingManager.SingleHandMode.BOTH -> "双手"
            com.tikctrl.app.GestureMappingManager.SingleHandMode.LEFT -> "左手"
            com.tikctrl.app.GestureMappingManager.SingleHandMode.RIGHT -> "右手"
        }
        textView.text = "单手识别模式                            $modeName  >"
    }

    private fun bindPercentSeekBar(
        seekBar: SeekBar,
        label: TextView,
        initialValue: Int,
        onChanged: (Int) -> Unit
    ) {
        seekBar.progress = initialValue
        label.text = "$initialValue%"
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "$progress%"
                if (fromUser) onChanged(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                onChanged(seekBar.progress)
            }
        })
    }

    private fun bindSwitch(
        switch: SwitchCompat,
        prefs: android.content.SharedPreferences,
        key: String,
        defaultValue: Boolean
    ) {
        switch.isChecked = prefs.getBoolean(key, defaultValue)
        switch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    private fun updatePermissionStatus(view: View) {
        val cameraStatus = view.findViewById<TextView>(R.id.tv_camera_status)
        val overlayStatus = view.findViewById<TextView>(R.id.tv_overlay_status)
        val accessibilityStatus = view.findViewById<TextView>(R.id.tv_accessibility_status)

        val context = requireContext()

        val cameraGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        updateStatusText(cameraStatus, cameraGranted)

        val overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        updateStatusText(overlayStatus, overlayGranted)

        val expectedComponent = ComponentName(context, GestureActionService::class.java)
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val accessibilityEnabled = !enabledServices.isNullOrEmpty() && enabledServices.contains(expectedComponent.flattenToString())
        updateStatusText(accessibilityStatus, accessibilityEnabled)
    }

    private fun updateStatusText(textView: TextView, isGranted: Boolean) {
        if (isGranted) {
            textView.text = "已开启"
            textView.setTextColor(requireContext().getColor(R.color.color_success))
        } else {
            textView.text = "未开启"
            textView.setTextColor(requireContext().getColor(R.color.color_error))
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let { updatePermissionStatus(it) }
    }

}
