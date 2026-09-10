package com.keyspirit.floating

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class FloatingBallView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3498DB")
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    var iconText = "●"
    var isRecording = false
        set(value) {
            field = value
            paint.color = if (value) Color.parseColor("#E74C3C") else Color.parseColor("#3498DB")
            invalidate()
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
        canvas.drawCircle(cx, cy, cx, paint)
        // 绘制图标
        val yPos = cy - (iconPaint.descent() + iconPaint.ascent()) / 2
        canvas.drawText(iconText, cx, yPos, iconPaint)
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
