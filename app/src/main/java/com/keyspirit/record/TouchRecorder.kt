package com.keyspirit.record

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import com.keyspirit.script.ScriptStep
import com.keyspirit.script.StepType
import com.keyspirit.service.AutoAccessibilityService

class TouchRecorder {

    companion object {
        private const val TAG = "TouchRecorder"
        private const val LONG_PRESS_THRESHOLD = 500L // 长按阈值 ms
        private const val SWIPE_DISTANCE_THRESHOLD = 20f // 滑动距离阈值 px
    }

    private val steps = mutableListOf<ScriptStep>()
    private var isRecording = false

    // 当前手势状态（使用屏幕绝对坐标 rawX/rawY）
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var hasMoved = false

    fun startRecording() {
        steps.clear()
        isRecording = true
        Log.d(TAG, "开始录制")
    }

    fun stopRecording(): List<ScriptStep> {
        isRecording = false
        Log.d(TAG, "停止录制，共 ${steps.size} 步")
        return steps.toList()
    }

    fun isRecording(): Boolean = isRecording

    fun onTouchEvent(event: MotionEvent) {
        if (!isRecording) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = SystemClock.elapsedRealtime()
                hasMoved = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(event.rawX - downX)
                val dy = Math.abs(event.rawY - downY)
                if (dx > SWIPE_DISTANCE_THRESHOLD || dy > SWIPE_DISTANCE_THRESHOLD) {
                    hasMoved = true
                }
            }

            MotionEvent.ACTION_UP -> {
                val upX = event.rawX
                val upY = event.rawY
                val duration = SystemClock.elapsedRealtime() - downTime

                if (hasMoved) {
                    // 滑动
                    steps.add(ScriptStep(
                        type = StepType.SWIPE,
                        x1 = downX.toInt(),
                        y1 = downY.toInt(),
                        x2 = upX.toInt(),
                        y2 = upY.toInt(),
                        duration = duration.coerceIn(100, 5000)
                    ))
                } else if (duration >= LONG_PRESS_THRESHOLD) {
                    // 长按
                    steps.add(ScriptStep(
                        type = StepType.LONG_PRESS,
                        x = downX.toInt(),
                        y = downY.toInt(),
                        duration = duration
                    ))
                } else {
                    // 点击
                    steps.add(ScriptStep(
                        type = StepType.CLICK,
                        x = downX.toInt(),
                        y = downY.toInt()
                    ))
                }

                // 在步骤之间添加默认延迟
                steps.add(ScriptStep(
                    type = StepType.DELAY,
                    delay = 500
                ))

                Log.d(TAG, "录制步骤: ${steps[steps.size - 2].getDescription()}")
            }
        }
    }

    /**
     * 将录制的手势转发给无障碍服务，让底层 App 能响应
     * 在 UP 时根据完整手势 dispatch
     */
    fun dispatchToApp(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_UP) return
        val service = AutoAccessibilityService.instance ?: return
        if (hasMoved) {
            service.swipe(
                downX.toInt(), downY.toInt(),
                event.rawX.toInt(), event.rawY.toInt(),
                (SystemClock.elapsedRealtime() - downTime).coerceIn(100, 5000)
            )
        } else {
            service.click(downX.toInt(), downY.toInt())
        }
    }
}
