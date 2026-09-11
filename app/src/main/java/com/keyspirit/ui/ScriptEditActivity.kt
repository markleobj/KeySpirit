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
            val steps = JsEngine.codeToSteps(etCode.text.toString())
            script?.steps = steps.toMutableList()
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // 根据步骤类型显示不同的输入框
        val inputs = mutableMapOf<String, EditText>()
        when (step.type) {
            StepType.CLICK, StepType.TOUCH_DOWN, StepType.TOUCH_UP,
            StepType.LEFT_CLICK_UP, StepType.RIGHT_CLICK_DOWN, StepType.RIGHT_CLICK_UP -> {
                inputs["x"] = addInput(layout, "X 坐标", step.x.toString())
                inputs["y"] = addInput(layout, "Y 坐标", step.y.toString())
                if (step.type == StepType.TOUCH_DOWN || step.type == StepType.LONG_PRESS || step.type == StepType.RIGHT_CLICK_DOWN) {
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
                inputs["loopCount"] = addInput(layout, "循环次数", step.loopCount.toString())
                inputs["loopStartIndex"] = addInput(layout, "起始步骤序号(从0开始)", step.loopStartIndex.toString())
                inputs["loopEndIndex"] = addInput(layout, "结束步骤序号", step.loopEndIndex.toString())
            }
        }

        AlertDialog.Builder(this)
            .setTitle("编辑步骤 - ${step.type.displayName}")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                try {
                    applyStepParams(step, inputs)
                    refreshStepList()
                } catch (e: Exception) {
                    Toast.makeText(this, "参数格式错误", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyStepParams(step: ScriptStep, inputs: Map<String, EditText>) {
        inputs["x"]?.let { step.x = it.text.toString().toInt() }
        inputs["y"]?.let { step.y = it.text.toString().toInt() }
        inputs["x1"]?.let { step.x1 = it.text.toString().toInt() }
        inputs["y1"]?.let { step.y1 = it.text.toString().toInt() }
        inputs["x2"]?.let { step.x2 = it.text.toString().toInt() }
        inputs["y2"]?.let { step.y2 = it.text.toString().toInt() }
        inputs["duration"]?.let { step.duration = it.text.toString().toLong() }
        inputs["delay"]?.let { step.delay = it.text.toString().toLong() }
        inputs["randomDelay"]?.let { step.randomDelay = it.text.toString().toLong() }
        inputs["imagePath"]?.let { step.imagePath = it.text.toString() }
        inputs["similarity"]?.let { step.similarity = it.text.toString().toDouble() }
        inputs["text"]?.let { step.text = it.text.toString() }
        inputs["loopCount"]?.let { step.loopCount = it.text.toString().toInt() }
        inputs["loopStartIndex"]?.let { step.loopStartIndex = it.text.toString().toInt() }
        inputs["loopEndIndex"]?.let { step.loopEndIndex = it.text.toString().toInt() }
        inputs["regionLeft"]?.let { step.regionLeft = it.text.toString().toInt() }
        inputs["regionTop"]?.let { step.regionTop = it.text.toString().toInt() }
        inputs["regionRight"]?.let { step.regionRight = it.text.toString().toInt() }
        inputs["regionBottom"]?.let { step.regionBottom = it.text.toString().toInt() }
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
     */
    private fun addImagePickerButton(parent: LinearLayout, targetInput: EditText) {
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
        script?.let {
            it.name = etName.text.toString().ifEmpty { "未命名脚本" }
            if (!isVisualMode) {
                it.steps = JsEngine.codeToSteps(etCode.text.toString()).toMutableList()
            }
            scriptManager.saveScript(it)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun runScript() {
        saveScript()
        script?.let {
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
