package com.keyspirit.floating

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.keyspirit.R

class FloatingPanelView(context: Context) : LinearLayout(context) {

    enum class PanelMode { HOME, RECORDING, EXECUTING }

    var onRecord: (() -> Unit)? = null
    var onStopRecord: (() -> Unit)? = null
    var onEdit: (() -> Unit)? = null
    var onSave: (() -> Unit)? = null
    var onExecute: (() -> Unit)? = null
    var onScreenshot: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStopExecute: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val container: LinearLayout

    private fun dp(id: Int): Int = context.resources.getDimensionPixelSize(id)
    @Suppress("DEPRECATION")
    private fun sp(id: Int): Float = context.resources.getDimension(id) / context.resources.displayMetrics.scaledDensity

    init {
        orientation = HORIZONTAL
        val pad = dp(R.dimen.floating_panel_padding)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E6000000"))
            cornerRadius = dp(R.dimen.floating_panel_radius).toFloat()
        }
        container = this
    }

    fun setMode(mode: PanelMode, extraInfo: String = "") {
        removeAllViews()
        when (mode) {
            PanelMode.HOME -> {
                addButton("录制", "#E74C3C") { onRecord?.invoke() }
                addButton("编辑", "#3498DB") { onEdit?.invoke() }
                addButton("保存", "#9B59B6") { onSave?.invoke() }
                addButton("截图", "#00897B") { onScreenshot?.invoke() }
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
        val padH = dp(R.dimen.floating_btn_padding_h)
        val padV = dp(R.dimen.floating_btn_padding_v)
        val btn = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = sp(R.dimen.floating_btn_text_size)
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color))
                cornerRadius = dp(R.dimen.floating_panel_radius).toFloat()
            }
            setOnClickListener { onClick() }
        }
        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(R.dimen.floating_btn_margin)
        }
        addView(btn, params)
    }

    private fun addInfoText(text: String) {
        val padH = dp(R.dimen.floating_info_padding_h)
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = sp(R.dimen.floating_info_text_size)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, 0, padH, 0)
        }
        addView(tv)
    }
}
