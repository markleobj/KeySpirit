package com.keyspirit.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.keyspirit.KeySpiritApp
import com.keyspirit.R
import com.keyspirit.script.JsEngine
import com.keyspirit.script.Script
import com.keyspirit.script.ScriptManager
import com.keyspirit.script.ScriptStep
import com.keyspirit.script.StepType
import com.keyspirit.service.FloatingWindowService

class ScriptEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCRIPT_ID = "script_id"
        private const val REQUEST_REGION = 1001
    }

    private lateinit var scriptManager: ScriptManager
    private var script: Script? = null
    private var isVisualMode = true

    private lateinit var etName: EditText
    private lateinit var etCode: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: StepAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_edit)
        com.keyspirit.util.TabletLayoutHelper.applyMaxWidth(this)

        scriptManager = KeySpiritApp.instance.scriptManager
        val scriptId = intent.getStringExtra(EXTRA_SCRIPT_ID)
        script = if (scriptId != null) {
            scriptManager.getScript(scriptId)
        } else {
            Script()
        } ?: Script()

        // 设置当前项目，截图会保存到这里
        com.keyspirit.util.CurrentProjectHolder.currentScriptId = script!!.id
        com.keyspirit.util.CurrentProjectHolder.currentScriptName = script!!.name

        etName = findViewById(R.id.etScriptName)
        etCode = findViewById(R.id.etCode)
        recyclerView = findViewById(R.id.stepList)
        recyclerView.layoutManager = LinearLayoutManager(this)

        etName.setText(script?.name ?: "新脚本")

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveScript() }

        findViewById<View>(R.id.tabVisual).setOnClickListener { switchMode(true) }
        findViewById<View>(R.id.tabCode).setOnClickListener { switchMode(false) }

        findViewById<View>(R.id.btnAddStep).setOnClickListener { showAddStepDialog() }
        findViewById<View>(R.id.btnRunScript).setOnClickListener { runScript() }

        refreshStepList()
    }

    private fun switchMode(visual: Boolean) {
        if (visual == isVisualMode) return
        if (visual) {
            // 从代码模式切回图形化：解析代码
            try {
                val steps = JsEngine.codeToSteps(etCode.text.toString())
                script?.steps = steps.toMutableList()
            } catch (e: Exception) {
                Toast.makeText(this, "代码解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
            etCode.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            findViewById<View>(R.id.tabVisual).setBackgroundResource(R.drawable.bg_tab_active)
            (findViewById<View>(R.id.tabVisual) as TextView).setTextColor(getColor(R.color.white))
            findViewById<View>(R.id.tabCode).setBackgroundResource(R.drawable.bg_tab_inactive)
            (findViewById<View>(R.id.tabCode) as TextView).setTextColor(getColor(R.color.gray))
            refreshStepList()
        } else {
            // 从图形化切到代码模式：生成代码
            etCode.setText(JsEngine.stepsToCode(script?.steps ?: emptyList()))
            etCode.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            findViewById<View>(R.id.tabCode).setBackgroundResource(R.drawable.bg_tab_active)
            (findViewById<View>(R.id.tabCode) as TextView).setTextColor(getColor(R.color.white))
            findViewById<View>(R.id.tabVisual).setBackgroundResource(R.drawable.bg_tab_inactive)
            (findViewById<View>(R.id.tabVisual) as TextView).setTextColor(getColor(R.color.gray))
        }
        isVisualMode = visual
    }

    private fun refreshStepList() {
        val steps = script?.steps ?: mutableListOf()
        adapter = StepAdapter(steps, object : StepAdapter.OnStepActionListener {
            override fun onEdit(position: Int) {
                showEditStepDialog(position)
            }
            override fun onDelete(position: Int) {
                script?.steps?.removeAt(position)
                refreshStepList()
            }
        })
        recyclerView.adapter = adapter
    }

    private fun showAddStepDialog() {
        val types = StepType.values()
        val names = types.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择步骤类型")
            .setItems(names) { _, which ->
                val type = types[which]
                val step = ScriptStep(type = type)
                script?.steps?.add(step)
                refreshStepList()
                // 添加后立即编辑参数
                showEditStepDialog(script!!.steps.size - 1)
            }
            .show()
    }

    private fun showEditStepDialog(position: Int) {
        val step = script?.steps?.getOrNull(position) ?: return
        showEditStepDialog(step) {
            refreshStepList()
        }
    }

    /**
     * 编辑单个步骤的对话框（重载，支持任意 ScriptStep 对象，用于 IF 块内子步骤）
     */
    private fun showEditStepDialog(step: ScriptStep, onSaved: () -> Unit = {}) {
        currentEditingStep = step
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // 根据步骤类型显示不同的输入框
        val inputs = mutableMapOf<String, EditText>()
        when (step.type) {
            StepType.CLICK, StepType.TOUCH_DOWN, StepType.TOUCH_UP,
            StepType.RIGHT_CLICK, StepType.RIGHT_CLICK_DOWN, StepType.RIGHT_CLICK_UP -> {
                inputs["x"] = addInput(layout, "X 坐标", step.x.toString())
                inputs["y"] = addInput(layout, "Y 坐标", step.y.toString())
                if (step.type == StepType.TOUCH_DOWN
                    || step.type == StepType.RIGHT_CLICK_DOWN || step.type == StepType.RIGHT_CLICK
                ) {
                    inputs["duration"] = addInput(layout, "持续时间(ms)", step.duration.toString())
                }
                addCoordinatePickerButton(layout, inputs["x"]!!, inputs["y"]!!)
            }
            StepType.SWIPE -> {
                inputs["x1"] = addInput(layout, "起点 X", step.x1.toString())
                inputs["y1"] = addInput(layout, "起点 Y", step.y1.toString())
                inputs["x2"] = addInput(layout, "终点 X", step.x2.toString())
                inputs["y2"] = addInput(layout, "终点 Y", step.y2.toString())
                inputs["duration"] = addInput(layout, "时长(ms)", step.duration.toString())
                addSwipeCoordinatePickerButtons(layout, inputs["x1"]!!, inputs["y1"]!!, inputs["x2"]!!, inputs["y2"]!!)
            }
            StepType.LONG_PRESS -> {
                inputs["x"] = addInput(layout, "X 坐标", step.x.toString())
                inputs["y"] = addInput(layout, "Y 坐标", step.y.toString())
                inputs["duration"] = addInput(layout, "时长(ms)", step.duration.toString())
                addCoordinatePickerButton(layout, inputs["x"]!!, inputs["y"]!!)
            }
            StepType.SCREENSHOT -> {
                inputs["imageName"] = addInput(layout, "图片名称", step.imageName)
                inputs["imagePath"] = addInput(layout, "图片路径", step.imagePath)
            }
            StepType.DELAY -> {
                inputs["delay"] = addInput(layout, "延迟(ms)", step.delay.toString())
                inputs["randomDelay"] = addInput(layout, "随机延迟上限(ms)", step.randomDelay.toString())
            }
            StepType.FIND_IMAGE -> {
                inputs["imagePath"] = addInput(layout, "图片路径", step.imagePath)
                inputs["similarity"] = addInput(layout, "相似度(0-1)", step.similarity.toString())
                // 添加从项目目录选图的按钮
                addImagePickerButton(layout, inputs["imagePath"]!!)
                addRegionInputs(layout, step, inputs)
            }
            StepType.FIND_TEXT -> {
                inputs["text"] = addInput(layout, "要查找的文字", step.text)
                addRegionInputs(layout, step, inputs)
            }
            StepType.LOOP -> {
                inputs["loopCount"] = addInput(layout, "循环次数（0=无限）", step.loopCount.toString())
                inputs["loopStartIndex"] = addInput(layout, "起始步骤序号(从1开始)", (step.loopStartIndex + 1).toString())
                inputs["loopEndIndex"] = addInput(layout, "结束步骤序号", (step.loopEndIndex + 1).toString())
            }
            StepType.IF -> {
                // 条件类型选择
                val conditionOptions = arrayOf("找到图片", "找到文字", "找不到图片", "找不到文字")
                val tvCond = TextView(this).apply {
                    text = "条件类型"
                    setTextColor(getColor(R.color.gray))
                    textSize = 13f
                    setPadding(0, 16, 0, 4)
                }
                layout.addView(tvCond)
                val spinner = android.widget.Spinner(this).apply {
                    adapter = android.widget.ArrayAdapter(
                        this@ScriptEditActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        conditionOptions
                    )
                    setSelection(step.conditionType)
                }
                layout.addView(spinner)
                // 存引用用于读取
                conditionSpinnerRef = spinner

                // 找图相关字段
                val tvImgPath = addInput(layout, "目标图片路径", step.conditionImagePath)
                inputs["conditionImagePath"] = tvImgPath
                val btnPickImg = addConditionImagePickerButton(layout, tvImgPath)
                val tvSim = addInput(layout, "相似度(0-1)", step.conditionSimilarity.toString())
                inputs["conditionSimilarity"] = tvSim

                // 找文字相关字段
                val tvText = addInput(layout, "目标文字", step.conditionText)
                inputs["conditionText"] = tvText

                // 共用字段
                inputs["conditionTimeout"] = addInput(layout, "超时时间(ms)", step.conditionTimeout.toString())

                // 块内步骤管理
                val tvBlockTitle = TextView(this).apply {
                    text = "条件成立时执行的步骤"
                    setTextColor(getColor(R.color.gray))
                    textSize = 13f
                    setPadding(0, 16, 0, 4)
                }
                layout.addView(tvBlockTitle)
                val btnManageBlock = android.widget.Button(this).apply {
                    text = "管理块内步骤（${step.ifSteps.size}个）"
                    setOnClickListener {
                        showIfBlockEditor(step) {
                            text = "管理块内步骤（${step.ifSteps.size}个）"
                        }
                    }
                }
                layout.addView(btnManageBlock)
                val tvBlockHint = TextView(this).apply {
                    text = "条件成立时，按顺序执行块内的所有步骤；不成立则全部跳过"
                    setTextColor(getColor(R.color.gray))
                    textSize = 12f
                    setPadding(0, 8, 0, 0)
                }
                layout.addView(tvBlockHint)

                // 找图相关View集合（用于动态显隐）
                val imageViews = listOf(tvImgPath, btnPickImg, tvSim)
                // 找文字相关View集合
                val textViews = listOf(tvText)

                fun updateIfVisibility(condType: Int) {
                    val isImage = condType == 0 || condType == 2
                    val isText = condType == 1 || condType == 3
                    imageViews.forEach { it.visibility = if (isImage) View.VISIBLE else View.GONE }
                    textViews.forEach { it.visibility = if (isText) View.VISIBLE else View.GONE }
                }

                spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        updateIfVisibility(position)
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
                // 初始化可见性
                updateIfVisibility(step.conditionType)
            }
            StepType.MOVE_MOUSE, StepType.PICK_POINT -> {
                inputs["x"] = addInput(layout, "X 坐标", step.x.toString())
                inputs["y"] = addInput(layout, "Y 坐标", step.y.toString())
                addCoordinatePickerButton(layout, inputs["x"]!!, inputs["y"]!!)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("编辑步骤 - ${step.type.displayName}")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                try {
                    applyStepParams(step, inputs)
                    onSaved()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, "参数格式错误: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "保存步骤失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyStepParams(step: ScriptStep, inputs: Map<String, EditText>) {
        val errors = mutableListOf<String>()

        fun safeInt(key: String, default: Int = 0): Int {
            return inputs[key]?.text?.toString()?.let {
                it.toIntOrNull() ?: run {
                    errors.add("$key: \"$it\" 不是有效数字")
                    default
                }
            } ?: default
        }
        fun safeLong(key: String, default: Long = 0): Long {
            return inputs[key]?.text?.toString()?.let {
                it.toLongOrNull() ?: run {
                    errors.add("$key: \"$it\" 不是有效数字")
                    default
                }
            } ?: default
        }
        fun safeDouble(key: String, default: Double = 0.0): Double {
            return inputs[key]?.text?.toString()?.let {
                it.toDoubleOrNull() ?: run {
                    errors.add("$key: \"$it\" 不是有效小数")
                    default
                }
            } ?: default
        }
        fun safeString(key: String): String {
            return inputs[key]?.text?.toString() ?: ""
        }

        step.x = safeInt("x")
        step.y = safeInt("y")
        step.x1 = safeInt("x1")
        step.y1 = safeInt("y1")
        step.x2 = safeInt("x2")
        step.y2 = safeInt("y2")
        step.duration = safeLong("duration", 300)
        step.delay = safeLong("delay", 500)
        step.randomDelay = safeLong("randomDelay")
        step.imagePath = safeString("imagePath")
        step.imageName = safeString("imageName")
        step.similarity = safeDouble("similarity", 0.9)
        step.text = safeString("text")
        step.loopCount = safeInt("loopCount", 1)
        step.loopStartIndex = (safeInt("loopStartIndex", 1) - 1).coerceAtLeast(0)
        step.loopEndIndex = (safeInt("loopEndIndex", 1) - 1).coerceAtLeast(0)
        // IF 条件判断
        conditionSpinnerRef?.let { spinner ->
            step.conditionType = spinner.selectedItemPosition
        }
        step.conditionImagePath = safeString("conditionImagePath")
        step.conditionSimilarity = safeDouble("conditionSimilarity", 0.9)
        step.conditionText = safeString("conditionText")
        step.conditionTimeout = safeLong("conditionTimeout", 1000)
        // ifSteps 直接在 showIfBlockEditor 中修改 step 对象，这里不需要再处理
        step.regionLeft = safeInt("regionLeft")
        step.regionTop = safeInt("regionTop")
        step.regionRight = safeInt("regionRight")
        step.regionBottom = safeInt("regionBottom")

        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.joinToString("; "))
        }
    }

    private fun addInput(parent: LinearLayout, label: String, value: String): EditText {
        val tv = TextView(this).apply {
            text = label
            setPadding(0, 16, 0, 4)
            setTextColor(getColor(R.color.gray))
            textSize = 13f
        }
        parent.addView(tv)
        val et = EditText(this).apply {
            setText(value)
            setSingleLine()
        }
        parent.addView(et)
        return et
    }

    /**
     * 添加"从项目目录选择图片"按钮，点击后列出当前项目截图目录的所有图片
     * @return 创建的按钮 View
     */
    private fun addImagePickerButton(parent: LinearLayout, targetInput: EditText): android.widget.Button {
        val btn = android.widget.Button(this).apply {
            text = "从项目目录选择图片"
            setOnClickListener {
                val scriptId = script?.id ?: return@setOnClickListener
                val screenshots = com.keyspirit.util.ScreenshotUtils.listProjectScreenshots(this@ScriptEditActivity, scriptId)
                if (screenshots.isEmpty()) {
                    android.widget.Toast.makeText(this@ScriptEditActivity, "项目目录下还没有截图，请先用悬浮窗截图", android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val fileNames = screenshots.map { it.name }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this@ScriptEditActivity)
                    .setTitle("选择图片")
                    .setItems(fileNames) { _, which ->
                        val selectedFile = screenshots[which]
                        targetInput.setText(selectedFile.absolutePath)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        parent.addView(btn)
        return btn
    }

    /**
     * 条件判断的选图按钮
     * @return 创建的按钮 View
     */
    private fun addConditionImagePickerButton(parent: LinearLayout, targetInput: EditText): android.widget.Button {
        val btn = android.widget.Button(this).apply {
            text = "从项目目录选择图片"
            setOnClickListener {
                val scriptId = script?.id ?: return@setOnClickListener
                val screenshots = com.keyspirit.util.ScreenshotUtils.listProjectScreenshots(this@ScriptEditActivity, scriptId)
                if (screenshots.isEmpty()) {
                    android.widget.Toast.makeText(this@ScriptEditActivity, "项目目录下还没有截图，请先用悬浮窗截图", android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val fileNames = screenshots.map { it.name }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this@ScriptEditActivity)
                    .setTitle("选择图片")
                    .setItems(fileNames) { _, which ->
                        val selectedFile = screenshots[which]
                        targetInput.setText(selectedFile.absolutePath)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        parent.addView(btn)
        return btn
    }

    /**
     * IF 块内步骤编辑器
     */
    private fun showIfBlockEditor(step: ScriptStep, onChanged: () -> Unit) {
        val ifSteps = step.ifSteps
        val dialogLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val tvCount = android.widget.TextView(this).apply {
            text = "共 ${ifSteps.size} 个步骤（点击可编辑，长按可删除）"
            setTextColor(getColor(R.color.gray))
            textSize = 12f
            setPadding(8, 0, 8, 8)
        }
        dialogLayout.addView(tvCount)

        val listView = android.widget.ListView(this)
        val adapter = object : android.widget.ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            ifSteps.mapIndexed { i, s -> "${i + 1}. ${s.getDescription()}" }
        ) {}
        listView.adapter = adapter
        dialogLayout.addView(listView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            600
        ))

        // 点击编辑
        listView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
            val subStep = ifSteps[position]
            showEditStepDialog(subStep) {
                adapter.clear()
                adapter.addAll(ifSteps.mapIndexed { i, s -> "${i + 1}. ${s.getDescription()}" })
                tvCount.text = "共 ${ifSteps.size} 个步骤（点击可编辑，长按可删除）"
                onChanged()
            }
        }

        // 长按删除
        listView.onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, _, position, _ ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("删除步骤")
                .setMessage("确定删除第 ${position + 1} 步吗？")
                .setPositiveButton("删除") { _, _ ->
                    ifSteps.removeAt(position)
                    adapter.clear()
                    adapter.addAll(ifSteps.mapIndexed { i, s -> "${i + 1}. ${s.getDescription()}" })
                    tvCount.text = "共 ${ifSteps.size} 个步骤（点击可编辑，长按可删除）"
                    onChanged()
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        // 添加步骤按钮
        val btnAdd = android.widget.Button(this).apply {
            text = "+ 添加步骤"
            setOnClickListener {
                val types = arrayOf(
                    "点击", "长按", "滑动", "延迟", "找图点击", "找文字点击"
                )
                val typeMap = arrayOf(
                    StepType.CLICK, StepType.LONG_PRESS, StepType.SWIPE,
                    StepType.DELAY, StepType.FIND_IMAGE, StepType.FIND_TEXT
                )
                androidx.appcompat.app.AlertDialog.Builder(this@ScriptEditActivity)
                    .setTitle("选择步骤类型")
                    .setItems(types) { _, which ->
                        val type = typeMap[which]
                        val newStep = ScriptStep(type = type)
                        ifSteps.add(newStep)
                        adapter.clear()
                        adapter.addAll(ifSteps.mapIndexed { i, s -> "${i + 1}. ${s.getDescription()}" })
                        tvCount.text = "共 ${ifSteps.size} 个步骤（点击可编辑，长按可删除）"
                        onChanged()
                        // 自动弹出编辑
                        showEditStepDialog(newStep) {
                            adapter.clear()
                            adapter.addAll(ifSteps.mapIndexed { i, s -> "${i + 1}. ${s.getDescription()}" })
                            onChanged()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        dialogLayout.addView(btnAdd)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("条件块内步骤")
            .setView(dialogLayout)
            .setPositiveButton("完成", null)
            .show()
    }

    /**
     * 坐标选取按钮：通过悬浮窗在任意 App 上选取坐标
     */
    private fun addCoordinatePickerButton(parent: LinearLayout, xInput: EditText, yInput: EditText) {
        val btn = android.widget.Button(this).apply {
            text = "📍 选取坐标"
            setOnClickListener {
                currentEditingStep?.let { pendingCoordinateStep = it }
                pendingXInput = xInput
                pendingYInput = yInput
                startCoordinatePicker()
            }
        }
        parent.addView(btn)
    }

    /**
     * 滑动坐标选取按钮：选取起点和终点
     */
    private fun addSwipeCoordinatePickerButtons(parent: LinearLayout, x1Input: EditText, y1Input: EditText, x2Input: EditText, y2Input: EditText) {
        val btn1 = android.widget.Button(this).apply {
            text = "📍 选取起点"
            setOnClickListener {
                currentEditingStep?.let { pendingCoordinateStep = it }
                pendingXInput = x1Input
                pendingYInput = y1Input
                startCoordinatePicker()
            }
        }
        parent.addView(btn1)

        val btn2 = android.widget.Button(this).apply {
            text = "📍 选取终点"
            setOnClickListener {
                currentEditingStep?.let { pendingCoordinateStep = it }
                pendingXInput = x2Input
                pendingYInput = y2Input
                startCoordinatePicker()
            }
        }
        parent.addView(btn2)
    }

    private var pendingCoordinateStep: ScriptStep? = null
    private var pendingXInput: EditText? = null
    private var pendingYInput: EditText? = null
    private var pendingRegionStep: ScriptStep? = null
    private var pendingRegionInputs: MutableMap<String, EditText>? = null

    /**
     * 启动悬浮窗坐标选取，把 App 退到后台，用户可在任意 App 上点击
     */
    private fun startCoordinatePicker() {
        com.keyspirit.util.CoordinateResultHolder.hasResult = false
        // 启动悬浮窗服务并触发坐标选取
        val intent = Intent(this, com.keyspirit.service.FloatingWindowService::class.java).apply {
            action = com.keyspirit.service.FloatingWindowService.ACTION_PICK_COORDINATE
            putExtra("fromEditor", true)
        }
        startService(intent)
        // 退到后台，让用户看到目标 App
        moveTaskToBack(true)
        Toast.makeText(this, "请在目标 App 上点击选取坐标", Toast.LENGTH_SHORT).show()
    }

    private var currentEditingStep: ScriptStep? = null
    private var conditionSpinnerRef: android.widget.Spinner? = null

    private fun addRegionInputs(parent: LinearLayout, step: ScriptStep, inputs: MutableMap<String, EditText>) {
        // 限定区域开关
        val checkTv = TextView(this).apply {
            text = "限定查找区域"
            setPadding(0, 16, 0, 4)
            setTextColor(getColor(R.color.gray))
            textSize = 13f
        }
        parent.addView(checkTv)

        val switch = android.widget.Switch(this).apply {
            isChecked = step.useRegion
            setOnCheckedChangeListener { _, checked ->
                step.useRegion = checked
            }
        }
        parent.addView(switch)

        // 区域坐标输入
        inputs["regionLeft"] = addInput(parent, "区域左 X", step.regionLeft.toString())
        inputs["regionTop"] = addInput(parent, "区域上 Y", step.regionTop.toString())
        inputs["regionRight"] = addInput(parent, "区域右 X", step.regionRight.toString())
        inputs["regionBottom"] = addInput(parent, "区域下 Y", step.regionBottom.toString())

        // 选取区域按钮
        val pickBtn = TextView(this).apply {
            text = "📐 框选屏幕区域"
            setTextColor(getColor(R.color.white))
            setBackgroundColor(getColor(R.color.primary))
            setPadding(24, 16, 24, 16)
            textSize = 14f
            setOnClickListener {
                currentEditingStep = step
                pendingRegionStep = step
                pendingRegionInputs = inputs
                // 通过悬浮窗选取区域，不跳转 Activity
                com.keyspirit.util.RegionResultHolder.hasNewResult = false
                val intent = Intent(this@ScriptEditActivity, com.keyspirit.service.FloatingWindowService::class.java).apply {
                    action = com.keyspirit.service.FloatingWindowService.ACTION_PICK_REGION
                    putExtra("fromEditor", true)
                }
                startService(intent)
                moveTaskToBack(true)
                Toast.makeText(this@ScriptEditActivity, "请在目标 App 上拖动框选区域", Toast.LENGTH_SHORT).show()
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 }
        parent.addView(pickBtn, params)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        // 检查坐标选取结果
        val coord = com.keyspirit.util.CoordinateResultHolder.consume()
        if (coord != null && pendingXInput != null && pendingYInput != null) {
            pendingXInput?.setText(coord.first.toString())
            pendingYInput?.setText(coord.second.toString())
            Toast.makeText(this, "坐标已设置: (${coord.first}, ${coord.second})", Toast.LENGTH_SHORT).show()
            pendingXInput = null
            pendingYInput = null
            pendingCoordinateStep = null
        }
        // 检查区域选取结果
        val region = com.keyspirit.util.RegionResultHolder.consumeRegion()
        if (region != null && pendingRegionInputs != null) {
            pendingRegionInputs?.get("regionLeft")?.setText(region[0].toString())
            pendingRegionInputs?.get("regionTop")?.setText(region[1].toString())
            pendingRegionInputs?.get("regionRight")?.setText(region[2].toString())
            pendingRegionInputs?.get("regionBottom")?.setText(region[3].toString())
            pendingRegionStep?.useRegion = true
            Toast.makeText(this, "区域已设置: (${region[0]},${region[1]})-(${region[2]},${region[3]})", Toast.LENGTH_SHORT).show()
            pendingRegionInputs = null
            pendingRegionStep = null
        }
    }

    private fun saveScript() {
        saveScriptInternal()
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * 保存脚本但不 finish Activity，供 runScript 等场景使用
     */
    private fun saveScriptInternal() {
        script?.let {
            it.name = etName.text.toString().ifEmpty { "未命名脚本" }
            if (!isVisualMode) {
                it.steps = JsEngine.codeToSteps(etCode.text.toString()).toMutableList()
            }
            scriptManager.saveScript(it)
        }
    }

    private fun runScript() {
        // 检查无障碍服务
        if (!com.keyspirit.service.AutoAccessibilityService.isRunning()) {
            Toast.makeText(this, "请先在设置中开启无障碍服务", Toast.LENGTH_LONG).show()
            return
        }
        // 检查悬浮窗权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
            && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
            return
        }
        saveScriptInternal()
        script?.let {
            // 确保悬浮窗服务已启动
            if (!FloatingWindowService.isRunning()) {
                startService(Intent(this, FloatingWindowService::class.java))
            }
            val intent = Intent(this, FloatingWindowService::class.java).apply {
                action = FloatingWindowService.ACTION_EXECUTE_SCRIPT
                putExtra(FloatingWindowService.EXTRA_SCRIPT_ID, it.id)
            }
            startService(intent)
            // 回到桌面
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            finish()
        }
    }

    // ============ Adapter ============

    class StepAdapter(
        private val steps: List<ScriptStep>,
        private val listener: OnStepActionListener
    ) : RecyclerView.Adapter<StepAdapter.ViewHolder>() {

        interface OnStepActionListener {
            fun onEdit(position: Int)
            fun onDelete(position: Int)
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvNum: TextView = view.findViewById(R.id.tvStepNum)
            val tvTitle: TextView = view.findViewById(R.id.tvStepTitle)
            val tvDesc: TextView = view.findViewById(R.id.tvStepDesc)
            val btnEdit: TextView = view.findViewById(R.id.btnStepEdit)
            val btnDelete: TextView = view.findViewById(R.id.btnStepDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_step, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val step = steps[position]
            holder.tvNum.text = (position + 1).toString()
            holder.tvTitle.text = "${step.type.displayName}"
            holder.tvDesc.text = step.getDescription()
            holder.btnEdit.setOnClickListener { listener.onEdit(position) }
            holder.btnDelete.setOnClickListener { listener.onDelete(position) }
        }

        override fun getItemCount(): Int = steps.size
    }
}
