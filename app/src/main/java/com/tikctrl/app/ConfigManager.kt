package com.tikctrl.app

import android.content.Context
import org.json.JSONObject

/**
 * Export/import gesture configuration as a shareable JSON file.
 *
 * Exported data includes:
 * - Gesture -> action mappings
 * - Action parameters (e.g. share targets)
 * - Custom action labels
 * - Single-hand mode
 * - App preferences (power saving, inference engine, camera, theme, etc.)
 */
object ConfigManager {
    private const val CONFIG_VERSION = 1
    private const val PREFS_MAPPINGS = "gesture_mappings"
    private const val PREFS_SETTINGS = "gesture_prefs"

    private val EXPORTABLE_SETTINGS = listOf(
        "power_saving_mode",
        "inference_delegate",
        "front_camera",
        "mirror_mode",
        "floating_alpha",
        "theme_mode",
        "language",
        "single_hand_mode"
    )

    fun exportToJson(context: Context): String {
        val root = JSONObject()
        root.put("version", CONFIG_VERSION)
        root.put("app", "TikCtrl")
        root.put("timestamp", System.currentTimeMillis())

        // Gesture mappings
        val mappings = JSONObject()
        val mappingPrefs = context.getSharedPreferences(PREFS_MAPPINGS, Context.MODE_PRIVATE)
        val gestureEntries = JSONObject()
        val paramEntries = JSONObject()
        val labelEntries = JSONObject()

        for ((key, value) in mappingPrefs.all) {
            when {
                key.startsWith("mapping_") -> gestureEntries.put(key.removePrefix("mapping_"), value as String)
                key.startsWith("param_") -> paramEntries.put(key.removePrefix("param_"), value as String)
                key.startsWith("label_") -> labelEntries.put(key.removePrefix("label_"), value as String)
            }
        }
        mappings.put("gestures", gestureEntries)
        mappings.put("params", paramEntries)
        mappings.put("labels", labelEntries)
        root.put("mappings", mappings)

        // App settings
        val settings = JSONObject()
        val settingsPrefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        for (key in EXPORTABLE_SETTINGS) {
            settingsPrefs.all[key]?.let { value ->
                when (value) {
                    is Boolean -> settings.put(key, value)
                    is Int -> settings.put(key, value)
                    is String -> settings.put(key, value)
                    is Long -> settings.put(key, value)
                    is Float -> settings.put(key, value.toDouble())
                    else -> {}
                }
            }
        }
        root.put("settings", settings)

        return root.toString(2)
    }

    data class ImportResult(
        val success: Boolean,
        val gestureCount: Int,
        val settingsCount: Int,
        val errorMessage: String? = null
    )

    fun importFromJson(context: Context, json: String): ImportResult {
        return try {
            val root = JSONObject(json)
            val version = root.optInt("version", -1)
            if (version != CONFIG_VERSION) {
                return ImportResult(
                    success = false,
                    gestureCount = 0,
                    settingsCount = 0,
                    errorMessage = "Unsupported config version: $version"
                )
            }

            var gestureCount = 0
            var settingsCount = 0

            // Import mappings
            root.optJSONObject("mappings")?.let { mappings ->
                val mappingPrefs = context.getSharedPreferences(PREFS_MAPPINGS, Context.MODE_PRIVATE)
                val editor = mappingPrefs.edit()

                mappings.optJSONObject("gestures")?.let { gestures ->
                    val keys = gestures.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        editor.putString("mapping_$key", gestures.getString(key))
                        gestureCount++
                    }
                }

                mappings.optJSONObject("params")?.let { params ->
                    val keys = params.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        editor.putString("param_$key", params.getString(key))
                    }
                }

                mappings.optJSONObject("labels")?.let { labels ->
                    val keys = labels.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        editor.putString("label_$key", labels.getString(key))
                    }
                }

                editor.apply()
            }

            // Import settings
            root.optJSONObject("settings")?.let { settings ->
                val settingsPrefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                val editor = settingsPrefs.edit()

                val keys = settings.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = settings.get(key)) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is String -> editor.putString(key, value)
                    }
                    settingsCount++
                }
                editor.apply()
            }

            ImportResult(
                success = true,
                gestureCount = gestureCount,
                settingsCount = settingsCount
            )
        } catch (e: Exception) {
            ImportResult(
                success = false,
                gestureCount = 0,
                settingsCount = 0,
                errorMessage = e.message
            )
        }
    }
}
