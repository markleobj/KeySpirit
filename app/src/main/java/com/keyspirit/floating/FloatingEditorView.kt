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
import com.keyspirit.R
import com.keyspirit.script.Script
import com.keyspirit.script.ScriptStep
import com.keyspirit.script.StepType

/**
 * 悬浮脚本编辑器：按键精灵风格
 * 左边 = 命令面板（点击命令后弹出之前/之后插入选择）
 * 右边 = 步骤列表（支持展开/收缩的树形结构）
 *
 * +/- 按钮 = 展开/收缩块
 * 点击行 = 选中该行（高亮）
 * 选中后点左侧命令 → 弹出"之前/之后"选择
 */
class FloatingEditorView(context: Context) : LinearLayout(context) {

    private fun dp(id: Int): Int = context.resources.getDimensionPixelSize(id)
    @Suppress("DEPRECATION")
    private fun sp(id: Int): Float = context.resources.getDimension(id) / context.resources.displayMetrics.scaledDensity

    interface EditorListener {
        fun onAddCommand(type: StepType, selectedPath: List<Int>?)
        fun onEditStep(path: List<Int>, step: ScriptStep)
        fun onDeleteStep(path: List<Int>)
        fun onSave()
        fun onRun()
        fun onClose()
    }

    var listener: EditorListener? = null
    private var script: Script? = null

    // 选中的步骤路径，null = 未选中（添加到末尾）
    var selectedPath: List<Int>? = null
        private set
    private var insertLabel: TextView? = null

    private val stepContainer: LinearLayout
    private val stepScrollView: ScrollView

