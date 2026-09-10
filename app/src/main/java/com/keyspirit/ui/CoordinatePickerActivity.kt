package com.keyspirit.ui

import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.keyspirit.R

class CoordinatePickerActivity : AppCompatActivity() {

    private var infoView: TextView? = null
    private var windowManager: WindowManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明全屏覆盖
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val overlay = View(this).apply {
            setBackgroundColor(0x33000000) // 半透明黑色
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        showCoordinate(event.x.toInt(), event.y.toInt())
                    }
                    MotionEvent.ACTION_UP -> {
                        // 确认坐标
                        val x = event.x.toInt()
                        val y = event.y.toInt()
                        // 返回结果
                        intent.putExtra("x", x)
                        intent.putExtra("y", y)
                        setResult(RESULT_OK, intent)
                        finish()
                    }
                }
                true
            }
        }

        addContentView(overlay, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 显示提示
        infoView = TextView(this).apply {
            text = "点击屏幕任意位置选取坐标"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(32, 16, 32, 16)
            textSize = 14f
        }
        addContentView(infoView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 100
        })
    }

    private fun showCoordinate(x: Int, y: Int) {
        infoView?.text = "X: $x, Y: $y  （点击确认）"
    }
}
