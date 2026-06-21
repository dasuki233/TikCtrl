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

    fun calculateSensitivity(): Int {
        val todayCount = getTodayCount()
        val history = getHistory(7)
        val avgCount = if (history.isNotEmpty()) history.map { it.second }.average() else 0.0

        // 灵敏度计算逻辑：
        // - 滑动次数越多，灵敏度越低（用户熟练了，需要更精确）
        // - 滑动次数越少，灵敏度越高（新手需要更宽松的检测）
        // 基准值 80，范围 40-100

        return when {
            todayCount < 5 -> 95  // 新手/第一天，高灵敏度
            todayCount < 20 -> 85  // 少量使用
            todayCount < 50 -> 75  // 中等使用
            todayCount < 100 -> 65 // 经常使用
            else -> 55              // 熟练用户，需要更高精度
        }
    }
}