    // 记录每个块的展开状态，key = path 的字符串形式
    private val expandedPaths = mutableSetOf<String>()

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
        setPadding(dp(R.dimen.editor_padding), dp(R.dimen.editor_padding), dp(R.dimen.editor_padding), dp(R.dimen.editor_padding))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F01E1E1E"))
            cornerRadius = dp(R.dimen.editor_radius).toFloat()
        }

        // ========== 左边：命令面板 ==========
        val cmdPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(R.dimen.spacing_sm), dp(R.dimen.spacing_sm), dp(R.dimen.spacing_xs), dp(R.dimen.spacing_sm))
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.35f)
        }
        val cmdTitle = TextView(context).apply {
            text = "命令"
            setTextColor(Color.parseColor("#888888"))
            textSize = sp(R.dimen.editor_cmd_text_size)
            setPadding(dp(R.dimen.spacing_xs), 0, 0, dp(R.dimen.spacing_xs))
        }
        cmdPanel.addView(cmdTitle)

        val cmdScroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val cmdList = LinearLayout(context).apply { orientation = VERTICAL }
        cmdScroll.addView(cmdList)
        cmdPanel.addView(cmdScroll)

        for ((groupName, types) in commandGroups) {
            val groupLabel = TextView(context).apply {
                text = groupName
                setTextColor(Color.parseColor("#666666"))
                textSize = sp(R.dimen.editor_group_label_size)
                setPadding(dp(R.dimen.spacing_xs), dp(R.dimen.spacing_sm), 0, dp(R.dimen.spacing_xs))
            }
            cmdList.addView(groupLabel)
            for (type in types) {
                cmdList.addView(createCommandButton(type))
            }
        }

        // ========== 右边：步骤列表 ==========
        val stepPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(R.dimen.spacing_xs), dp(R.dimen.spacing_sm), dp(R.dimen.spacing_sm), dp(R.dimen.spacing_sm))
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.65f)
        }

        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTitle = TextView(context).apply {
            text = "脚本步骤"
            setTextColor(Color.WHITE)
            textSize = sp(R.dimen.editor_title_size)
            setPadding(dp(R.dimen.spacing_xs), 0, 0, dp(R.dimen.spacing_xs))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = sp(R.dimen.editor_close_size)
            setPadding(dp(R.dimen.spacing_md), dp(R.dimen.spacing_xs), dp(R.dimen.spacing_sm), dp(R.dimen.spacing_xs))
            setOnClickListener { listener?.onClose() }
        }
        titleBar.addView(tvTitle)
        titleBar.addView(btnClose)
        stepPanel.addView(titleBar)

        insertLabel = TextView(context).apply {
            text = "▶ 未选中步骤（添加到末尾）"
            setTextColor(Color.parseColor("#F39C12"))
            textSize = sp(R.dimen.editor_step_text_size)
            setPadding(dp(R.dimen.spacing_xs), dp(R.dimen.editor_insert_top), 0, dp(R.dimen.spacing_xs))
        }
        stepPanel.addView(insertLabel!!)

        stepScrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        stepContainer = LinearLayout(context).apply { orientation = VERTICAL }
        stepScrollView.addView(stepContainer)
        stepPanel.addView(stepScrollView)

        val bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(R.dimen.spacing_sm), 0, 0)
        }
        val btnSave = Button(context).apply {
            text = "保存"
            textSize = sp(R.dimen.editor_cmd_text_size)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { listener?.onSave() }
        }
        val btnRun = Button(context).apply {
            text = "运行"
            textSize = sp(R.dimen.editor_cmd_text_size)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { listener?.onRun() }
        }
        bottomBar.addView(btnSave)
        bottomBar.addView(btnRun)
        stepPanel.addView(bottomBar)

        addView(cmdPanel)
        addView(stepPanel)
    }

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
            textSize = sp(R.dimen.editor_cmd_text_size)
            gravity = Gravity.CENTER
            setPadding(dp(R.dimen.editor_cmd_padding_h), dp(R.dimen.editor_cmd_padding_v), dp(R.dimen.editor_cmd_padding_h), dp(R.dimen.editor_cmd_padding_v))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color))
                cornerRadius = dp(R.dimen.editor_cmd_radius).toFloat()
                alpha = 200
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(R.dimen.editor_cmd_margin)
            }
            setOnClickListener {
                listener?.onAddCommand(type, selectedPath)
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
                textSize = sp(R.dimen.editor_cmd_text_size)
                setPadding(dp(R.dimen.spacing_lg), dp(R.dimen.editor_empty_padding), dp(R.dimen.spacing_lg), dp(R.dimen.editor_empty_padding))
                gravity = Gravity.CENTER
            }
            stepContainer.addView(empty)
            return
        }
        renderSteps(steps, emptyList(), 0)
        updateInsertLabel()
    }

    /**
     * 递归渲染一组步骤
     * 块类型（LOOP/IF）有展开/收缩功能
     */
    private fun renderSteps(steps: MutableList<ScriptStep>, parentPath: List<Int>, depth: Int) {
        steps.forEachIndexed { index, step ->
            val currentPath = parentPath + index
            val pathKey = currentPath.joinToString(",")
            val isSelected = currentPath == selectedPath

            if (step.type == StepType.LOOP || step.type == StepType.IF) {
                // 块开始行
                stepContainer.addView(createBlockStartRow(index, step, currentPath, depth, isSelected))
                // 展开时显示子步骤
                if (expandedPaths.contains(pathKey)) {
                    val childSteps = when (step.type) {
                        StepType.IF -> {
                            @Suppress("SENSELESS_COMPARISON")
                            if (step.ifSteps == null) step.ifSteps = mutableListOf()
                            step.ifSteps
                        }
                        StepType.LOOP -> {
                            @Suppress("SENSELESS_COMPARISON")
                            if (step.loopSteps == null) step.loopSteps = mutableListOf()
                            step.loopSteps
                        }
                        else -> mutableListOf()
                    }
                    if (childSteps.isNotEmpty()) {
                        renderSteps(childSteps, currentPath, depth + 1)
                    }
                    // 结束标记
                    val endText = if (step.type == StepType.IF) "条件结束" else "循环结束"
                    stepContainer.addView(createBlockEndRow(endText, depth))
                }
            } else {
                // 普通步骤
                stepContainer.addView(createStepRow(index, step, currentPath, depth, isSelected))
            }
        }
    }

    /**
     * 创建块开始行
     * [+/-] 循环开始 3次    编  删
     * +/- = 展开/收缩
     */
    private fun createBlockStartRow(index: Int, step: ScriptStep, path: List<Int>, depth: Int, isSelected: Boolean): View {
        val pathKey = path.joinToString(",")
        val isExpanded = expandedPaths.contains(pathKey)

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(R.dimen.editor_step_padding_h), dp(R.dimen.editor_step_padding_v), dp(R.dimen.editor_step_padding_h), dp(R.dimen.editor_step_padding_v))
            background = GradientDrawable().apply {
                setColor(if (isSelected) Color.parseColor("#3A5A3A") else if (step.type == StepType.LOOP) Color.parseColor("#2A9B59B6") else Color.parseColor("#2A3498DB"))
                cornerRadius = dp(R.dimen.editor_step_radius).toFloat()
                if (isSelected) setStroke(2, Color.parseColor("#2ECC71"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(R.dimen.editor_step_row_margin)
                leftMargin = depth * dp(R.dimen.editor_indent)
            }
        }

        // +/- 展开收缩按钮
        val btnToggle = TextView(context).apply {
            text = if (isExpanded) "−" else "+"
            setTextColor(Color.parseColor("#F39C12"))
            textSize = sp(R.dimen.editor_step_text_size)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(R.dimen.spacing_xs), 0, dp(R.dimen.spacing_xs), 0)
            setOnClickListener {
                if (expandedPaths.contains(pathKey)) {
                    expandedPaths.remove(pathKey)
                } else {
                    expandedPaths.add(pathKey)
                }
                refreshStepList()
            }
        }
        row.addView(btnToggle)

        // 标签
        val tvLabel = TextView(context).apply {
            text = if (step.type == StepType.LOOP) "循环开始" else "条件开始"
            setTextColor(if (step.type == StepType.LOOP) Color.parseColor("#AF7AC5") else Color.parseColor("#5DADE2"))
            textSize = sp(R.dimen.editor_step_text_size)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(R.dimen.spacing_xs), 0, dp(R.dimen.spacing_xs), 0)
        }
        row.addView(tvLabel)

        // 描述
        val tvDesc = TextView(context).apply {
            text = step.getDescription()
            setTextColor(Color.parseColor("#999999"))
            textSize = sp(R.dimen.editor_step_desc_size)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tvDesc)

        // 编辑/删除
        val btnEdit = TextView(context).apply {
            text = "编"
            setTextColor(Color.parseColor("#3498DB"))
            textSize = sp(R.dimen.editor_step_desc_size)
            setPadding(dp(R.dimen.editor_step_padding_h), 0, dp(R.dimen.editor_step_padding_h), 0)
            setOnClickListener { listener?.onEditStep(path, step) }
        }
        val btnDel = TextView(context).apply {
            text = "删"
            setTextColor(Color.parseColor("#E74C3C"))
            textSize = sp(R.dimen.editor_step_desc_size)
            setPadding(dp(R.dimen.editor_step_padding_h), 0, dp(R.dimen.editor_step_padding_h), 0)
            setOnClickListener { listener?.onDeleteStep(path) }
        }
        row.addView(btnEdit)
        row.addView(btnDel)

        // 点击行体 = 选中
        row.setOnClickListener {
            selectedPath = if (isSelected) null else path
            refreshStepList()
        }
        return row
    }

    /**
     * 创建块结束行
     * ── 循环结束 ──
     */
    private fun createBlockEndRow(label: String, depth: Int): View {
        return TextView(context).apply {
            text = "── $label ──"
            setTextColor(Color.parseColor("#666666"))
            textSize = sp(R.dimen.editor_step_desc_size)
            gravity = Gravity.CENTER
            setPadding(0, dp(R.dimen.spacing_xs), 0, dp(R.dimen.spacing_xs))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = depth * dp(R.dimen.editor_indent)
                bottomMargin = dp(R.dimen.editor_step_row_margin)
            }
        }
    }

    /**
     * 创建普通步骤行
     */
    private fun createStepRow(index: Int, step: ScriptStep, path: List<Int>, depth: Int, isSelected: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(R.dimen.editor_step_padding_h), dp(R.dimen.editor_step_padding_v), dp(R.dimen.editor_step_padding_h), dp(R.dimen.editor_step_padding_v))
            background = GradientDrawable().apply {
                setColor(if (isSelected) Color.parseColor("#3A5A3A") else if (depth > 0) Color.parseColor("#252525") else Color.parseColor("#2A2A2A"))
                cornerRadius = dp(R.dimen.editor_step_radius).toFloat()
                if (isSelected) setStroke(2, Color.parseColor("#2ECC71"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(R.dimen.editor_step_row_margin)
                leftMargin = depth * dp(R.dimen.editor_indent)
            }
        }

        // 占位（与块行的 +/- 对齐）
        val spacer = TextView(context).apply {
            text = "  "
            textSize = sp(R.dimen.editor_step_text_size)
            setPadding(dp(R.dimen.spacing_xs), 0, dp(R.dimen.spacing_xs), 0)
        }
        row.addView(spacer)

        // 序号
        val prefix = if (depth > 0) "└ " else ""
        val num = TextView(context).apply {
            text = "$prefix${index + 1}"
            setTextColor(Color.parseColor("#666666"))
            textSize = sp(R.dimen.editor_step_desc_size)
            setPadding(dp(R.dimen.spacing_xs), 0, dp(R.dimen.spacing_xs), 0)
        }
        row.addView(num)

        // 步骤信息
        val infoLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvType = TextView(context).apply {
            text = step.type.displayName
            setTextColor(Color.WHITE)
            textSize = sp(R.dimen.editor_step_text_size)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val tvDesc = TextView(context).apply {
            text = step.getDescription()
            setTextColor(Color.parseColor("#999999"))
            textSize = sp(R.dimen.editor_step_desc_size)
        }
        infoLayout.addView(tvType)
        infoLayout.addView(tvDesc)
        row.addView(infoLayout)

        // 编辑/删除
        val btnEdit = TextView(context).apply {
            text = "编"
            setTextColor(Color.parseColor("#3498DB"))
            textSize = sp(R.dimen.editor_step_desc_size)
            setPadding(dp(R.dimen.editor_step_padding_h), 0, dp(R.dimen.editor_step_padding_h), 0)
            setOnClickListener { listener?.onEditStep(path, step) }
        }
        val btnDel = TextView(context).apply {
            text = "删"
            setTextColor(Color.parseColor("#E74C3C"))
            textSize = sp(R.dimen.editor_step_desc_size)
            setPadding(dp(R.dimen.editor_step_padding_h), 0, dp(R.dimen.editor_step_padding_h), 0)
            setOnClickListener { listener?.onDeleteStep(path) }
        }
        row.addView(btnEdit)
        row.addView(btnDel)

        // 点击行体 = 选中
        row.setOnClickListener {
            selectedPath = if (isSelected) null else path
            refreshStepList()
        }
        return row
    }

    private fun updateInsertLabel() {
        val sp = selectedPath
        val text = if (sp == null) {
            "▶ 未选中步骤（添加到末尾）"
        } else {
            val pos = sp.joinToString("→") { (it + 1).toString() }
            "▶ 选中：第 $pos 条（点命令选择之前/之后插入）"
        }
        insertLabel?.text = text
    }

    /**
     * 清除选中
     */
    fun clearSelection() {
        selectedPath = null
        updateInsertLabel()
    }
}
