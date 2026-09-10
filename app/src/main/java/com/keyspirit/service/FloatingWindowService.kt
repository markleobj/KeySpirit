package com.keyspirit.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.keyspirit.KeySpiritApp
import com.keyspirit.R
import com.keyspirit.floating.FloatingBallView
import com.keyspirit.floating.FloatingPanelView
import com.keyspirit.record.TouchRecorder
import com.keyspirit.script.Script
import com.keyspirit.script.ScriptExecutor
import com.keyspirit.script.ScriptManager

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindow"
        private const val NOTIFICATION_ID = 1001

        var instance: FloatingWindowService? = null
            private set

        fun isRunning(): Boolean = instance != null

        const val ACTION_SHOW = "com.keyspirit.SHOW"
        const val ACTION_HIDE = "com.keyspirit.HIDE"
        const val ACTION_EXECUTE_SCRIPT = "com.keyspirit.EXECUTE_SCRIPT"
        const val EXTRA_SCRIPT_ID = "script_id"
    }

    private lateinit var windowManager: WindowManager
    private var floatingBall: FloatingBallView? = null
    private var floatingPanel: FloatingPanelView? = null
    private var recordingOverlay: View? = null

    private var isPanelVisible = false
    private var isRecording = false
    private var isExecuting = false

    private val touchRecorder = TouchRecorder()
    private var scriptExecutor: ScriptExecutor? = null
    private var currentScript: Script? = null
    private lateinit var scriptManager: ScriptManager

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scriptManager = KeySpiritApp.instance.scriptManager
        startForeground(NOTIFICATION_ID, createNotification())
        showFloatingBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXECUTE_SCRIPT -> {
                val scriptId = intent.getStringExtra(EXTRA_SCRIPT_ID) ?: return START_NOT_STICKY
                val script = scriptManager.getScript(scriptId) ?: return START_NOT_STICKY
                startExecution(script)
            }
            ACTION_HIDE -> {
                hideAll()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, KeySpiritApp.CHANNEL_FLOATING)
            .setContentTitle("按键精灵")
            .setContentText("悬浮窗服务运行中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    // ============ 悬浮球 ============

    private fun showFloatingBall() {
        if (floatingBall != null) return
        floatingBall = FloatingBallView(this).apply {
            iconText = "▶"
            onTap = { togglePanel() }
            onDrag = { x, y -> updateBallPosition(x, y) }
        }
        val params = createOverlayParams(120, 120).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        windowManager.addView(floatingBall, params)
    }

    private fun updateBallPosition(x: Int, y: Int) {
        val ball = floatingBall ?: return
        val params = ball.layoutParams as WindowManager.LayoutParams
        params.x = x
        params.y = y
        windowManager.updateViewLayout(ball, params)
    }

    private fun togglePanel() {
        if (isPanelVisible) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        if (floatingPanel != null) return
        val ballParams = floatingBall?.layoutParams as? WindowManager.LayoutParams ?: return
        floatingPanel = FloatingPanelView(this).apply {
            when {
                isRecording -> setMode(FloatingPanelView.PanelMode.RECORDING)
                isExecuting -> setMode(FloatingPanelView.PanelMode.EXECUTING)
                else -> setMode(FloatingPanelView.PanelMode.HOME)
            }
            onRecord = { startRecording() }
            onStopRecord = { stopRecording() }
            onPickCoordinate = { pickCoordinate() }
            onExecute = { showScriptList() }
            onPause = { pauseExecution() }
            onStopExecute = { stopExecution() }
            onClose = { hidePanel() }
        }
        val params = createOverlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballParams.x
            y = ballParams.y + 130
        }
        windowManager.addView(floatingPanel, params)
        isPanelVisible = true
    }

    private fun hidePanel() {
        floatingPanel?.let {
            windowManager.removeView(it)
        }
        floatingPanel = null
        isPanelVisible = false
    }

    // ============ 录制 ============

    private fun startRecording() {
        if (!AutoAccessibilityService.isRunning()) {
            toast("请先开启无障碍服务")
            return
        }
        isRecording = true
        touchRecorder.startRecording()
        floatingBall?.isRecording = true
        floatingBall?.iconText = "●"
        hidePanel()
        showRecordingOverlay()
        toast("开始录制，操作完成后点击停止")
    }

    private fun stopRecording() {
        isRecording = false
        val steps = touchRecorder.stopRecording()
        floatingBall?.isRecording = false
        floatingBall?.iconText = "▶"
        hideRecordingOverlay()
        hidePanel()

        // 保存为新脚本
        if (steps.isNotEmpty()) {
            val script = Script(
                name = "录制脚本_${System.currentTimeMillis() % 100000}",
                steps = steps.toMutableList()
            )
            scriptManager.saveScript(script)
            toast("录制完成，已保存 ${steps.size} 步")
        } else {
            toast("录制结束，未捕获到操作")
        }
    }

    private fun showRecordingOverlay() {
        recordingOverlay = View(this).apply {
            setBackgroundColor(0x00000000) // 完全透明
            setOnTouchListener { _, event ->
                touchRecorder.onTouchEvent(event)
                // 将事件转发给无障碍服务，让底层 App 响应
                touchRecorder.dispatchToApp(event)
                true
            }
        }
        val params = createOverlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.addView(recordingOverlay, params)
    }

    private fun hideRecordingOverlay() {
        recordingOverlay?.let {
            windowManager.removeView(it)
        }
        recordingOverlay = null
    }

    // ============ 执行 ============

    private fun showScriptList() {
        hidePanel()
        // 发送广播让 MainActivity 显示脚本选择
        val intent = Intent("com.keyspirit.SHOW_SCRIPT_PICKER").apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    fun startExecution(script: Script) {
        if (!AutoAccessibilityService.isRunning()) {
            toast("请先开启无障碍服务")
            return
        }
        isExecuting = true
        currentScript = script
        scriptExecutor = ScriptExecutor(script, object : ScriptExecutor.ExecutionListener {
            override fun onStepStart(index: Int, step: com.keyspirit.script.ScriptStep) {
                updatePanelInfo("步骤 ${index + 1}/${script.stepCount()}")
            }
            override fun onStepComplete(index: Int, step: com.keyspirit.script.ScriptStep) {}
            override fun onLoopUpdate(currentLoop: Int, totalLoops: Int) {
                val total = if (totalLoops <= 0) "∞" else totalLoops.toString()
                updatePanelInfo("循环: $currentLoop/$total")
            }
            override fun onComplete() {
                isExecuting = false
                scriptExecutor = null
                hidePanel()
                floatingBall?.iconText = "▶"
                toast("脚本执行完成")
                scriptManager.markRun(script.id)
            }
            override fun onError(message: String) {
                isExecuting = false
                scriptExecutor = null
                hidePanel()
                floatingBall?.iconText = "▶"
                toast("执行出错: $message")
            }
        })
        floatingBall?.iconText = "⏸"
        showPanel()
        scriptExecutor?.start()
    }

    private fun updatePanelInfo(info: String) {
        handler.post {
            floatingPanel?.setMode(FloatingPanelView.PanelMode.EXECUTING, info)
        }
    }

    private fun pauseExecution() {
        scriptExecutor?.let {
            if (it.isPaused()) {
                it.resume()
                floatingBall?.iconText = "⏸"
            } else {
                it.pause()
                floatingBall?.iconText = "▶"
            }
        }
    }

    private fun stopExecution() {
        scriptExecutor?.stop()
        isExecuting = false
        scriptExecutor = null
        hidePanel()
        floatingBall?.iconText = "▶"
        toast("已停止执行")
    }

    // ============ 取坐标 ============

    private fun pickCoordinate() {
        hidePanel()
        val intent = Intent().apply {
            setClassName("com.keyspirit", "com.keyspirit.ui.CoordinatePickerActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ============ 工具方法 ============

    private fun createOverlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            width, height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun hideAll() {
        hidePanel()
        hideRecordingOverlay()
        floatingBall?.let { windowManager.removeView(it) }
        floatingBall = null
    }

    private fun toast(msg: String) {
        handler.post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        hideAll()
        scriptExecutor?.stop()
        Log.d(TAG, "悬浮窗服务已停止")
    }
}
