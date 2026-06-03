package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.content.SharedPreferences

/**
 * Manage gesture -> action mappings persisted in SharedPreferences.
 * Action values are strings; add new values as needed.
 */
object GestureMappingManager {
    private const val PREFS_NAME = "gesture_mappings"
    private const val KEY_PREFIX = "mapping_"

    enum class Action(val id: String) {
        NONE("none"),
        NEXT("next"),
        PREV("prev"),
        SHARE("share"),
        LIKE("like"),
        UNLIKE("unlike"),
        DOUBLE_SPEED("double_speed"),
        NORMAL_SPEED("normal_speed"),
        FOLLOW("follow"),
//        UNFOLLOW("unfollow"),
        MARK("mark"),
        BACKK("back"),
        OPEN_COMMENTS("open_comments"),
        CUSTOM("custom"),
        USER_AVATAR("user_avatar")
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getActionForGesture(context: Context, gesture: GestureClassifier.Gesture): Action {
        val key = KEY_PREFIX + gesture.name
        val id = prefs(context).getString(key, null)
        return if (id == null) defaultMapping()[gesture] ?: Action.NONE else Action.values().find { it.id == id } ?: Action.NONE
    }

    fun setActionForGesture(context: Context, gesture: GestureClassifier.Gesture, action: Action) {
        prefs(context).edit().putString(KEY_PREFIX + gesture.name, action.id).apply()
    }

