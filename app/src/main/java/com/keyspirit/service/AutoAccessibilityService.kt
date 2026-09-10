package com.keyspirit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.WindowManager
import com.keyspirit.record.TouchRecorder

class AutoAccessibilityService : AccessibilityService() {

    // 手势回调
    interface GestureCallback {
        fun onCompleted()
        fun onCancelled()
    }

    companion object {
        private const val TAG = "AutoAccessibility"

        var instance: AutoAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    var touchRecorder: TouchRecorder? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可在此处理无障碍事件
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "无障碍服务已销毁")
    }

    /**
     * 点击指定坐标
     */
    fun click(x: Int, y: Int, callback: GestureCallback? = null) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.onCompleted()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.onCancelled()
            }
        }, handler)
    }

    /**
     * 触摸按下（保持）
     */
    fun touchDown(x: Int, y: Int, duration: Long, callback: GestureCallback? = null) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.onCompleted()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.onCancelled()
            }
        }, handler)
    }

    /**
     * 滑动
     */
    fun swipe(
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        duration: Long,
        callback: GestureCallback? = null
    ) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.onCompleted()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.onCancelled()
            }
        }, handler)
    }

    /**
     * 长按
     */
    fun longPress(x: Int, y: Int, duration: Long, callback: GestureCallback? = null) {
        touchDown(x, y, duration, callback)
    }

    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Point {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val point = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let {
                point.x = it.width()
                point.y = it.height()
            }
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
        }
        return point
    }
}
