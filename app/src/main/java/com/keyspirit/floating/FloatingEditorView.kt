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
 * 悬浮脚本编辑器：列表式编辑，IF/LOOP 块内子步骤缩进显示
 * 参考按键精灵的清晰结构
 */
class FloatingEditorView(context: Context) : LinearLayout(context) {

    interface EditorListener {
        fun onAddStep(type: StepType, path: List<Int>)
        fun onEditStep(path: List<Int>, step: ScriptStep)
        fun onDeleteStep(path: List<Int>)
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

        // 底部：保存 / 运行 / 添加步骤
        val bottomBar = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, 8, 0, 0)
        }
        val btnAdd = Button(context).apply {
            text = "+ 添加步骤"
            setOnClickListener { showStepTypeMenu(emptyList()) }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        bottomBar.addView(btnAdd)

        val bottomActions = LinearLayout(context).apply {
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
        bottomActions.addView(btnSave)
        bottomActions.addView(btnRun)
        bottomBar.addView(bottomActions)
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
        // 递归渲染根级别步骤
        renderSteps(steps, emptyList(), 0)
    }

    /**
     * 递归渲染一组步骤
     * @param steps 步骤列表
     * @param parentPath 父路径（用于定位）
     * @param depth 缩进深度
     */
    private fun renderSteps(steps: MutableList<ScriptStep>, parentPath: List<Int>, depth: Int) {
        steps.forEachIndexed { index, step ->
            val currentPath = parentPath + index
            val row = createStepRow(index, step, currentPath, depth)
            stepContainer.addView(row)

            // 如果是 IF 或 LOOP 类型，递归渲染其子步骤
            if (step.type == StepType.IF && step.ifSteps.isNotEmpty()) {
                renderSteps(step.ifSteps, currentPath, depth + 1)
            }
            // LOOP 暂时用 loopStartIndex/loopEndIndex 范围式，没有子步骤列表
        }
    }

    private fun createStepRow(
        index: Int,
        step: ScriptStep,
        path: List<Int>,
        depth: Int
    ): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 10, 8, 10)
            background = when {
                step.type == StepType.IF -> GradientDrawable().apply {
                    setColor(Color.parseColor("#1A3498DB"))
                    cornerRadius = 8f
                }
                step.type == StepType.LOOP -> GradientDrawable().apply {
                    setColor(Color.parseColor("#1A9B59B6"))
                    cornerRadius = 8f
                }
                depth > 0 -> GradientDrawable().apply {
                    setColor(Color.parseColor("#252525"))
                    cornerRadius = 8f
                }
                else -> GradientDrawable().apply {
                    setColor(Color.parseColor("#2A2A2A"))
                    cornerRadius = 8f
                }
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 4
                leftMargin = depth * 24
            }
        }

        // 序号
        val prefix = if (depth > 0) "└ " else ""
        val num = TextView(context).apply {
            text = "$prefix${index + 1}"
            setTextColor(Color.parseColor("#888888"))
            textSize = 11f
            setPadding(4, 0, 6, 0)
        }

        // 步骤信息
        val infoLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvType = TextView(context).apply {
            text = step.type.displayName
            setTextColor(when {
                step.type == StepType.IF -> Color.parseColor("#5DADE2")
                step.type == StepType.LOOP -> Color.parseColor("#AF7AC5")
                else -> Color.WHITE
            })
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val tvDesc = TextView(context).apply {
            text = step.getDescription()
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 11f
        }
        infoLayout.addView(tvType)
        infoLayout.addView(tvDesc)

        // 操作按钮
        val btnAddSub = if (step.type == StepType.IF) {
            TextView(context).apply {
                text = "+子"
                setTextColor(Color.parseColor("#2ECC71"))
                textSize = 11f
                setPadding(8, 0, 8, 0)
                setOnClickListener { showStepTypeMenu(path) }
            }
        } else null

        val btnEdit = TextView(context).apply {
            text = "编辑"
            setTextColor(Color.parseColor("#3498DB"))
            textSize = 11f
            setPadding(10, 0, 10, 0)
            setOnClickListener { listener?.onEditStep(path, step) }
        }
        val btnDel = TextView(context).apply {
            text = "删"
            setTextColor(Color.parseColor("#E74C3C"))
            textSize = 11f
            setPadding(10, 0, 10, 0)
            setOnClickListener { listener?.onDeleteStep(path) }
        }

        row.addView(num)
        row.addView(infoLayout)
        if (btnAddSub != null) row.addView(btnAddSub)
        row.addView(btnEdit)
        row.addView(btnDel)
        return row
    }

    private fun showStepTypeMenu(path: List<Int>) {
        val types = arrayOf(
            StepType.CLICK,
            StepType.LONG_PRESS,
            StepType.TOUCH_DOWN,
            StepType.TOUCH_UP,
            StepType.RIGHT_CLICK,
            StepType.RIGHT_CLICK_DOWN,
            StepType.RIGHT_CLICK_UP,
            StepType.SWIPE,
            StepType.DELAY,
            StepType.FIND_IMAGE,
            StepType.FIND_TEXT,
            StepType.IF,
            StepType.LOOP
        )
        val names = types.map { it.displayName }.toTypedArray()
        val dialog = android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("选择步骤类型")
            .setItems(names) { _, which ->
                listener?.onAddStep(types[which], path)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }
}
