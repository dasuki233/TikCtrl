package com.tikctrl.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object GestureStatistics {

    private const val PREFS_NAME = "gesture_statistics"
    private const val KEY_DAILY_COUNT_PREFIX = "daily_"
    private const val KEY_LAST_RESET_DATE = "last_reset_date"
    private const val KEY_TOTAL_COUNT = "total_count"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        resetIfNewDay()
    }

    private fun getTodayKey(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return KEY_DAILY_COUNT_PREFIX + dateFormat.format(Date())
    }

    private fun getDateKey(date: Date): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return KEY_DAILY_COUNT_PREFIX + dateFormat.format(date)
    }

    private fun resetIfNewDay() {
        val lastResetDate = prefs.getString(KEY_LAST_RESET_DATE, "") ?: ""
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (lastResetDate != today) {
            prefs.edit().putString(KEY_LAST_RESET_DATE, today).apply()
            // 清理超过 7 天的历史记录，避免 prefs 无限增长
            cleanupOldHistory(7)
        }
    }

    private fun cleanupOldHistory(keepDays: Int) {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -keepDays)
            val cutoff = calendar.time

            val editor = prefs.edit()
            var removed = 0
            prefs.all.keys.forEach { key ->
                if (key.startsWith(KEY_DAILY_COUNT_PREFIX)) {
                    val dateStr = key.removePrefix(KEY_DAILY_COUNT_PREFIX)
                    try {
                        val date = dateFormat.parse(dateStr)
                        if (date != null && date.before(cutoff)) {
                            editor.remove(key)
                            removed++
                        }
                    } catch (e: Exception) {
                        // 无法解析的 key 一并清理
                        editor.remove(key)
                        removed++
                    }
                }
            }
            if (removed > 0) {
                editor.apply()
            }
        } catch (e: Exception) {
            // 清理失败不影响主流程
        }
    }

    fun getTodayCount(): Int {
        resetIfNewDay()
        return prefs.getInt(getTodayKey(), 0)
    }

    fun incrementCount() {
        resetIfNewDay()
        val key = getTodayKey()
        val current = prefs.getInt(key, 0)
        prefs.edit()
            .putInt(key, current + 1)
            .putInt(KEY_TOTAL_COUNT, prefs.getInt(KEY_TOTAL_COUNT, 0) + 1)
            .apply()
    }

    fun getHistory(days: Int = 7): List<Pair<String, Int>> {
        val history = mutableListOf<Pair<String, Int>>()
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

        for (i in 0 until days) {
            val date = calendar.time
            val key = getDateKey(date)
            val count = prefs.getInt(key, 0)
            history.add(Pair(dateFormat.format(date), count))
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return history.reversed()
    }

    fun getTotalCount(): Int {
        return prefs.getInt(KEY_TOTAL_COUNT, 0)
    }
}
