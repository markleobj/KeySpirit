package com.keyspirit.floating

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 悬浮球：绘制一个带播放图标的圆形按钮
 */
class FloatingBallView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3498DB")
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val playPath = Path()

    enum class BallState { IDLE, RECORDING, EXECUTING, PAUSED }

    var state: BallState = BallState.IDLE
        set(value) {
            field = value
            bgPaint.color = when (value) {
                BallState.IDLE -> Color.parseColor("#3498DB")
                BallState.RECORDING -> Color.parseColor("#E74C3C")
                BallState.EXECUTING -> Color.parseColor("#2E7D32")
                BallState.PAUSED -> Color.parseColor("#F39C12")
            }
            invalidate()
        }

    @Deprecated("Use state instead")
    var isRecording: Boolean
        get() = state == BallState.RECORDING
        set(value) {
            state = if (value) BallState.RECORDING else BallState.IDLE
        }

    var onTap: (() -> Unit)? = null
    var onDrag: ((x: Int, y: Int) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var isDragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = cx

        // 背景圆
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 播放三角形图标（居中）
        val triW = radius * 0.5f
        val triH = radius * 0.6f
        playPath.reset()
        playPath.moveTo(cx - triW / 2, cy - triH / 2)
        playPath.lineTo(cx - triW / 2, cy + triH / 2)
        playPath.lineTo(cx + triW / 2, cy)
        playPath.close()
        canvas.drawPath(playPath, iconPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (abs(dx) > 10 || abs(dy) > 10) {
                    isDragging = true
                    onDrag?.invoke(
                        (event.rawX - downX).toInt(),
                        (event.rawY - downY).toInt()
                    )
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    onTap?.invoke()
                }
            }
        }
        return true
    }
}