    fun resetMappings(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private const val KEY_LABEL_PREFIX = "label_"
    private const val KEY_PARAM_PREFIX = "param_"

    fun setActionParam(context: Context, gesture: GestureClassifier.Gesture, param: String?) {
        val key = KEY_PARAM_PREFIX + gesture.name
        val editor = prefs(context).edit()
        if (param == null) editor.remove(key) else editor.putString(key, param)
        editor.apply()
    }

    fun getActionParam(context: Context, gesture: GestureClassifier.Gesture): String? {
        return prefs(context).getString(KEY_PARAM_PREFIX + gesture.name, null)
    }

    fun setCustomLabel(context: Context, action: Action, label: String) {
        prefs(context).edit().putString(KEY_LABEL_PREFIX + action.id, label).apply()
    }

    fun getCustomLabel(context: Context, action: Action): String? {
        return prefs(context).getString(KEY_LABEL_PREFIX + action.id, null)
    }

    fun getDisplayName(context: Context, action: Action): String {
        val custom = getCustomLabel(context, action)
        if (!custom.isNullOrEmpty()) return custom
        // 下拉框中文转换用
        return when (action) {
            Action.NONE -> context.getString(R.string.action_none)
            Action.NEXT -> context.getString(R.string.action_next)
            Action.PREV -> context.getString(R.string.action_prev)
            Action.SHARE -> context.getString(R.string.action_share)
            Action.LIKE -> context.getString(R.string.action_like)
            Action.OPEN_COMMENTS -> context.getString(R.string.action_open_comments)
            Action.DOUBLE_SPEED -> context.getString(R.string.action_double_speed)
            Action.NORMAL_SPEED -> context.getString(R.string.action_normal_speed)
            Action.FOLLOW -> context.getString(R.string.action_follow)
            Action.MARK -> context.getString(R.string.action_mark)
            Action.BACKK -> context.getString(R.string.action_back)
            Action.UNLIKE -> context.getString(R.string.action_unlike)
//            Action.UNFOLLOW -> context.getString(R.string.action_unfollow)
            Action.USER_AVATAR -> context.getString(R.string.action_user_avatar)
            Action.CUSTOM -> context.getString(R.string.action_custom)
        }
    }

    // 下拉框选项编辑
    fun getAllActionsForDisplay(): List<Action> {
        return listOf(
            Action.NONE, Action.NEXT, Action.PREV, Action.SHARE, Action.LIKE,Action.UNLIKE, Action.OPEN_COMMENTS,
            Action.DOUBLE_SPEED, Action.NORMAL_SPEED,Action.FOLLOW,Action.USER_AVATAR,Action.MARK, Action.BACKK
        )
    }

    // 手势 对应动作  默认设置
    fun defaultMapping(): Map<GestureClassifier.Gesture, Action> {
        return mapOf(
            GestureClassifier.Gesture.MIDDLE_FINGER to Action.LIKE, // 点赞  🖕🏻
            GestureClassifier.Gesture.PINKY_FINGER to Action.UNLIKE, // 取消点赞 小拇指伸直其他弯曲
            GestureClassifier.Gesture.INDEX_FINGER to Action.NEXT, // 下一个 👆🏻
            GestureClassifier.Gesture.PEACE_V to Action.PREV, // 上一个 [胜利]
            GestureClassifier.Gesture.INDEX_MIDDLE_RING to Action.OPEN_COMMENTS, // 打开评论  手势三
            GestureClassifier.Gesture.INDEX_MIDDLE_RING_PINKY to Action.MARK, // 收藏 手势四
            GestureClassifier.Gesture.SPIDER_MAN_SHOOTER to Action.MARK, // 关注  🤟
//            GestureClassifier.Gesture.SPIDER_SHOOTER_NO_THUMB to Action., // 取消关注 🤘
            GestureClassifier.Gesture.OK to Action.USER_AVATAR, // 查看主页  [OK]
            GestureClassifier.Gesture.THUMB to Action.BACKK, // 查看主页  [OK]
            GestureClassifier.Gesture.Aki_FOX_DEVIL to Action.DOUBLE_SPEED, //  2倍速  大拇指捏住中指和无名指，其他伸直
            GestureClassifier.Gesture.SIXSIXSIX to Action.NORMAL_SPEED //  1倍速  🤙
        )
    }

    // 左边列表中文映射
    fun getGestureDisplayName(context: Context, gesture: GestureClassifier.Gesture): String {
        return try {
            when (gesture) {

                GestureClassifier.Gesture.MIDDLE_FINGER -> context.getString(R.string.gesture_middle_finger)
                GestureClassifier.Gesture.PINKY_FINGER -> context.getString(R.string.gesture_pinky_finger)
                GestureClassifier.Gesture.INDEX_FINGER -> context.getString(R.string.gesture_index_finger)
                GestureClassifier.Gesture.PEACE_V -> context.getString(R.string.gesture_peace_v)
                GestureClassifier.Gesture.INDEX_MIDDLE_RING -> context.getString(R.string.gesture_index_middle_ring)
                GestureClassifier.Gesture.INDEX_MIDDLE_RING_PINKY -> context.getString(R.string.gesture_index_middle_ring_pinky)
                GestureClassifier.Gesture.SPIDER_MAN_SHOOTER -> context.getString(R.string.gesture_spider_man_shooter)
                GestureClassifier.Gesture.SPIDER_SHOOTER_NO_THUMB -> context.getString(R.string.gesture_spider_shooter_no_thumb)
                GestureClassifier.Gesture.OK -> context.getString(R.string.gesture_ok)
                GestureClassifier.Gesture.THUMB -> context.getString(R.string.gesture_thumb)
                GestureClassifier.Gesture.Aki_FOX_DEVIL -> context.getString(R.string.gesture_aki_fox_devil)
                GestureClassifier.Gesture.SIXSIXSIX -> context.getString(R.string.gesture_sixsixsix)
                else -> gesture.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
            }
        } catch (e: Exception) {
            gesture.name
        }
    }

    // --- Single-hand mode persistence (mirrors main/main variant) ---
    enum class SingleHandMode {
        BOTH,
        LEFT,
        RIGHT
    }

    private const val KEY_SINGLE_HAND_MODE = "single_hand_mode"

    fun setSingleHandMode(context: Context, mode: SingleHandMode) {
        prefs(context).edit().putString(KEY_SINGLE_HAND_MODE, mode.name).apply()
    }

    fun getSingleHandMode(context: Context): SingleHandMode {
        val v = prefs(context).getString(KEY_SINGLE_HAND_MODE, SingleHandMode.BOTH.name) ?: SingleHandMode.BOTH.name
        return try {
            SingleHandMode.valueOf(v)
        } catch (e: Exception) {
            SingleHandMode.BOTH
        }
    }
}
