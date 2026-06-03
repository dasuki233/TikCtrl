package com.google.mediapipe.examples.handlandmarker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import android.media.AudioManager
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import androidx.annotation.RequiresApi
import androidx.test.uiautomator.UiSelector

class GestureActionService : AccessibilityService() {

    private val gestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HandGestureService.ACTION_GESTURE) {
                val gesture = intent.getStringExtra(HandGestureService.EXTRA_GESTURE)
                android.util.Log.i("GestureActionService", "Received gesture broadcast: $gesture")
                gesture?.let { performGestureAction(it) }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.i("GestureActionService", "Accessibility service connected; registering gesture receiver")
        val filter = IntentFilter(HandGestureService.ACTION_GESTURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(gestureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gestureReceiver, filter)
        }
    }

    override fun onInterrupt() {}

    // Used to wait for UI changes after performing gestures
    @Volatile
    private var expectingContentChange = false
    private var contentChangeLatch: CountDownLatch? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val type = event.eventType
            if (expectingContentChange && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
                android.util.Log.i("GestureActionService", "Observed UI content change event type=$type")
                contentChangeLatch?.countDown()
                expectingContentChange = false
            }
        } catch (e: Exception) {
            android.util.Log.w("GestureActionService", "onAccessibilityEvent exception: ${e.message}")
        }
    }

    // 上划
    private fun swipeUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 获取屏幕尺寸
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // 定义手势的起点和终点
            val startX = screenWidth / 2
            val startY = screenHeight / 2
            val endY = screenHeight / 5

            // 创建手势路径
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(startX.toFloat(), endY.toFloat())
            }

            // 创建手势描述
            val gestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300)) // 在500毫秒内完成滑动
                .build()

            // 分发手势
            dispatchGesture(gestureDescription, null, null)
        }
    }

    // 下滑
    private fun swipeDown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val startX = screenWidth / 2
            val startY = screenHeight / 5
            val endY = screenHeight / 2

            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(startX.toFloat(), endY.toFloat())
            }

            val gestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()

            dispatchGesture(gestureDescription, null, null)
        }
    }

    // 左滑
    private fun swipeLeft() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // 从屏幕右侧向左侧滑动
            val startX = screenWidth * 3 / 4  // 从屏幕3/4宽度开始
            val startY = screenHeight / 2     // 垂直居中
            val endX = screenWidth / 6        // 滑动到屏幕1/6宽度

            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), startY.toFloat())
            }

            val gestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()

            dispatchGesture(gestureDescription, null, null)
        }
    }

    // 右划
    private fun swipeRight() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // 从屏幕左侧向右侧滑动
            val startX = screenWidth / 6      // 从屏幕1/6宽度开始
            val startY = screenHeight / 2     // 垂直居中
            val endX = screenWidth * 3 / 4    // 滑动到屏幕3/4宽度

            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), startY.toFloat())
            }

            val gestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()

            dispatchGesture(gestureDescription, null, null)
        }
    }

    // 8次快速点击屏幕中心 点赞用
    private fun performSixTaps() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val displayMetrics = resources.displayMetrics
        val x = displayMetrics.widthPixels / 2
        val y = displayMetrics.heightPixels / 2

        val builder = GestureDescription.Builder()


        // 每次点击持续时间（毫秒）
        val tapDuration = 50L
        // 每次点击间隔（毫秒）
        val interval = 150L

        for (i in 0 until 8) {
            val startTime = i * (tapDuration + interval)
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }

            // 添加一个点击手势（点按一下）
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    startTime,
                    tapDuration
                )
            )
        }

        val gesture = builder.build()
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    android.util.Log.i("GestureActionService", "✅ 8次点击完成")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    android.util.Log.e("GestureActionService", "❌ 连续点击被取消")
                }
            },
            null
        )
    }

    // 分享好友：使用 actionParam（来自 GestureMappingManager）选择目标并尝试发送
    // 函数会使用 Thread.sleep 阻塞主线程以保证元素加载完成
    private fun shareToTargets(actionParam: String?) {
        // 打开分享面板
        findAndClickByContentDescPartial("分享")

        // 阻塞等待分享面板加载（用户要求阻塞）
        try { Thread.sleep(2000) } catch (e: InterruptedException) {}

        if (!actionParam.isNullOrEmpty()) {
            Log.i("GestureActionService", "Attempting to select share targets: $actionParam")
            val targets = actionParam.split(',').map { it.trim() }.filter { it.isNotEmpty() }

            // Try to select each target (by content-desc or text)
            for (t in targets) {
                Log.i("GestureActionService", "Trying target: $t")
                val clicked = findAndClickByContentDescPartial(t) || findAndClickByTextPartial(t)
                if (!clicked) {
                    Log.w("GestureActionService", "Could not find target: $t")
                } else {
                    // small delay between selections to allow UI update (blocking as requested)
                    try { Thread.sleep(2000) } catch (e: InterruptedException) {}
                }
            }

            // After selecting targets, attempt to find a send button near the selected nodes (sibling/ancestor strategy)
            debugCurrentContentDescriptions()
            try { Thread.sleep(2000) } catch (e: InterruptedException) {}

            var sent = false
            try {
                if (findAndClickSendNearSelectedNodes(targets)) {
                    Log.i("GestureActionService", "Clicked send via sibling/ancestor strategy")
                    sent = true
                }
            } catch (e: Exception) {
                Log.w("GestureActionService", "findAndClickSendNearSelectedNodes exception: ${e.message}")
            }

            // If sibling strategy didn't work, fallback to previous heuristics
            if (!sent) {
                debugCurrentContentDescriptions()
                try { Thread.sleep(2000) } catch (e: InterruptedException) {}
                // fallback attempts: try content-desc/text/viewId searches for common send labels
                val fallbackLabels = listOf("发送", "分享", "分别发送")
                for (lbl in fallbackLabels) {
                    if (findAndClickByContentDescPartial(lbl) || findAndClickByTextPartial(lbl)) {
                        Log.i("GestureActionService", "Clicked send via fallback label '$lbl'")
                        sent = true
                        break
                    }
                }
            }

            if (!sent) {
                Log.w("GestureActionService", "Failed to click send after all attempts; consider dumpAccessibilityTree for debugging")
            }
        } else {
            Log.i("GestureActionService", "No share target configured for this gesture; fallback to manual selection")
        }
    }


    // Dump the accessibility tree to logs (for debugging). Be careful: can be verbose.
    private fun dumpAccessibilityTree() {
        val root = rootInActiveWindow ?: run {
            android.util.Log.e("GestureActionService", "dumpAccessibilityTree: root is null")
            return
        }

        fun walk(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null) return
            try {
                val indent = "  ".repeat(depth)
                val id = node.viewIdResourceName ?: "null"
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val cls = node.className?.toString() ?: ""
                val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                android.util.Log.i("GestureActionService", "$indent id=$id text='$text' desc='$desc' cls=$cls bounds=$bounds clickable=${node.isClickable} focusable=${node.isFocusable}")
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    walk(child, depth + 1)
                }
            } catch (e: Exception) {
                android.util.Log.w("GestureActionService", "walk exception: ${e.message}")
            } finally {
                try { node.recycle() } catch (ignored: Exception) {}
            }
        }

        walk(root)
    }

    // Find node(s) whose contentDescription contains keyword and click using the robust strategy.
    // 1. findAndClickByContentDescPartial: Find element; dispatchGestureAndWaitForUiChange: Click element
    private fun findAndClickByContentDescPartial(keyword: String, classNameFilter: String? = null): Boolean {
        val root = rootInActiveWindow ?: run {
            android.util.Log.e("GestureActionService", "findAndClickByContentDescPartial: root null")
            return false
        }



            // ...existing code...

        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val desc = node.contentDescription?.toString()
                if (!desc.isNullOrEmpty() && desc.contains(keyword)) {
                    if (classNameFilter == null || node.className?.toString() == classNameFilter) {
                        matches.add(node)
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    walk(child)
                }
            } catch (e: Exception) {
                android.util.Log.w("GestureActionService", "walk exception: ${e.message}")
            }
        }

        walk(root)

        if (matches.isEmpty()) {
            android.util.Log.i("GestureActionService", "No nodes with contentDesc contains '$keyword'")
            return false
        }

        // Sort by clickable then proximity to center
        matches.sortWith(compareByDescending<AccessibilityNodeInfo> { it.isClickable }.thenComparator { a, b ->
            val ra = android.graphics.Rect().also { a.getBoundsInScreen(it) }
            val rb = android.graphics.Rect().also { b.getBoundsInScreen(it) }
            val metrics = resources.displayMetrics
            val cx = metrics.widthPixels / 2
            val cy = metrics.heightPixels / 2
            val da = Math.hypot((ra.centerX() - cx).toDouble(), (ra.centerY() - cy).toDouble())
            val db = Math.hypot((rb.centerX() - cx).toDouble(), (rb.centerY() - cy).toDouble())
            da.compareTo(db)
        })

        // Try robust click on first candidate
        val node = matches[0]
        try {
            // attempt performAction or fallback to coordinate tap via existing function
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.util.Log.i("GestureActionService", "performAction on desc node result=$ok")
            if (ok) {
                try { node.recycle() } catch (ignored: Exception) {}
                return true
            }
        } catch (e: Exception) {
            android.util.Log.w("GestureActionService", "performAction on desc node exception: ${e.message}")
        }

        // Not clickable or performAction failed: try ancestors or coordinate tap
        val resourceName = node.viewIdResourceName ?: ""
        try {
            // Try clickable ancestor
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val okParent = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.i("GestureActionService", "performAction on ancestor for desc node result=$okParent")
                    if (okParent) {
                        try { node.recycle() } catch (ignored: Exception) {}
                        return true
                    }
                }
                parent = parent.parent
            }
        } catch (e: Exception) {
            android.util.Log.w("GestureActionService", "ancestor perform exception: ${e.message}")
        }

        // Fallback to coordinate tap and wait for UI change
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            android.util.Log.i("GestureActionService", "Fallback coordinate tap for desc '$keyword' at ($x,$y)")
            val success = dispatchGestureAndWaitForUiChange(x, y)
            try { node.recycle() } catch (ignored: Exception) {}
            return success
        }

        try { node.recycle() } catch (ignored: Exception) {}
        return false
    }

    // Recursively search subtree for send-like nodes and try to click them (class-level method)
    private fun tryClickSendInSubtree(node: AccessibilityNodeInfo?, maxDepth: Int = 6): Boolean {
        if (node == null) return false
        if (maxDepth < 0) return false

        try {
            val cText = node.text?.toString() ?: ""
            val cDesc = node.contentDescription?.toString() ?: ""
            val vid = node.viewIdResourceName ?: ""
            val cls = node.className?.toString() ?: ""
            val isClickable = node.isClickable

            // Avoid matching group-create send buttons like "建群并发送"
            val isExplicitlyExcluded = cText.contains("建群并发送") || cDesc.contains("建群并发送")
            val looksLikeSend = !isExplicitlyExcluded && (cText.contains("发送") || cText.contains("分别发送") || cDesc.contains("发送") || cDesc.contains("分别发送") || vid.contains("send") || vid.contains("share") || cls.contains("Button") || cls.contains("ImageButton"))

            if (isClickable && looksLikeSend) {
                android.util.Log.i("GestureActionService", "tryClickSendInSubtree found candidate: text='$cText' desc='$cDesc' id='$vid' class='$cls' clickable=$isClickable")
                try {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        try { node.recycle() } catch (ignored: Exception) {}
                        return true
                    }
                } catch (e: Exception) {
                    android.util.Log.w("GestureActionService", "performAction on candidate exception: ${e.message}")
                }

                // coordinate fallback
                try {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val x = bounds.centerX().toFloat()
                        val y = bounds.centerY().toFloat()
                        android.util.Log.i("GestureActionService", "tryClickSendInSubtree coordinate fallback at ($x,$y)")
                        if (dispatchGestureAndWaitForUiChange(x, y, 1200L, 80L)) {
                            try { node.recycle() } catch (ignored: Exception) {}
                            return true
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            // recurse children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    if (tryClickSendInSubtree(child, maxDepth - 1)) {
                        try { node.recycle() } catch (ignored: Exception) {}
                        return true
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        try { node.recycle() } catch (ignored: Exception) {}
        return false
    }

    // Similar to findAndClickByContentDescPartial but matches node.text instead of contentDescription
    private fun findAndClickByTextPartial(keyword: String, classNameFilter: String? = null): Boolean {
        val root = rootInActiveWindow ?: run {
            android.util.Log.e("GestureActionService", "findAndClickByTextPartial: root null")
            return false
        }

        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val txt = node.text?.toString()
                if (!txt.isNullOrEmpty() && txt.contains(keyword)) {
                    if (classNameFilter == null || node.className?.toString() == classNameFilter) {
                        matches.add(node)
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    walk(child)
                }
            } catch (e: Exception) {
                android.util.Log.w("GestureActionService", "walk exception: ${e.message}")
            }
        }

        walk(root)

        if (matches.isEmpty()) {
            android.util.Log.i("GestureActionService", "No nodes with text contains '$keyword'")
            return false
        }

        // Prefer clickable nodes
        matches.sortWith(compareByDescending<AccessibilityNodeInfo> { it.isClickable })

        val node = matches[0]
        try {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.util.Log.i("GestureActionService", "performAction on text node result=$ok")
            if (ok) {
                try { node.recycle() } catch (ignored: Exception) {}
                return true
            }
        } catch (e: Exception) {
            android.util.Log.w("GestureActionService", "performAction on text node exception: ${e.message}")
        }

        // try ancestors
        try {
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val okParent = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.i("GestureActionService", "performAction on ancestor for text node result=$okParent")
                    if (okParent) {
                        try { node.recycle() } catch (ignored: Exception) {}
                        return true
                    }
                }
                parent = parent.parent
            }
        } catch (e: Exception) {
            android.util.Log.w("GestureActionService", "ancestor perform exception: ${e.message}")
        }

        // coordinate fallback
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            android.util.Log.i("GestureActionService", "Fallback coordinate tap for text '$keyword' at ($x,$y)")
            val success = dispatchGestureAndWaitForUiChange(x, y)
            try { node.recycle() } catch (ignored: Exception) {}
            return success
        }

        try { node.recycle() } catch (ignored: Exception) {}
        return false
    }

    // Find node(s) whose viewIdResourceName contains keyword and click using robust strategy
    private fun findAndClickByViewIdPartial(keyword: String): Boolean {
        val root = rootInActiveWindow ?: run {
            android.util.Log.e("GestureActionService", "findAndClickByViewIdPartial: root null")
            return false
        }

        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val vid = node.viewIdResourceName ?: ""
                if (vid.contains(keyword)) {
                    matches.add(node)
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    walk(child)
                }
            } catch (e: Exception) {
                android.util.Log.w("GestureActionService", "walk exception: ${e.message}")
            }
        }

        walk(root)

        if (matches.isEmpty()) {
            android.util.Log.i("GestureActionService", "No nodes with viewId contains '$keyword'")
            return false
        }

        // Prefer clickable nodes
        matches.sortWith(compareByDescending<AccessibilityNodeInfo> { it.isClickable })

        val node = matches[0]
        try {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.util.Log.i("GestureActionService", "performAction on viewId node result=$ok")
            if (ok) {
                try { node.recycle() } catch (ignored: Exception) {}
                return true
            }
        } catch (e: Exception) {
            android.util.Log.w("GestureActionService", "performAction on viewId node exception: ${e.message}")
        }

        // try ancestors
        try {
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val okParent = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.i("GestureActionService", "performAction on ancestor for viewId node result=$okParent")
                    if (okParent) {
                        try { node.recycle() } catch (ignored: Exception) {}
                        return true
                    }
                }
                parent = parent.parent
            }
        } catch (e: Exception) {
            android.util.Log.w("GestureActionService", "ancestor perform exception: ${e.message}")
        }

        // coordinate fallback
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            android.util.Log.i("GestureActionService", "Fallback coordinate tap for viewId '$keyword' at ($x,$y)")
            val success = dispatchGestureAndWaitForUiChange(x, y)
            try { node.recycle() } catch (ignored: Exception) {}
            return success
        }

        try { node.recycle() } catch (ignored: Exception) {}
        return false
    }

    // Dispatch a single-tap gesture and wait for a UI content change event (with timeout). Returns true if UI changed.
    private fun dispatchGestureAndWaitForUiChange(x: Float, y: Float, timeoutMs: Long = 1000L, durationMs: Long = 60L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()

        contentChangeLatch = CountDownLatch(1)
        expectingContentChange = true
        var completed = false

        try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    android.util.Log.i("GestureActionService", "dispatchGesture completed, waiting for UI change...")
                    // don't set completed here; wait for UI event
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    android.util.Log.w("GestureActionService", "dispatchGesture cancelled")
                    expectingContentChange = false
                    contentChangeLatch?.countDown()
                }
            }, null)

            // Wait for accessibility events indicating UI changed
            val ok = try {
                contentChangeLatch?.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: false
            } catch (ie: InterruptedException) {
                false
            }

            completed = ok
            if (!ok) {
                android.util.Log.w("GestureActionService", "No UI change observed within $timeoutMs ms after gesture")
            }
        } catch (se: SecurityException) {
            android.util.Log.e("GestureActionService", "dispatchGesture SecurityException: ${se.message}")
            expectingContentChange = false
            contentChangeLatch?.countDown()
        } finally {
            expectingContentChange = false
            contentChangeLatch = null
        }

        return completed
    }

    //
    private fun longPressAtPositionAndWait(x: Float, y: Float, longPressMs: Long = 500L, waitMs: Long = 2000L): Boolean {
        android.util.Log.i("GestureActionService", "在位置($x, $y)长按${longPressMs}ms并等待${waitMs}ms")

        // 执行长按手势
        val success = dispatchGestureAndWaitForUiChange(x, y, waitMs, longPressMs)

        android.util.Log.i("GestureActionService", "长按操作结果: $success")
        return success
    }

    // 正确的刷新无障碍树方法
    // 刷新无障碍树（强制更新）
    private fun refreshAccessibilityTree() {
        try {
            // 通过执行一个空操作来刷新树
//            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(100)
//            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            // 忽略异常
        }
    }

    // 调试函数：打印当前所有内容描述
    private fun debugCurrentContentDescriptions() {
        val root = rootInActiveWindow ?: return

        val allDescriptions = mutableListOf<String>()

        fun collectDescriptions(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val desc = node.contentDescription?.toString()
                if (!desc.isNullOrEmpty()) {
                    val className = node.className?.toString() ?: "Unknown"
                    val clickable = if (node.isClickable) "可点击" else "不可点击"
                    allDescriptions.add("『$desc』 - $className ($clickable)")
                }
                for (i in 0 until node.childCount) {
                    collectDescriptions(node.getChild(i))
                }
            } catch (e: Exception) {
                // 忽略异常
            }
        }

        collectDescriptions(root)

        android.util.Log.d("GestureActionService", "=== 当前界面可用元素 ===")
        if (allDescriptions.isEmpty()) {
            android.util.Log.d("GestureActionService", "没有找到任何内容描述")
        } else {
            allDescriptions.forEachIndexed { index, desc ->
                android.util.Log.d("GestureActionService", "${index + 1}. $desc")
            }
        }
        android.util.Log.d("GestureActionService", "=== 共 ${allDescriptions.size} 个元素 ===")
    }

    // 长按后 找到 元素点击 调倍速用
    private fun longPressThenFindAndClick(
        keyword: String,
        x: Float? = null,
        y: Float? = null,
        longPressMs: Long = 600L,
        waitAfterLongPress: Long = 2000L,  // 长按后等待界面加载的时间
        maxRetries: Int = 3,               // 最大重试次数
        retryDelay: Long = 800L            // 每次重试的间隔
    ): Boolean {

        // 1. 确定长按位置（如果没指定就用屏幕中心）
        val pressX = x ?: resources.displayMetrics.widthPixels / 2f
        val pressY = y ?: resources.displayMetrics.heightPixels / 2f

        android.util.Log.i("GestureActionService", "开始执行：长按后查找『$keyword』")
        android.util.Log.i("GestureActionService", "长按位置: ($pressX, $pressY), 长按时间: ${longPressMs}ms")

        // 2. 执行长按
        android.util.Log.i("GestureActionService", "执行长按手势...")
        val longPressSuccess = longPressAtPositionAndWait(pressX, pressY, longPressMs, waitAfterLongPress)

        if (longPressSuccess) {
            android.util.Log.i("GestureActionService", "长按成功，检测到UI变化")
        } else {
            android.util.Log.w("GestureActionService", "长按后未检测到UI变化，但仍继续尝试查找")
        }

        // 3. 等待界面稳定加载
        android.util.Log.i("GestureActionService", "等待界面完全加载...")
        try {
            Thread.sleep(300)
        } catch (e: InterruptedException) {
            // 忽略异常
        }

        // 4. 多次尝试查找并点击目标元素
        var retryCount = 0
        while (retryCount < maxRetries) {
            android.util.Log.i("GestureActionService", "第${retryCount + 1}次尝试查找『$keyword』")

            // 先调试输出当前所有可用的元素（可选，用于调试）
            debugCurrentContentDescriptions()

            // 尝试查找并点击
            val found = findAndClickByContentDescPartial(keyword)
            if (found) {
                android.util.Log.i("GestureActionService", "成功找到并点击了『$keyword』")
                return true
            }

            retryCount++
            if (retryCount < maxRetries) {
                android.util.Log.i("GestureActionService", "未找到，等待${retryDelay}ms后重试...")
                try {
                    Thread.sleep(retryDelay)
                } catch (e: InterruptedException) {
                    break
                }

                // 每次重试前都刷新一下UI树
                refreshAccessibilityTree()
            }
        }

        android.util.Log.w("GestureActionService", "❌ 经过3次尝试仍未找到『$keyword』")
        return false
    }

    // 进入主页
    private fun clickUserAvatar() {
        val fullId = "com.ss.android.ugc.aweme:id/user_avatar"

        // 1) Try exact viewId match
        try {
            val root = rootInActiveWindow ?: run {
                android.util.Log.w("GestureActionService", "clickUserAvatar: root null")
                return
            }

            // Search for exact viewId
            fun findByViewId(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                try {
                    val vid = node.viewIdResourceName ?: ""
                    if (vid == fullId) return node
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        val found = findByViewId(child)
                        if (found != null) return found
                    }
                } catch (e: Exception) {
                    // ignore
                }
                return null
            }

            val exact = findByViewId(root)
            if (exact != null) {
                try {
                    android.util.Log.i("GestureActionService", "clickUserAvatar: found by full id, attempting click")
                    if (exact.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        try { exact.recycle() } catch (ignored: Exception) {}
                        return
                    }
                } catch (e: Exception) {
                    android.util.Log.w("GestureActionService", "clickUserAvatar performAction exception: ${e.message}")
                }

                // fallback to coordinate tap
                try {
                    val bounds = android.graphics.Rect()
                    exact.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val x = bounds.centerX().toFloat()
                        val y = bounds.centerY().toFloat()
                        android.util.Log.i("GestureActionService", "clickUserAvatar: fallback coordinate tap at ($x,$y)")
                        if (dispatchGestureAndWaitForUiChange(x, y, 1200L, 80L)) {
                            try { exact.recycle() } catch (ignored: Exception) {}
                            return
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
                try { exact.recycle() } catch (ignored: Exception) {}
            }

            // 2) Try partial id or className match (partial id contains 'user_avatar' or className 'android.widget.ImageView')
            val partialMatches = mutableListOf<AccessibilityNodeInfo>()
            fun collectPartial(node: AccessibilityNodeInfo?) {
                if (node == null) return
                try {
                    val vid = node.viewIdResourceName ?: ""
                    val cls = node.className?.toString() ?: ""
                    if (vid.contains("user_avatar") || cls == "android.widget.ImageView") {
                        partialMatches.add(node)
                    }
                    for (i in 0 until node.childCount) {
                        collectPartial(node.getChild(i))
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            collectPartial(root)
            if (partialMatches.isNotEmpty()) {
                // prefer clickable ones
                partialMatches.sortWith(compareByDescending<AccessibilityNodeInfo> { it.isClickable })
                val node = partialMatches[0]
                try {
                    android.util.Log.i("GestureActionService", "clickUserAvatar: found by partial id/class, attempting click")
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        try { node.recycle() } catch (ignored: Exception) {}
                        return
                    }
                } catch (e: Exception) {
                    android.util.Log.w("GestureActionService", "clickUserAvatar performAction exception: ${e.message}")
                }

                // fallback to coordinate
                try {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val x = bounds.centerX().toFloat()
                        val y = bounds.centerY().toFloat()
                        android.util.Log.i("GestureActionService", "clickUserAvatar: fallback coordinate tap at ($x,$y)")
                        if (dispatchGestureAndWaitForUiChange(x, y, 1200L, 80L)) {
                            try { node.recycle() } catch (ignored: Exception) {}
                            return
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
                try { node.recycle() } catch (ignored: Exception) {}
            }

            android.util.Log.w("GestureActionService", "clickUserAvatar: did not find avatar by id or class")
        } catch (e: Exception) {
            android.util.Log.w("GestureActionService", "clickUserAvatar general exception: ${e.message}")
        }
    }

    // Try to find a send/share button by locating selected target nodes and searching their ancestor's children (siblings)
    private fun findAndClickSendNearSelectedNodes(targets: List<String>): Boolean {
        val root = rootInActiveWindow ?: run {
            android.util.Log.w("GestureActionService", "findAndClickSendNearSelectedNodes: root null")
            return false
        }

        val foundTargets = mutableListOf<AccessibilityNodeInfo>()

        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val txt = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                for (t in targets) {
                    if (t.isNotEmpty() && (txt.contains(t) || desc.contains(t))) {
                        foundTargets.add(node)
                        break
                    }
                }
                for (i in 0 until node.childCount) {
                    collect(node.getChild(i))
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        collect(root)

        if (foundTargets.isEmpty()) {
            android.util.Log.i("GestureActionService", "No nodes matching targets found for sibling search")
            return false
        }

        // For each found target, walk up ancestors and inspect siblings for send-like nodes
        for (targetNode in foundTargets) {
            try {
                var ancestor = targetNode.parent
                    var depth = 0
                    // walk up ancestors (allow deeper traversal if needed)
                    while (ancestor != null && depth < 10) {
                        // inspect each child (sibling subtree) of this ancestor for send-like nodes
                        for (i in 0 until ancestor.childCount) {
                            val child = ancestor.getChild(i) ?: continue
                            try {
                                // Recursively search the child's subtree for send-like clickable nodes
                                if (tryClickSendInSubtree(child)) {
                                    try { targetNode.recycle() } catch (ignored: Exception) {}
                                    return true
                                }
                            } catch (e: Exception) {
                                // ignore per-child errors
                            }
                        }

                        // go up one level and continue
                        ancestor = ancestor.parent
                        depth++
                    }
            } catch (e: Exception) {
                // ignore per-target errors
            } finally {
                try { targetNode.recycle() } catch (ignored: Exception) {}
            }
        }

        return false
    }


    

    private fun performGestureAction(gesture: String) {
        android.util.Log.i("GestureActionService", "Performing action for gesture: $gesture")

        // Resolve gesture string to enum (fallback to NONE on error) and load mapping
        val gestureEnum = try {
            GestureClassifier.Gesture.valueOf(gesture)
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("GestureActionService", "Unknown gesture enum: $gesture, defaulting to NONE")
            GestureClassifier.Gesture.NONE
        }

        val action = GestureMappingManager.getActionForGesture(this, gestureEnum)
        android.util.Log.i("GestureActionService", "Mapped action for $gesture => $action")

        when (action) {

            GestureMappingManager.Action.NONE -> {
                Log.i("GestureActionService", "No action mapped for gesture $gesture")
            }
            // 一、上滑(下一个视频)
            GestureMappingManager.Action.NEXT -> {
                Log.i("GestureActionService", "Action NEXT: swipe up")
                swipeUp()
//                debugCurrentContentDescriptions()
            }
            // 二、下滑(上一个视频)
            GestureMappingManager.Action.PREV -> {
                Log.i("GestureActionService", "Action PREV: swipe down")
                swipeDown()
//                findAndClickByContentDescPartial("已关注")
            }
            // 三、点赞
            GestureMappingManager.Action.LIKE -> {
                Log.i("GestureActionService", "Action LIKE: try find by content-desc '点赞' or click center")
                performSixTaps()
            }

            // 四、取消点赞
            GestureMappingManager.Action.UNLIKE -> {
                Log.i("GestureActionService", "Action noLIKE: try find by content-desc '取消点赞' or click center")
                // 取消点赞
                findAndClickByContentDescPartial("已点赞")
            }

            // 五、查看评论
            GestureMappingManager.Action.OPEN_COMMENTS -> {
                Log.i("GestureActionService", "Action OPEN_COMMENTS: try find '评论' and click")
                val clicked = findAndClickByContentDescPartial("评论")
            }

            // 六、收藏
            GestureMappingManager.Action.MARK -> {
                Log.i("GestureActionService", "Action MARK: try find '收藏' and click")
                findAndClickByContentDescPartial("收藏")
            }

            // 七、关注
            GestureMappingManager.Action.FOLLOW -> {
                Log.i("GestureActionService", "Action FOLLOW: try find '关注' and click")
                findAndClickByContentDescPartial("关注")
            }

//            // 八、取消关注  找不到元素，现不做了
//            GestureMappingManager.Action.UNFOLLOW -> {
////                Log.i("GestureActionService", "Action UNFOLLOW: try find '取消关注' and click")
//                // 先滑动到主页，先不做了
//
//            }

            // 九、查看主页
            GestureMappingManager.Action.USER_AVATAR -> {
                Log.i("GestureActionService", "Action USER_AVATAR: try find '查看主页' and click")
                clickUserAvatar()
            }

            // 十、分享给好友
            GestureMappingManager.Action.SHARE -> {
                Log.i("GestureActionService", "Action SHARE: open share sheet and select configured target")

                // Read configured param for this gesture and perform share
                val shareParam = GestureMappingManager.getActionParam(this, gestureEnum)
                shareToTargets(shareParam)

            }

            GestureMappingManager.Action.CUSTOM -> {
                Log.i("GestureActionService", "Action CUSTOM: currently no-op (extend as needed)")
            }

            // 十一、返回
            GestureMappingManager.Action.BACKK -> {
                Log.i("GestureActionService", "Action BACKK: try find '返回' and click")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }

            // 十二、二倍速
            GestureMappingManager.Action.DOUBLE_SPEED -> {
                Log.i("GestureActionService", "Action DOUBLE_SPEED: try find '二倍速' and click")
                longPressThenFindAndClick("2.0")
            }

            // 十三、一倍速
            GestureMappingManager.Action.NORMAL_SPEED -> {
                Log.i("GestureActionService", "Action NORMAL_SPEED: try find '一倍速' and click")
                longPressThenFindAndClick("1.0")
            }

        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterReceiver(gestureReceiver)
        return super.onUnbind(intent)
    }
}
