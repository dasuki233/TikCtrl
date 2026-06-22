package com.tikctrl.app

/**
 * 可自定义的手势 → 系统操作映射表
 * 未来可扩展为从本地文件或UI配置加载
 */
object GestureConfig {
    val gestureActionMap = mapOf(
        "MIDDLE_FINGER" to "SWIPE_DOWN",       // 竖中指 → 返回
        "OK" to "SWIPE_DOWN",              // OK → 上滑
        "THUMB_UP" to "SWIPE_DOWN",      // 拇指上 → 下滑
        "FIST" to "HOME"                 // 握拳 → 返回桌面
    )
}
