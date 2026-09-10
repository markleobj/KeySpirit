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
            StepType.CLICK, StepType.TOUCH_DOWN, StepType.TOUCH_UP -> {
                inputs["x"] = addInput(layout, "X 坐标", step.x.toString())
                inputs["y"] = addInput(layout, "Y 坐标", step.y.toString())
                if (step.type == StepType.TOUCH_DOWN || step.type == StepType.LONG_PRESS) {
                    inputs["duration"] = addInput(layout, "持续时间(ms)", step.duration.toString())
                }
            }
            StepType.SWIPE -> {
                inputs["x1"] = addInput(layout, "起点 X", step.x1.toString())
                inputs["y1"] = addInput(layout, "起点 Y", step.y1.toString())
                inputs["x2"] = addInput(layout, "终点 X", step.x2.toString())
                inputs["y2"] = addInput(layout, "终点 Y", step.y2.toString())
                inputs["duration"] = addInput(layout, "时长(ms)", step.duration.toString())
            }
            StepType.LONG_PRESS -> {
                inputs["x"] = addInput(layout, "X 坐标", step.x.toString())
                inputs["y"] = addInput(layout, "Y 坐标", step.y.toString())
                inputs["duration"] = addInput(layout, "时长(ms)", step.duration.toString())
            }
            StepType.DELAY -> {
                inputs["delay"] = addInput(layout, "延迟(ms)", step.delay.toString())
                inputs["randomDelay"] = addInput(layout, "随机延迟上限(ms)", step.randomDelay.toString())
            }
            StepType.FIND_IMAGE -> {
                inputs["imagePath"] = addInput(layout, "图片路径", step.imagePath)
                inputs["similarity"] = addInput(layout, "相似度(0-1)", step.similarity.toString())
            }
            StepType.FIND_TEXT -> {
                inputs["text"] = addInput(layout, "要查找的文字", step.text)
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
