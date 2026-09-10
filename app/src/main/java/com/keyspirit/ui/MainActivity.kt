package com.keyspirit.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.keyspirit.KeySpiritApp
import com.keyspirit.R
import com.keyspirit.script.Script
import com.keyspirit.script.ScriptManager
import com.keyspirit.service.FloatingWindowService
import com.keyspirit.service.ScreenCaptureService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var scriptManager: ScriptManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ScriptAdapter
    private lateinit var projectionManager: MediaProjectionManager

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scriptManager = KeySpiritApp.instance.scriptManager
        recyclerView = findViewById(R.id.scriptList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.btnNewScript).setOnClickListener {
            val script = Script(name = "新脚本")
            scriptManager.saveScript(script)
            openEditor(script.id)
        }

        findViewById<View>(R.id.btnStartRecord).setOnClickListener {
            startFloatingService()
        }

        // 初始化截屏权限（用于找图/找文字/截图）
        if (!ScreenCaptureService.isRunning()) {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
        } catch (e: Exception) {
            Toast.makeText(this, "无法请求截屏权限", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                // 启动截屏服务
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, data)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "截屏服务已启动", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "未授权截屏，找图/找文字/截图功能不可用", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshScripts()
    }

    private fun refreshScripts() {
        val scripts = scriptManager.getAllScripts().sortedByDescending { it.updatedAt }
        adapter = ScriptAdapter(scripts, object : ScriptAdapter.OnItemClickListener {
            override fun onPlay(script: Script) {
                runScript(script)
            }
            override fun onEdit(script: Script) {
                openEditor(script.id)
            }
            override fun onDelete(script: Script) {
                scriptManager.deleteScript(script.id)
                refreshScripts()
            }
        })
        recyclerView.adapter = adapter
    }

    private fun openEditor(scriptId: String) {
        val intent = Intent(this, ScriptEditActivity::class.java).apply {
            putExtra(ScriptEditActivity.EXTRA_SCRIPT_ID, scriptId)
        }
        startActivity(intent)
    }

    private fun runScript(script: Script) {
        startFloatingService()
        // 通知悬浮窗服务执行脚本
        val intent = Intent(this, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_EXECUTE_SCRIPT
            putExtra(FloatingWindowService.EXTRA_SCRIPT_ID, script.id)
        }
        startService(intent)
    }

    private fun startFloatingService() {
        if (!FloatingWindowService.isRunning()) {
            val intent = Intent(this, FloatingWindowService::class.java)
            startService(intent)
        }
        // 回到桌面，让用户看到悬浮窗
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    class ScriptAdapter(
        private val scripts: List<Script>,
        private val listener: OnItemClickListener
    ) : RecyclerView.Adapter<ScriptAdapter.ViewHolder>() {

        interface OnItemClickListener {
            fun onPlay(script: Script)
            fun onEdit(script: Script)
            fun onDelete(script: Script)
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvScriptName)
            val tvInfo: TextView = view.findViewById(R.id.tvScriptInfo)
            val btnPlay: TextView = view.findViewById(R.id.btnPlay)
            val btnEdit: TextView = view.findViewById(R.id.btnEdit)
            val btnDelete: TextView = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_script, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val script = scripts[position]
            holder.tvName.text = script.name

            val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val lastRun = if (script.lastRunAt > 0) {
                "最近运行 ${dateFormat.format(Date(script.lastRunAt))}"
            } else {
                "从未运行"
            }
            holder.tvInfo.text = "${script.stepCount()} 步 · $lastRun"

            holder.btnPlay.setOnClickListener { listener.onPlay(script) }
            holder.btnEdit.setOnClickListener { listener.onEdit(script) }
            holder.btnDelete.setOnClickListener { listener.onDelete(script) }
        }

        override fun getItemCount(): Int = scripts.size
    }
}
