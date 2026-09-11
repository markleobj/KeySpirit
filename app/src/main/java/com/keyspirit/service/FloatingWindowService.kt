package com.keyspirit.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import com.keyspirit.script.ScriptStep
import com.keyspirit.script.StepType

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
            state = FloatingBallView.BallState.IDLE
            onTap = {
                if (this@FloatingWindowService.isRecording) {
                    stopRecording()
                } else {
                    togglePanel()
                }
            }
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
            onEdit = { openEditor() }
            onSave = { saveCurrentScript() }
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
        floatingBall?.state = FloatingBallView.BallState.RECORDING
        hidePanel()
        showRecordingOverlay()
        toast("开始录制，操作完成后点击停止")
    }

    private fun stopRecording() {
        isRecording = false
        val steps = touchRecorder.stopRecording()
        floatingBall?.state = FloatingBallView.BallState.IDLE
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

    private var touchIndicator: View? = null
    private var coordTextView: android.widget.TextView? = null

    // ============ 悬浮脚本编辑器 ============
    private var editorView: com.keyspirit.floating.FloatingEditorView? = null
    private var editingScript: Script? = null
    // 待添加的步骤类型（交互式选取完成后回填）
    private var pendingStepType: StepType? = null

    private fun openEditor() {
        hidePanel()
        if (editorView != null) return

        // 优先复用 editingScript（添加步骤的过程中 closeEditor 不应清空它），
        // 否则从存储加载当前脚本，没有就新建
        if (editingScript == null) {
            val scriptId = com.keyspirit.util.CurrentProjectHolder.currentScriptId
            val script = if (scriptId != null) {
                scriptManager.getScript(scriptId)
            } else null
            editingScript = script ?: Script(name = "新脚本")
        }

        editorView = com.keyspirit.floating.FloatingEditorView(this).apply {
            setScript(editingScript!!)
            listener = object : com.keyspirit.floating.FloatingEditorView.EditorListener {
                override fun onAddStep(type: StepType) {
                    handleAddStep(type)
                }
                override fun onEditStep(position: Int, step: ScriptStep) {
                    // 编辑步骤：根据类型重新交互式设置
                    pendingStepType = step.type
                    when (step.type) {
                        StepType.CLICK, StepType.LONG_PRESS -> startCoordinatePickForStep(step)
                        StepType.FIND_IMAGE -> startRegionPickForFindImage(step)
                        StepType.FIND_TEXT -> startRegionPickForFindText(step)
                        StepType.SWIPE -> startSwipePickForStep(step)
                        StepType.DELAY -> showDelayDialog(step)
                        else -> {}
                    }
                }
                override fun onDeleteStep(position: Int) {
                    editingScript?.steps?.removeAt(position)
                    editorView?.refreshStepList()
                }
                override fun onSave() {
                    editingScript?.let {
                        it.name = it.name.ifEmpty { "未命名脚本" }
                        scriptManager.saveScript(it)
                        com.keyspirit.util.CurrentProjectHolder.currentScriptId = it.id
                        com.keyspirit.util.CurrentProjectHolder.currentScriptName = it.name
                        toast("已保存")
                    }
                }
                override fun onRun() {
                    editingScript?.let {
                        it.name = it.name.ifEmpty { "未命名脚本" }
                        scriptManager.saveScript(it)
                        startExecution(it)
                        closeEditor()
                    }
                }
                override fun onClose() {
                    closeEditor()
                }
            }
        }

        val params = createOverlayParams(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.7).toInt()
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager.addView(editorView, params)
    }

    private fun closeEditor() {
        editorView?.let { windowManager.removeView(it) }
        editorView = null
        // 注意：不清空 editingScript，因为添加步骤的回调还需要往里面加步骤
        pendingStepType = null
    }

    /**
     * 保存当前脚本（悬浮编辑器中的脚本 或 CurrentProjectHolder 中的当前脚本）
     */
    private fun saveCurrentScript() {
        val script = editingScript ?: run {
            val id = com.keyspirit.util.CurrentProjectHolder.currentScriptId
            if (id != null) scriptManager.getScript(id) else null
        }
        if (script == null) {
            toast("没有可保存的脚本")
            return
        }
        script.name = script.name.ifEmpty { "未命名脚本" }
        scriptManager.saveScript(script)
        com.keyspirit.util.CurrentProjectHolder.currentScriptId = script.id
        com.keyspirit.util.CurrentProjectHolder.currentScriptName = script.name
        toast("已保存: ${script.name}")
    }

    /**
     * 处理添加步骤：根据类型进入不同的交互式选取流程
     */
    private fun handleAddStep(type: StepType) {
        pendingStepType = type
        when (type) {
            StepType.CLICK, StepType.LONG_PRESS -> startCoordinatePickForStep(null)
            StepType.FIND_IMAGE -> startRegionPickForFindImage(null)
            StepType.FIND_TEXT -> startRegionPickForFindText(null)
            StepType.SWIPE -> startSwipePickForStep(null)
            StepType.DELAY -> showDelayDialog(null)
            else -> {
                // 其他类型直接添加空步骤
                editingScript?.steps?.add(ScriptStep(type = type))
                editorView?.refreshStepList()
            }
        }
    }

    /**
     * 坐标选取模式：用于点击/长按步骤
     * existingStep 不为空表示编辑已有步骤
     */
    private fun startCoordinatePickForStep(existingStep: ScriptStep?) {
        closeEditor()
        floatingBall?.visibility = View.GONE

        val overlay = View(this).apply {
            setBackgroundColor(0x33000000)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        showPickerToast("X: ${event.rawX.toInt()}, Y: ${event.rawY.toInt()}（松开确认）")
                    }
                    MotionEvent.ACTION_UP -> {
                        val x = event.rawX.toInt()
                        val y = event.rawY.toInt()
                        removePickerOverlay()
                        floatingBall?.visibility = View.VISIBLE
                        val type = pendingStepType ?: StepType.CLICK
                        val step = existingStep ?: ScriptStep(type = type)
                        step.x = x
                        step.y = y
                        if (existingStep == null) {
                            editingScript?.steps?.add(step)
                        }
                        openEditor()
                        toast("已设置坐标: ($x, $y)")
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
        showPickerToast("点击屏幕选取${if (pendingStepType == StepType.LONG_PRESS) "长按" else "点击"}坐标")
    }

    /**
     * 滑动坐标选取：依次选取起点和终点
     */
    private var swipePickState = 0 // 0=选起点, 1=选终点
    private var swipeStep: ScriptStep? = null

    private fun startSwipePickForStep(existingStep: ScriptStep?) {
        swipeStep = existingStep ?: ScriptStep(type = StepType.SWIPE)
        swipePickState = 0
        closeEditor()
        floatingBall?.visibility = View.GONE

        val overlay = View(this).apply {
            setBackgroundColor(0x33000000)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        val msg = if (swipePickState == 0) "选起点" else "选终点"
                        showPickerToast("$msg: X: ${event.rawX.toInt()}, Y: ${event.rawY.toInt()}（松开确认）")
                    }
                    MotionEvent.ACTION_UP -> {
                        val x = event.rawX.toInt()
                        val y = event.rawY.toInt()
                        if (swipePickState == 0) {
                            swipeStep?.x1 = x
                            swipeStep?.y1 = y
                            swipePickState = 1
                            showPickerToast("已选起点，请选取终点")
                        } else {
                            swipeStep?.x2 = x
                            swipeStep?.y2 = y
                            removePickerOverlay()
                            floatingBall?.visibility = View.VISIBLE
                            if (existingStep == null) {
                                editingScript?.steps?.add(swipeStep!!)
                            }
                            swipeStep = null
                            openEditor()
                            toast("滑动已设置")
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
        showPickerToast("请选取滑动起点")
    }

    /**
     * 区域选取模式，用于找图步骤：选区域后自动截图该区域并保存为目标图片
     */
    private fun startRegionPickForFindImage(existingStep: ScriptStep?) {
        closeEditor()
        floatingBall?.visibility = View.GONE
        com.keyspirit.util.RegionResultHolder.hasNewResult = false

        val overlay = object : View(this) {
            private var startX = 0f
            private var startY = 0f
            private var curX = 0f
            private var curY = 0f
            private var dragging = false
            private val dashPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#00BFFF")
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 6f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 12f), 0f)
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                if (dragging) {
                    val left = Math.min(startX, curX)
                    val top = Math.min(startY, curY)
                    val right = Math.max(startX, curX)
                    val bottom = Math.max(startY, curY)
                    canvas.drawRect(left, top, right, bottom, dashPaint)
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        curX = event.rawX
                        curY = event.rawY
                        dragging = true
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        curX = event.rawX
                        curY = event.rawY
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        val left = Math.min(startX, event.rawX).toInt()
                        val top = Math.min(startY, event.rawY).toInt()
                        val right = Math.max(startX, event.rawX).toInt()
                        val bottom = Math.max(startY, event.rawY).toInt()
                        dragging = false
                        removePickerOverlay()
                        floatingBall?.visibility = View.VISIBLE

                        if (right - left < 20 || bottom - top < 20) {
                            toast("区域太小，请重新选择")
                            openEditor()
                            return true
                        }
                        captureRegionAndSave(left, top, right, bottom, existingStep)
                    }
                }
                return true
            }

            init {
                setBackgroundColor(0x33000000)
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
        showPickerToast("拖动框选找图区域，松开后自动截图")
    }

    /**
     * 截取指定区域并保存为找图目标图片
     */
    private fun captureRegionAndSave(left: Int, top: Int, right: Int, bottom: Int, existingStep: ScriptStep?) {
        val service = ScreenCaptureService.instance
        if (service == null) {
            toast("截屏服务未启动，请先授权截屏权限")
            openEditor()
            return
        }
        // 截图前隐藏悬浮球，避免被截进去
        floatingBall?.visibility = View.GONE
        Thread {
            // 等屏幕刷新（遮罩层和悬浮球移除后）
            try { Thread.sleep(200) } catch (_: InterruptedException) {}

            val bitmap = service.captureScreen()
            if (bitmap == null) {
                handler.post {
                    floatingBall?.visibility = View.VISIBLE
                    toast("截屏失败：${service.getDiagnosticInfo().takeLast(100)}")
                    openEditor()
                }
                return@Thread
            }
            // 裁剪区域
            val cropLeft = left.coerceIn(0, bitmap.width - 1)
            val cropTop = top.coerceIn(0, bitmap.height - 1)
            val cropRight = right.coerceIn(cropLeft + 1, bitmap.width)
            val cropBottom = bottom.coerceIn(cropTop + 1, bitmap.height)
            val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop)

            // 确保当前脚本已保存（获取 scriptId）
            val script = editingScript ?: run {
                handler.post {
                    floatingBall?.visibility = View.VISIBLE
                    openEditor()
                }
                return@Thread
            }
            if (script.id.isBlank() || scriptManager.getScript(script.id) == null) {
                script.name = script.name.ifEmpty { "未命名脚本" }
                scriptManager.saveScript(script)
            }

            val path = com.keyspirit.util.ScreenshotUtils.saveToProject(this@FloatingWindowService, cropped, script.id)
            handler.post {
                floatingBall?.visibility = View.VISIBLE
                if (path != null) {
                    val step = existingStep ?: ScriptStep(type = StepType.FIND_IMAGE)
                    step.imagePath = path
                    step.regionLeft = left
                    step.regionTop = top
                    step.regionRight = right
                    step.regionBottom = bottom
                    step.useRegion = true
                    if (existingStep == null) {
                        editingScript?.steps?.add(step)
                    }
                    toast("已截取目标图片并设置区域 (${cropped.width}x${cropped.height})")
                } else {
                    toast("图片保存失败")
                }
                openEditor()
            }
        }.start()
    }

    /**
     * 区域选取模式，用于找文字步骤
     */
    private fun startRegionPickForFindText(existingStep: ScriptStep?) {
        closeEditor()
        floatingBall?.visibility = View.GONE

        val overlay = object : View(this) {
            private var startX = 0f
            private var startY = 0f
            private var curX = 0f
            private var curY = 0f
            private var dragging = false
            private val dashPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#00BFFF")
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 6f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 12f), 0f)
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                if (dragging) {
                    val left = Math.min(startX, curX)
                    val top = Math.min(startY, curY)
                    val right = Math.max(startX, curX)
                    val bottom = Math.max(startY, curY)
                    canvas.drawRect(left, top, right, bottom, dashPaint)
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        curX = event.rawX
                        curY = event.rawY
                        dragging = true
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        curX = event.rawX
                        curY = event.rawY
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        val left = Math.min(startX, event.rawX).toInt()
                        val top = Math.min(startY, event.rawY).toInt()
                        val right = Math.max(startX, event.rawX).toInt()
                        val bottom = Math.max(startY, event.rawY).toInt()
                        dragging = false
                        removePickerOverlay()
                        floatingBall?.visibility = View.VISIBLE

                        // 弹出输入框让用户输入要找的文字
                        showFindTextDialog(left, top, right, bottom, existingStep)
                    }
                }
                return true
            }

            init {
                setBackgroundColor(0x33000000)
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
        showPickerToast("拖动框选查找文字的区域")
    }

    private fun showFindTextDialog(left: Int, top: Int, right: Int, bottom: Int, existingStep: ScriptStep?) {
        val input = android.widget.EditText(this).apply {
            hint = "输入要查找的文字"
            setPadding(32, 16, 32, 16)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("查找文字")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    val step = existingStep ?: ScriptStep(type = StepType.FIND_TEXT)
                    step.text = text
                    step.regionLeft = left
                    step.regionTop = top
                    step.regionRight = right
                    step.regionBottom = bottom
                    step.useRegion = true
                    if (existingStep == null) {
                        editingScript?.steps?.add(step)
                    }
                }
                openEditor()
            }
            .setNegativeButton("取消") { _, _ -> openEditor() }
            .create()
            .apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
            .show()
    }

    private fun showDelayDialog(existingStep: ScriptStep?) {
        val input = android.widget.EditText(this).apply {
            hint = "延迟毫秒数（如 500）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("500")
            setPadding(32, 16, 32, 16)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("设置延迟")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val ms = input.text.toString().toLongOrNull() ?: 500
                val step = existingStep ?: ScriptStep(type = StepType.DELAY)
                step.delay = ms
                if (existingStep == null) {
                    editingScript?.steps?.add(step)
                }
                editorView?.refreshStepList()
            }
            .setNegativeButton("取消", null)
            .create()
            .apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
            .show()
    }

    private fun showRecordingOverlay() {
        // 用 FrameLayout 承载透明触摸层 + 触摸点指示器 + 坐标文本
        val container = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0x00000000)
        }

        // 触摸点指示器（红色小圆点），实时显示触摸位置
        touchIndicator = View(this).apply {
            setBackgroundColor(android.graphics.Color.RED)
            alpha = 0.7f
            visibility = View.GONE
        }
        val indicatorSize = 30
        container.addView(touchIndicator, android.widget.FrameLayout.LayoutParams(indicatorSize, indicatorSize))

        // 坐标文本显示（屏幕左上角，实时显示 rawX/rawY）
        coordTextView = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 14f
            setPadding(16, 8, 16, 8)
            text = "触摸坐标: (-, -)"
        }
        container.addView(coordTextView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 16
            topMargin = 16
        })

        container.setOnTouchListener { _, event ->
            val rx = event.rawX
            val ry = event.rawY
            // 更新坐标文本
            coordTextView?.text = "触摸坐标: (${rx.toInt()}, ${ry.toInt()})"
            // 更新指示器位置（以触摸点为中心）
            touchIndicator?.let { ind ->
                ind.x = rx - indicatorSize / 2f
                ind.y = ry - indicatorSize / 2f
                ind.visibility = if (event.action == MotionEvent.ACTION_UP) View.GONE else View.VISIBLE
            }
            android.util.Log.d("RecordTouch", "action=${event.action} rawX=${rx} rawY=${ry}")
            touchRecorder.onTouchEvent(event)
            touchRecorder.dispatchToApp(event)
            true
        }

        recordingOverlay = container
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
                floatingBall?.state = FloatingBallView.BallState.IDLE
                toast("脚本执行完成")
                scriptManager.markRun(script.id)
            }
            override fun onError(message: String) {
                isExecuting = false
                scriptExecutor = null
                hidePanel()
                floatingBall?.state = FloatingBallView.BallState.IDLE
                toast("执行出错: $message")
            }
        })
        floatingBall?.state = FloatingBallView.BallState.EXECUTING
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
                floatingBall?.state = FloatingBallView.BallState.EXECUTING
            } else {
                it.pause()
                floatingBall?.state = FloatingBallView.BallState.PAUSED
            }
        }
    }

    private fun stopExecution() {
        scriptExecutor?.stop()
        isExecuting = false
        scriptExecutor = null
        hidePanel()
        floatingBall?.state = FloatingBallView.BallState.IDLE
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
            toast("截屏服务未启动，请先在 App 首页授权截屏权限", long = true)
            return
        }
        toast("正在截图...")
        // 在后台线程截屏（captureScreen 是同步阻塞方法）
        Thread {
            try {
                val bitmap = service.captureScreen()
                if (bitmap == null) {
                    handler.post {
                        toast("截屏失败\n${service.getDiagnosticInfo()}", long = true)
                    }
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
            } catch (e: Exception) {
                android.util.Log.e("FloatingWindow", "截图线程异常", e)
                handler.post {
                    toast("截图异常: ${e.message}", long = true)
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

    private fun toast(msg: String, long: Boolean = false) {
        handler.post {
            android.widget.Toast.makeText(this, msg, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 弹出诊断信息对话框，让用户直接看到截屏失败的具体原因
     */
    private fun showDiagnosticDialog(message: String) {
        handler.post {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("截屏诊断")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .create()
                .apply {
                    window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
                .show()
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
