package com.tikctrl.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

class GestureMappingActivity : AppCompatActivity() {
    private lateinit var gestureContainer: LinearLayout
    private lateinit var rootLayout: LinearLayout

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            try {
                val json = ConfigManager.exportToJson(this)
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray())
                }
                Snackbar.make(rootLayout, getString(R.string.export_success), Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(rootLayout, getString(R.string.export_failed), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val json = contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().use { it.readText() }
                } ?: run {
                    Snackbar.make(rootLayout, getString(R.string.import_failed, "Read file failed"), Snackbar.LENGTH_SHORT).show()
                    return@let
                }

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_import_title))
                    .setMessage(getString(R.string.dialog_import_message))
                    .setPositiveButton(getString(R.string.confirm)) { dialog, _ ->
                        val result = ConfigManager.importFromJson(this, json)
                        if (result.success) {
                            Snackbar.make(
                                rootLayout,
                                getString(R.string.import_success, result.gestureCount, result.settingsCount),
                                Snackbar.LENGTH_SHORT
                            ).show()
                            recreate()
                        } else {
                            Snackbar.make(
                                rootLayout,
                                getString(R.string.import_failed, result.errorMessage ?: "Unknown error"),
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            } catch (e: Exception) {
                Snackbar.make(rootLayout, getString(R.string.import_failed, e.message ?: "Error"), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gesture_mapping)

        gestureContainer = findViewById(R.id.gesture_mappings_container)
        rootLayout = findViewById(R.id.container)
        initStatistics()

        val btnReset: Button = findViewById(R.id.btn_reset_defaults)
        val btnExport: Button = findViewById(R.id.btn_export_config)
        val btnImport: Button = findViewById(R.id.btn_import_config)

        btnExport.setOnClickListener {
            exportLauncher.launch(getString(R.string.config_file_name))
        }

        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }

        // Build ordered list of actions for spinner (display names are localized)
        val actionList = GestureMappingManager.getAllActionsForDisplay()
        val actionLabels = actionList.map { GestureMappingManager.getDisplayName(this, it) }

        // For each gesture enum, add a row
        val gestures = GestureClassifier.Gesture.values()
        for (gesture in gestures) {
            // skip NONE
            if (gesture == GestureClassifier.Gesture.NONE) continue

            try {
                // create a horizontal row container
                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = androidx.core.content.ContextCompat.getDrawable(this@GestureMappingActivity, R.drawable.bg_card_small)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 8, 0, 8)
                    }
                }

                val tv = TextView(this).apply {
                    text = GestureMappingManager.getGestureDisplayName(this@GestureMappingActivity, gesture)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val spinner = Spinner(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }

                // EditText for action-specific parameter (e.g., share target name)
                val paramDesc = TextView(this).apply {
                    text = getString(R.string.mapping_share_targets_desc)
                    setTextColor(androidx.core.content.ContextCompat.getColor(this@GestureMappingActivity, R.color.color_text_secondary))
                    textSize = 12f
                    visibility = android.view.View.GONE
                    setPadding(dp(16), dp(10), dp(16), 0)
                }

                val paramInput = android.widget.EditText(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setHint(R.string.mapping_share_targets_hint)
                    visibility = android.view.View.GONE
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }

                val adapter = ArrayAdapter(this, R.layout.spinner_item_dark, actionLabels)
                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
                spinner.adapter = adapter
                spinner.background = ContextCompat.getDrawable(this, R.drawable.bg_spinner_dark)

                // Load current mapping
                val currentAction = GestureMappingManager.getActionForGesture(this, gesture)
                val pos = actionList.indexOf(currentAction)
                if (pos >= 0) spinner.setSelection(pos)

                // When changed, persist
                spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val sel = actionList[position]
                        GestureMappingManager.setActionForGesture(this@GestureMappingActivity, gesture, sel)
                        // Show/hide param input when SHARE selected
                        if (sel == GestureMappingManager.Action.SHARE) {
                            paramDesc.visibility = android.view.View.VISIBLE
                            paramInput.visibility = android.view.View.VISIBLE
                            val existing = GestureMappingManager.getActionParam(this@GestureMappingActivity, gesture)
                            paramInput.setText(existing ?: "")
                        } else {
                            paramDesc.visibility = android.view.View.GONE
                            paramInput.visibility = android.view.View.GONE
                        }
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }

                rowLayout.addView(tv)
                rowLayout.addView(spinner)
                // Add param description and input below the spinner (separate rows)
                val paramDescRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                paramDescRow.addView(paramDesc)
                val paramRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setPadding(dp(12), 0, dp(12), dp(8))
                }
                paramRow.addView(paramInput)
                gestureContainer.addView(rowLayout)
                gestureContainer.addView(paramDescRow)
                gestureContainer.addView(paramRow)

                // Save param when focus lost
                paramInput.setOnFocusChangeListener { v, hasFocus ->
                    if (!hasFocus) {
                        val text = paramInput.text?.toString()?.trim()
                        if (text.isNullOrEmpty()) GestureMappingManager.setActionParam(this@GestureMappingActivity, gesture, null)
                        else GestureMappingManager.setActionParam(this@GestureMappingActivity, gesture, text)
                    }
                }

                // Also save on text change immediately to avoid losing value if user presses Home without blurring
                paramInput.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val text = s?.toString()?.trim()
                        if (text.isNullOrEmpty()) GestureMappingManager.setActionParam(this@GestureMappingActivity, gesture, null)
                        else GestureMappingManager.setActionParam(this@GestureMappingActivity, gesture, text)
                    }
                })
            } catch (e: Exception) {
                android.util.Log.e("GestureMapping", "Failed to create row for gesture: $gesture", e)
            }
        }

        btnReset.setOnClickListener {
            GestureMappingManager.resetMappings(this)
            // refresh activity
            recreate()
        }
    }

    private fun initStatistics() {
        GestureStatistics.init(this)

        val tvTodayCount = findViewById<TextView>(R.id.tv_today_count)
        val tvTotalCount = findViewById<TextView>(R.id.tv_total_count)

        tvTodayCount.text = GestureStatistics.getTodayCount().toString()
        tvTotalCount.text = GestureStatistics.getTotalCount().toString()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
