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
 * 悬浮脚本编辑器：按键精灵风格
 * 左边 = 命令面板（所有可用命令，点击即添加）
 * 右边 = 步骤列表（当前脚本步骤，缩进显示 IF 块）
 */
class FloatingEditorView(context: Context) : LinearLayout(context) {

    interface EditorListener {
        fun onAddCommand(type: StepType, path: List<Int>)
        fun onEditStep(path: List<Int>, step: ScriptStep)
        fun onDeleteStep(path: List<Int>)
        fun onSave()
        fun onRun()
        fun onClose()
    }

    var listener: EditorListener? = null
    private var script: Script? = null

    // 插入位置：空列表=末尾，非空=插入到该路径的 IF 块末尾
    var insertPath: List<Int> = emptyList()
        set(value) {
            field = value
            updateInsertLabel()
        }
    private var insertLabel: TextView? = null

    private val stepContainer: LinearLayout
    private val stepScrollView: ScrollView

    // 所有命令分类
    private val commandGroups = listOf(
        "点击操作" to listOf(
            StepType.CLICK, StepType.LONG_PRESS,
            StepType.TOUCH_DOWN, StepType.TOUCH_UP,
            StepType.RIGHT_CLICK, StepType.RIGHT_CLICK_DOWN, StepType.RIGHT_CLICK_UP
        ),
        "鼠标操作" to listOf(StepType.MOVE_MOUSE, StepType.PICK_POINT),
        "滑动操作" to listOf(StepType.SWIPE),
        "截图" to listOf(StepType.SCREENSHOT),
        "控制流程" to listOf(StepType.IF, StepType.LOOP, StepType.DELAY),
        "查找识别" to listOf(StepType.FIND_IMAGE, StepType.FIND_TEXT)
    )

    init {
        orientation = HORIZONTAL
        setPadding(8, 8, 8, 8)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F01E1E1E"))
            cornerRadius = 16f
        }

