package com.tvvpn.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

/**
 * 远程输入注入（**严格 rootless**）。用系统无障碍能力把远端发来的点按/滑动/文本落到屏幕上：
 *  - 点按 / 滑动：[dispatchGesture]（**需 API 24+**，低于 24 无法注入手势）。
 *  - 返回 / 主页 / 最近任务：[performGlobalAction]（API 16+）。
 *  - 文本：找当前聚焦的可编辑节点，用 ACTION_SET_TEXT 写入（API 21+）。
 *
 * 不使用 root、不注入原始 KeyEvent（rootless 下办不到），因此 D-pad 方向键这类无法模拟——
 * 这是无障碍方案的固有限制，UI/README 已说明。服务实例通过 [instance] 暴露给
 * [RemoteHttpServer] 调用；未开启无障碍时 instance 为 null，HTTP 侧据此回错。
 */
class RemoteInputAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接，远程输入可用（gesture 需 API24+，当前 SDK=${Build.VERSION.SDK_INT}）")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // 我们不消费事件，只做注入。留空即可。
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** 点按 (x,y)（屏幕像素坐标）。返回是否已派发。 */
    fun tap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 滑动 (x1,y1)->(x2,y2)，duration 毫秒。 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val dur = durationMs.coerceIn(1, 10_000)
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 全局动作：back / home / recents。 */
    fun globalAction(name: String): Boolean = when (name.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    /** 往当前聚焦的可编辑控件写入文本。 */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        private const val TAG = "RemoteInputA11y"
        /** onServiceConnected 时置位；HTTP 服务据此判断远程输入是否可用。 */
        @Volatile
        var instance: RemoteInputAccessibilityService? = null
            private set

        /** 无障碍是否已开启且可注入手势（API24+）。 */
        fun canInject(): Boolean =
            instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
}
