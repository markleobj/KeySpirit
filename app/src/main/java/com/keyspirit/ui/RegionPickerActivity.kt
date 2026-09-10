package com.keyspirit.ui

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.keyspirit.R

class RegionPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LEFT = "region_left"
        const val EXTRA_TOP = "region_top"
        const val EXTRA_RIGHT = "region_right"
        const val EXTRA_BOTTOM = "region_bottom"
    }

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isSelecting = false

    private lateinit var drawView: RegionDrawView
    private lateinit var infoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        // 半透明覆盖层
        drawView = RegionDrawView(this).apply {
            setBackgroundColor(0x33000000.toInt())
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        root.addView(drawView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 提示文字
        infoText = TextView(this).apply {
            text = "按住并拖动以选择区域，松开确认"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(32, 16, 32, 16)
            textSize = 14f
        }
        root.addView(infoText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 100
        })

        setContentView(root)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                isSelecting = true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                val left = Math.min(startX, currentX).toInt()
                val top = Math.min(startY, currentY).toInt()
                val right = Math.max(startX, currentX).toInt()
                val bottom = Math.max(startY, currentY).toInt()
                infoText.text = "区域: ($left, $top) - ($right, $bottom)  宽:${right - left} 高:${bottom - top}"
            }
            MotionEvent.ACTION_UP -> {
                isSelecting = false
                val left = Math.min(startX, currentX).toInt()
                val top = Math.min(startY, currentY).toInt()
                val right = Math.max(startX, currentX).toInt()
                val bottom = Math.max(startY, currentY).toInt()

                // 区域太小则取消
                if (right - left < 10 || bottom - top < 10) {
                    infoText.text = "区域太小，请重新选择"
                    return true
                }

                // 返回结果
                intent.putExtra(EXTRA_LEFT, left)
                intent.putExtra(EXTRA_TOP, top)
                intent.putExtra(EXTRA_RIGHT, right)
                intent.putExtra(EXTRA_BOTTOM, bottom)
                setResult(RESULT_OK, intent)
                finish()
            }
        }
        drawView.invalidate()
        return true
    }

    inner class RegionDrawView(context: android.content.Context) : View(context) {
        private val rectPaint = Paint().apply {
            color = Color.parseColor("#3498DB")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val fillPaint = Paint().apply {
            color = Color.parseColor("#333498DB")
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isSelecting) {
                val left = Math.min(startX, currentX)
                val top = Math.min(startY, currentY)
                val right = Math.max(startX, currentX)
                val bottom = Math.max(startY, currentY)
                canvas.drawRect(left, top, right, bottom, fillPaint)
                canvas.drawRect(left, top, right, bottom, rectPaint)
            }
        }
    }
}