        // ========== 左边：命令面板 ==========
        val cmdPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(8, 8, 4, 8)
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.35f)
        }
        val cmdTitle = TextView(context).apply {
            text = "命令"
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            setPadding(4, 0, 0, 4)
        }
        cmdPanel.addView(cmdTitle)

        val cmdScroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val cmdList = LinearLayout(context).apply { orientation = VERTICAL }
        cmdScroll.addView(cmdList)
        cmdPanel.addView(cmdScroll)

        // 填充命令按钮
        for ((groupName, types) in commandGroups) {
            val groupLabel = TextView(context).apply {
                text = groupName
                setTextColor(Color.parseColor("#666666"))
                textSize = 11f
                setPadding(4, 8, 0, 4)
            }
            cmdList.addView(groupLabel)
            for (type in types) {
                val btn = createCommandButton(type)
                cmdList.addView(btn)
            }
        }

        // ========== 右边：步骤列表 ==========
        val stepPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(4, 8, 8, 8)
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.65f)
        }

        // 标题栏
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTitle = TextView(context).apply {
            text = "脚本步骤"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(4, 0, 0, 4)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(12, 4, 8, 4)
            setOnClickListener { listener?.onClose() }
        }
        titleBar.addView(tvTitle)
        titleBar.addView(btnClose)
        stepPanel.addView(titleBar)

        // 插入位置提示
        insertLabel = TextView(context).apply {
            text = "▶ 插入位置：末尾"
            setTextColor(Color.parseColor("#F39C12"))
            textSize = 11f
            setPadding(4, 2, 0, 4)
        }
        stepPanel.addView(insertLabel!!)

        // 步骤列表
        stepScrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        stepContainer = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        stepScrollView.addView(stepContainer)
        stepPanel.addView(stepScrollView)

        // 底部按钮
        val bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        val btnSave = Button(context).apply {
            text = "保存"
            textSize = 12f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { listener?.onSave() }
        }
        val btnRun = Button(context).apply {
            text = "运行"
            textSize = 12f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { listener?.onRun() }
        }
        bottomBar.addView(btnSave)
        bottomBar.addView(btnRun)
        stepPanel.addView(bottomBar)

        // 组装左右面板
        addView(cmdPanel)
        addView(stepPanel)
    }

    /**
     * 创建左侧命令按钮
     */
    private fun createCommandButton(type: StepType): View {
        val color = when (type) {
            StepType.SCREENSHOT -> "#E74C3C"
            StepType.MOVE_MOUSE -> "#1ABC9C"
            StepType.PICK_POINT -> "#16A085"
            StepType.IF -> "#3498DB"
            StepType.LOOP -> "#9B59B6"
            StepType.FIND_IMAGE, StepType.FIND_TEXT -> "#E67E22"
            StepType.DELAY -> "#95A5A6"
            else -> "#2E7D32"
        }
        return TextView(context).apply {
            text = type.displayName
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(8, 10, 8, 10)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color))
                cornerRadius = 8f
                alpha = 200
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 4
            }
            setOnClickListener {
                listener?.onAddCommand(type, insertPath)
            }
        }
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
                text = "暂无步骤\n点击左侧命令添加"
                setTextColor(Color.parseColor("#555555"))
                textSize = 12f
                setPadding(16, 40, 16, 40)
                gravity = Gravity.CENTER
            }
            stepContainer.addView(empty)
            return
        }
        renderSteps(steps, emptyList(), 0)
    }

    /**
     * 递归渲染一组步骤
     */
    private fun renderSteps(steps: MutableList<ScriptStep>, parentPath: List<Int>, depth: Int) {
        steps.forEachIndexed { index, step ->
            val currentPath = parentPath + index
            val row = createStepRow(index, step, currentPath, depth)
            stepContainer.addView(row)

            // IF 块的子步骤（安全检查：ifSteps 可能为 null）
            @Suppress("SENSELESS_COMPARISON")
            if (step.type == StepType.IF && step.ifSteps != null && step.ifSteps.isNotEmpty()) {
                renderSteps(step.ifSteps, currentPath, depth + 1)
            }
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
            setPadding(6, 8, 6, 8)
            background = when {
                step.type == StepType.IF -> GradientDrawable().apply {
                    setColor(Color.parseColor("#1A3498DB"))
                    cornerRadius = 6f
                }
                step.type == StepType.LOOP -> GradientDrawable().apply {
                    setColor(Color.parseColor("#1A9B59B6"))
                    cornerRadius = 6f
                }
                depth > 0 -> GradientDrawable().apply {
                    setColor(Color.parseColor("#252525"))
                    cornerRadius = 6f
                }
                else -> GradientDrawable().apply {
                    setColor(Color.parseColor("#2A2A2A"))
                    cornerRadius = 6f
                }
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 3
                leftMargin = depth * 20
            }
        }

        // 序号
        val prefix = if (depth > 0) "└ " else ""
        val num = TextView(context).apply {
            text = "$prefix${index + 1}"
            setTextColor(Color.parseColor("#666666"))
            textSize = 10f
            setPadding(4, 0, 4, 0)
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
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val tvDesc = TextView(context).apply {
            text = step.getDescription()
            setTextColor(Color.parseColor("#999999"))
            textSize = 10f
        }
        infoLayout.addView(tvType)
        infoLayout.addView(tvDesc)

        // IF 类型加一个「设为插入点」按钮
        if (step.type == StepType.IF) {
            val btnInsert = TextView(context).apply {
                text = "+子"
                setTextColor(Color.parseColor("#2ECC71"))
                textSize = 10f
                setPadding(6, 0, 6, 0)
                setOnClickListener {
                    insertPath = path
                    updateInsertLabel()
                }
            }
            row.addView(num)
            row.addView(infoLayout)
            row.addView(btnInsert)
        } else {
            row.addView(num)
            row.addView(infoLayout)
        }

        // 编辑/删除
        val btnEdit = TextView(context).apply {
            text = "编"
            setTextColor(Color.parseColor("#3498DB"))
            textSize = 10f
            setPadding(6, 0, 6, 0)
            setOnClickListener { listener?.onEditStep(path, step) }
        }
        val btnDel = TextView(context).apply {
            text = "删"
            setTextColor(Color.parseColor("#E74C3C"))
            textSize = 10f
            setPadding(6, 0, 6, 0)
            setOnClickListener { listener?.onDeleteStep(path) }
        }
        row.addView(btnEdit)
        row.addView(btnDel)

        return row
    }

    private fun updateInsertLabel() {
        val text = if (insertPath.isEmpty()) {
            "▶ 插入位置：末尾"
        } else {
            val pos = insertPath.joinToString("→") { (it + 1).toString() }
            "▶ 插入位置：第 $pos 个 IF 内"
        }
        insertLabel?.text = text
    }

    /**
     * 重置插入位置到末尾
     */
    fun resetInsertPosition() {
        insertPath = emptyList()
        updateInsertLabel()
    }
}
