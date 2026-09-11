package com.keyspirit.floating

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.keyspirit.script.Script
import com.keyspirit.script.ScriptStep
import com.keyspirit.script.StepType

/**
 * 悬浮脚本编辑器：列出脚本步骤，支持交互式添加步骤
 */
class FloatingEditorView(context: Context) : LinearLayout(context) {

    interface EditorListener {
        fun onAddStep(type: StepType)
        fun onEditStep(position: Int, step: ScriptStep)
        fun onDeleteStep(position: Int)
        fun onSave()
        fun onRun()
        fun onClose()
    }

    var listener: EditorListener? = null
    private var script: Script? = null

    private val stepContainer: LinearLayout
    private val scrollView: ScrollView

    init {
        orientation = VERTICAL
        setPadding(16, 16, 16, 16)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F01E1E1E"))
            cornerRadius = 20f
        }

        // 标题栏
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTitle = TextView(context).apply {
            text = "脚本编辑"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(8, 8, 8, 8)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(16, 8, 16, 8)
            setOnClickListener { listener?.onClose() }
        }
        titleBar.addView(tvTitle)
        titleBar.addView(btnClose)
        addView(titleBar)

        // 步骤列表（可滚动）
        scrollView = ScrollView(context)
        stepContainer = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        scrollView.addView(stepContainer)
        val scrollParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = 8
            bottomMargin = 8
        }
        addView(scrollView, scrollParams)

        // 操作栏：添加步骤
        val actionBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        val btnAdd = Button(context).apply {
            text = "+ 添加步骤"
            setOnClickListener { showStepTypeMenu() }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        actionBar.addView(btnAdd)
        addView(actionBar)

        // 底部：保存 / 运行
        val bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        val btnSave = Button(context).apply {
            text = "保存"
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { listener?.onSave() }
        }
        val btnRun = Button(context).apply {
            text = "运行"
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { listener?.onRun() }
        }
        bottomBar.addView(btnSave)
        bottomBar.addView(btnRun)
        addView(bottomBar)
    }

    fun setScript(s: Script) {
        script = s
        refreshStepList()
    }

    fun refreshStepList() {
        stepContainer.removeAllViews()
        val steps = script?.steps ?: return
        if (steps.isEmpty()) {
            val empty = TextView(context).apply {
                text = "暂无步骤，点击下方\"添加步骤\"开始"
                setTextColor(Color.parseColor("#888888"))
                textSize = 13f
                setPadding(16, 32, 16, 32)
                gravity = Gravity.CENTER
            }
            stepContainer.addView(empty)
            return
        }
        steps.forEachIndexed { index, step ->
            val row = createStepRow(index, step)
            stepContainer.addView(row)
        }
    }

    private fun createStepRow(index: Int, step: ScriptStep): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 12, 8, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2A2A"))
                cornerRadius = 10f
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 6
            }
        }

        val num = TextView(context).apply {
            text = "${index + 1}"
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            setPadding(8, 0, 8, 0)
        }
        val info = TextView(context).apply {
            text = "${step.type.displayName}\n${step.getDescription()}"
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnEdit = TextView(context).apply {
            text = "编辑"
            setTextColor(Color.parseColor("#3498DB"))
            textSize = 12f
            setPadding(12, 0, 12, 0)
            setOnClickListener { listener?.onEditStep(index, step) }
        }
        val btnDel = TextView(context).apply {
            text = "删"
            setTextColor(Color.parseColor("#E74C3C"))
            textSize = 12f
            setPadding(12, 0, 12, 0)
            setOnClickListener { listener?.onDeleteStep(index) }
        }

        row.addView(num)
        row.addView(info)
        row.addView(btnEdit)
        row.addView(btnDel)
        return row
    }

    private fun showStepTypeMenu() {
        val types = arrayOf(
            StepType.CLICK,
            StepType.FIND_IMAGE,
            StepType.FIND_TEXT,
            StepType.SWIPE,
            StepType.LONG_PRESS,
            StepType.DELAY
        )
        val names = types.map { it.displayName }.toTypedArray()
        val dialog = android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("选择步骤类型")
            .setItems(names) { _, which ->
                listener?.onAddStep(types[which])
            }
            .setNegativeButton("取消", null)
            .create()
        // 从 Service 上下文弹 Dialog 必须设置窗口类型为悬浮窗，否则 BadTokenException 崩溃
        dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }
}
