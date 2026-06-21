package com.tikctrl.app

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class GestureMappingActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gesture_mapping)

        container = findViewById(R.id.gesture_mappings_container)
        val btnReset: Button = findViewById(R.id.btn_reset_defaults)

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
                val paramInput = android.widget.EditText(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    hint = "Share targets, optional, separated by commas"
                    visibility = android.view.View.GONE
                }

                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actionLabels)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter

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
                            paramInput.visibility = android.view.View.VISIBLE
                            val existing = GestureMappingManager.getActionParam(this@GestureMappingActivity, gesture)
                            paramInput.setText(existing ?: "")
                        } else {
                            paramInput.visibility = android.view.View.GONE
                        }
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }

                rowLayout.addView(tv)
                rowLayout.addView(spinner)
                // Add param input below the spinner in the container (separate row)
                val paramRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setPadding(0, 4, 0, 4)
                }
                paramRow.addView(paramInput)
                container.addView(rowLayout)
                container.addView(paramRow)

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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
