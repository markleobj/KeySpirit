package com.keyspirit.floating

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class FloatingPanelView(context: Context) : LinearLayout(context) {

    enum class PanelMode { HOME, RECORDING, EXECUTING }

    var onRecord: (() -> Unit)? = null
    var onStopRecord: (() -> Unit)? = null
    var onPickCoordinate: (() -> Unit)? = null
    var onPickRegion: (() -> Unit)? = null
    var onScreenshot: (() -> Unit)? = null
    var onExecute: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStopExecute: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val container: LinearLayout

    init {
        orientation = HORIZONTAL
        setPadding(16, 16, 16, 16)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E6000000"))
            cornerRadius = 24f
        }
        container = this
    }

    fun setMode(mode: PanelMode, extraInfo: String = "") {
        removeAllViews()
        when (mode) {
            PanelMode.HOME -> {
                addButton("录制", "#E74C3C") { onRecord?.invoke() }
                addButton("截图", "#9B59B6") { onScreenshot?.invoke() }
                addButton("取坐标", "#3498DB") { onPickCoordinate?.invoke() }
                addButton("框选区域", "#1ABC9C") { onPickRegion?.invoke() }
                addButton("运行", "#2E7D32") { onExecute?.invoke() }
                addButton("✕", "#95A5A6") { onClose?.invoke() }
                // 版本号显示
                try {
                    val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    addInfoText("v$versionName")
                } catch (e: Exception) {}
            }
            PanelMode.RECORDING -> {
                addButton("停止", "#E74C3C") { onStopRecord?.invoke() }
                addButton("✕", "#95A5A6") { onClose?.invoke() }
            }
            PanelMode.EXECUTING -> {
                addButton("暂停", "#F39C12") { onPause?.invoke() }
                addButton("停止", "#E74C3C") { onStopExecute?.invoke() }
                if (extraInfo.isNotEmpty()) {
                    addInfoText(extraInfo)
                }
            }
        }
    }

    private fun addButton(text: String, color: String, onClick: () -> Unit) {
        val btn = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color))
                cornerRadius = 16f
            }
            setOnClickListener { onClick() }
        }
        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = 8
        }
        addView(btn, params)
    }

    private fun addInfoText(text: String) {
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 0)
        }
        addView(tv)
    }
}
