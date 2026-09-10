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
        const val ACTION_PICK_COORDINATE = "com.keyspirit.PICK_COORDINATE"
        const val ACTION_PICK_REGION = "com.keyspirit.PICK_REGION"
        const val EXTRA_SCRIPT_ID = "script_id"
    }

    private lateinit var windowManager: WindowManager
    private var floatingBall: FloatingBallView? = null
    private var floatingPanel: FloatingPanelView? = null
    private var recordingOverlay: View? = null
    private var pickerOverlay: View? = null  // 坐标/区域选取的全屏悬浮层

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
        // Android 10+ 需要在 startForeground 中指定前台服务类型
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        showFloatingBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXECUTE_SCRIPT -> {
                val scriptId = intent.getStringExtra(EXTRA_SCRIPT_ID) ?: return START_NOT_STICKY
                val script = scriptManager.getScript(scriptId) ?: return START_NOT_STICKY
                startExecution(script)
            }
            ACTION_PICK_COORDINATE -> {
                pickerFromEditor = intent.getBooleanExtra("fromEditor", false)
                pickCoordinate()
            }
            ACTION_PICK_REGION -> {
                pickerFromEditor = intent.getBooleanExtra("fromEditor", false)
                pickRegion()
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
            onPickRegion = { pickRegion() }
            onScreenshot = { takeScreenshot() }
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
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(recordingOverlay, params)
        // 把悬浮球提到最上层，确保可以点击停止
        floatingBall?.let { ball ->
            windowManager.removeView(ball)
            windowManager.addView(ball, ball.layoutParams)
        }
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
        // 设置当前项目，截图会保存到这个项目目录
        com.keyspirit.util.CurrentProjectHolder.currentScriptId = script.id
        com.keyspirit.util.CurrentProjectHolder.currentScriptName = script.name

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

    // ============ 取坐标（全屏悬浮层，不跳转 Activity） ============

    private fun pickCoordinate() {
        hidePanel()
        showCoordinateOverlay()
    }

    private var pickerFromEditor = false

    private fun showCoordinateOverlay() {
        // 先隐藏悬浮球，避免遮挡
        floatingBall?.visibility = View.GONE

        val overlay = View(this).apply {
            setBackgroundColor(0x33000000) // 半透明黑色
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        // 实时显示坐标
                        val x = event.rawX.toInt()
                        val y = event.rawY.toInt()
                        showPickerToast("X: $x, Y: $y  （松开确认）")
                    }
                    MotionEvent.ACTION_UP -> {
                        val x = event.rawX.toInt()
                        val y = event.rawY.toInt()
                        removePickerOverlay()
                        if (pickerFromEditor) {
                            // 编辑器流程：保存坐标，编辑器 onResume 读取
                            com.keyspirit.util.CoordinateResultHolder.x = x
                            com.keyspirit.util.CoordinateResultHolder.y = y
                            com.keyspirit.util.CoordinateResultHolder.hasResult = true
                            toast("坐标已选取: ($x, $y)")
                        } else {
                            // 悬浮窗流程：显示操作菜单
                            showCoordinateActionMenu(x, y)
                        }
                    }
                }
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params)
        pickerOverlay = overlay
        showPickerToast("点击屏幕任意位置选取坐标")
    }

    // ============ 区域选取（全屏悬浮层，不跳转 Activity） ============

    private fun pickRegion() {
        hidePanel()
        showRegionOverlay()
    }

    private fun showRegionOverlay() {
        floatingBall?.visibility = View.GONE
        com.keyspirit.util.RegionResultHolder.hasNewResult = false

        var startX = 0f
        var startY = 0f
        var regionView: View? = null

        val overlay = View(this).apply {
            setBackgroundColor(0x33000000)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        // 创建虚线边框选区框
                        regionView = View(this@FloatingWindowService).apply {
                            background = createDashedBorder()
                        }
                        val rParams = WindowManager.LayoutParams(
                            0, 0,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT
                        )
                        windowManager.addView(regionView, rParams)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val curX = event.rawX
                        val curY = event.rawY
                        val left = minOf(startX, curX).toInt()
                        val top = minOf(startY, curY).toInt()
                        val width = Math.abs(curX - startX).toInt()
                        val height = Math.abs(curY - startY).toInt()
                        regionView?.let { rv ->
                            val lp = rv.layoutParams as WindowManager.LayoutParams
                            lp.x = left
                            lp.y = top
                            lp.width = width
                            lp.height = height
                            windowManager.updateViewLayout(rv, lp)
                        }
                        showPickerToast("区域: ${width}x${height}")
                    }
                    MotionEvent.ACTION_UP -> {
                        val endX = event.rawX
                        val endY = event.rawY
                        val left = minOf(startX, endX).toInt()
                        val top = minOf(startY, endY).toInt()
                        val right = maxOf(startX, endX).toInt()
                        val bottom = maxOf(startY, endY).toInt()
                        // 移除选区框
                        regionView?.let { windowManager.removeView(it) }
                        removePickerOverlay()
                        if (pickerFromEditor) {
                            // 编辑器流程：保存区域，编辑器 onResume 读取
                            com.keyspirit.util.RegionResultHolder.region = intArrayOf(left, top, right, bottom)
                            com.keyspirit.util.RegionResultHolder.hasNewResult = true
                            toast("区域已选取: [$left,$top,$right,$bottom]")
                        } else {
                            // 悬浮窗流程：显示操作菜单
                            showRegionActionMenu(left, top, right, bottom)
                        }
                    }
                }
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params)
        pickerOverlay = overlay
        showPickerToast("拖动选择区域")
    }

    /**
     * 创建虚线边框 Drawable
     */
    private fun createDashedBorder(): android.graphics.drawable.Drawable {
        val sWidth = 6f
        val dWidth = 20f
        val dGap = 12f
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#00BFFF")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = sWidth
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(dWidth, dGap), 0f)
        }
        return object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: android.graphics.Canvas) {
                val rect = android.graphics.RectF(bounds)
                canvas.drawRect(rect, paint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    /**
     * 区域选取后的操作菜单
     */
    private fun showRegionActionMenu(left: Int, top: Int, right: Int, bottom: Int) {
        val options = arrayOf("在此区域找图", "在此区域找文字", "仅保存区域")
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("选择操作")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        addStepToCurrentScript(com.keyspirit.script.ScriptStep(
                            type = com.keyspirit.script.StepType.FIND_IMAGE,
                            useRegion = true,
                            regionLeft = left, regionTop = top,
                            regionRight = right, regionBottom = bottom
                        ))
                        toast("已添加：在区域内找图")
                    }
                    1 -> {
                        addStepToCurrentScript(com.keyspirit.script.ScriptStep(
                            type = com.keyspirit.script.StepType.FIND_TEXT,
                            useRegion = true,
                            regionLeft = left, regionTop = top,
                            regionRight = right, regionBottom = bottom
                        ))
                        toast("已添加：在区域内找文字")
                    }
                    2 -> {
                        toast("区域已保存: [$left,$top,$right,$bottom]")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    /**
     * 坐标选取后的操作菜单
     */
    private fun showCoordinateActionMenu(x: Int, y: Int) {
        val options = arrayOf("点击此处", "长按此处", "保存坐标")
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("坐标 ($x, $y) - 选择操作")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        addStepToCurrentScript(com.keyspirit.script.ScriptStep(
                            type = com.keyspirit.script.StepType.CLICK,
                            x = x, y = y
                        ))
                        toast("已添加：点击 ($x, $y)")
                    }
                    1 -> {
                        addStepToCurrentScript(com.keyspirit.script.ScriptStep(
                            type = com.keyspirit.script.StepType.LONG_PRESS,
                            x = x, y = y, duration = 1000
                        ))
                        toast("已添加：长按 ($x, $y)")
                    }
                    2 -> {
                        toast("坐标已保存: ($x, $y)")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    /**
     * 添加步骤到当前脚本（如果没有当前脚本则创建默认项目）
     */
    private fun addStepToCurrentScript(step: com.keyspirit.script.ScriptStep) {
        var scriptId = com.keyspirit.util.CurrentProjectHolder.currentScriptId
        if (scriptId == null) {
            val defaultScript = com.keyspirit.script.Script(name = "默认项目")
            scriptManager.saveScript(defaultScript)
            scriptId = defaultScript.id
            com.keyspirit.util.CurrentProjectHolder.currentScriptId = scriptId
            com.keyspirit.util.CurrentProjectHolder.currentScriptName = defaultScript.name
        }
        val script = scriptManager.getScript(scriptId) ?: return
        script.steps.add(step)
        scriptManager.saveScript(script)
    }

    private fun removePickerOverlay() {
        pickerOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        pickerOverlay = null
        floatingBall?.visibility = View.VISIBLE
    }

    private var pickerToastView: android.widget.TextView? = null
    private fun showPickerToast(msg: String) {
        pickerToastView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        val tv = android.widget.TextView(this).apply {
            text = msg
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(40, 20, 40, 20)
            textSize = 14f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 200
        }
        windowManager.addView(tv, params)
        pickerToastView = tv
        // 自动消失（仅对非实时提示）
        handler.postDelayed({
            pickerToastView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
                pickerToastView = null
            }
        }, 3000)
    }

    private fun takeScreenshot() {
        hidePanel()
        val service = com.keyspirit.service.ScreenCaptureService.instance
        if (service == null) {
            toast("截屏服务未启动，请先在 App 首页授权截屏权限")
            return
        }
        // 在后台线程截屏（captureScreen 是同步阻塞方法）
        Thread {
            val bitmap = service.captureScreen()
            if (bitmap == null) {
                handler.post { toast("截屏失败，请重试") }
                return@Thread
            }
            // 如果没有当前项目，自动创建一个默认项目
            var scriptId = com.keyspirit.util.CurrentProjectHolder.currentScriptId
            var scriptName = com.keyspirit.util.CurrentProjectHolder.currentScriptName
            if (scriptId == null) {
                val defaultScript = com.keyspirit.script.Script(name = "默认项目")
                scriptManager.saveScript(defaultScript)
                scriptId = defaultScript.id
                scriptName = defaultScript.name
                com.keyspirit.util.CurrentProjectHolder.currentScriptId = scriptId
                com.keyspirit.util.CurrentProjectHolder.currentScriptName = scriptName
            }
            val path = com.keyspirit.util.ScreenshotUtils.saveToProject(this, bitmap, scriptId)
            handler.post {
                if (path != null) {
                    toast("已保存到【$scriptName】: ${path.substringAfterLast('/')}")
                } else {
                    toast("截图保存失败")
                }
            }
        }.start()
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
        removePickerOverlay()
        pickerToastView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        pickerToastView = null
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
