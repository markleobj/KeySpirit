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

    // 找图两步选取：第一步搜索区域，第二步目标图片区域
    private var findImageSearchRegion: IntArray? = null
    private var findImageExistingStep: com.keyspirit.script.ScriptStep? = null

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
            try { windowManager.removeView(it) } catch (_: Exception) {}
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

    /**
     * 确保 editingScript 存在且 steps 不为 null
     */
    private fun ensureEditingScript(): Script {
        if (editingScript == null) {
            val scriptId = com.keyspirit.util.CurrentProjectHolder.currentScriptId
            val script = if (scriptId != null) {
                scriptManager.getScript(scriptId)
            } else null
            editingScript = script ?: Script(name = "新脚本")
        }
        val s = editingScript!!
        // 兜底：Gson 反序列化可能导致 steps 运行时为 null
        @Suppress("SENSELESS_COMPARISON")
        if (s.steps == null) {
            s.steps = mutableListOf()
        }
        return s
    }

    private fun openEditor() {
        hidePanel()
        // 如果 editorView 还在（例如 closeEditor 抛异常导致没清掉），先强制移除
        editorView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            editorView = null
        }

        val script = ensureEditingScript()
        Log.d(TAG, "openEditor: script=${script.name}, steps=${script.steps.size}")

        editorView = com.keyspirit.floating.FloatingEditorView(this).apply {
            setScript(script)
            listener = object : com.keyspirit.floating.FloatingEditorView.EditorListener {
                override fun onAddStep(type: StepType) {
                    handleAddStep(type)
                }
                override fun onEditStep(position: Int, step: ScriptStep) {
                    // 编辑步骤：根据类型重新交互式设置
                    pendingStepType = step.type
                    when (step.type) {
                        StepType.CLICK, StepType.LONG_PRESS,
                        StepType.TOUCH_DOWN, StepType.TOUCH_UP,
                        StepType.RIGHT_CLICK, StepType.RIGHT_CLICK_DOWN, StepType.RIGHT_CLICK_UP -> startCoordinatePickForStep(step)
                        StepType.FIND_IMAGE -> startRegionPickForFindImage(step)
                        StepType.FIND_TEXT -> startRegionPickForFindText(step)
                        StepType.SWIPE -> startSwipePickForStep(step)
                        StepType.DELAY -> showDelayDialog(step)
                        else -> {}
                    }
                }
                override fun onDeleteStep(position: Int) {
                    ensureEditingScript().steps.removeAt(position)
                    editorView?.refreshStepList()
                }
                override fun onSave() {
                    ensureEditingScript().let {
                        it.name = it.name.ifEmpty { "未命名脚本" }
                        scriptManager.saveScript(it)
                        com.keyspirit.util.CurrentProjectHolder.currentScriptId = it.id
                        com.keyspirit.util.CurrentProjectHolder.currentScriptName = it.name
                        toast("已保存")
                    }
                }
                override fun onRun() {
                    ensureEditingScript().let {
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
        editorView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        editorView = null
        // 注意：不清空 editingScript，因为添加步骤的回调还需要往里面加步骤
        // pendingStepType 保留，供 picker 完成后读取步骤类型
    }

    /**
     * 保存当前脚本（悬浮编辑器中的脚本 或 CurrentProjectHolder 中的当前脚本）
     */
    private fun saveCurrentScript() {
        val script = if (editingScript != null) {
            ensureEditingScript()
        } else {
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
        val script = ensureEditingScript()
        Log.d(TAG, "handleAddStep: type=$type, current steps=${script.steps.size}")
        when (type) {
            StepType.CLICK, StepType.LONG_PRESS,
            StepType.TOUCH_DOWN, StepType.TOUCH_UP,
            StepType.RIGHT_CLICK, StepType.RIGHT_CLICK_DOWN, StepType.RIGHT_CLICK_UP -> startCoordinatePickForStep(null)
            StepType.FIND_IMAGE -> startRegionPickForFindImage(null)
            StepType.FIND_TEXT -> startRegionPickForFindText(null)
            StepType.SWIPE -> startSwipePickForStep(null)
            StepType.DELAY -> showDelayDialog(null)
            else -> {
                // 其他类型直接添加空步骤
                script.steps.add(ScriptStep(type = type))
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
                        val step = existingStep ?: ScriptStep(type = type).apply {
                            // 需要按住的步骤默认持续时间
                            when (type) {
                                StepType.TOUCH_DOWN -> duration = 10000 // 按住不放
                                StepType.RIGHT_CLICK, StepType.RIGHT_CLICK_DOWN -> duration = 300
                                StepType.LONG_PRESS -> duration = 500
                                else -> {}
                            }
                        }
                        step.x = x
                        step.y = y
                        val script = ensureEditingScript()
                        if (existingStep == null) {
                            script.steps.add(step)
                            Log.d(TAG, "Added ${type.displayName} step at ($x, $y), total steps=${script.steps.size}")
                        }
                        openEditor()
                        toast("已设置${type.displayName}: ($x, $y)")
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
        showPickerToast("点击屏幕选取${pendingStepType?.displayName ?: "点击"}坐标")
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
                            val script = ensureEditingScript()
                            if (existingStep == null) {
                                script.steps.add(swipeStep!!)
                                Log.d(TAG, "Added SWIPE step, total steps=${script.steps.size}")
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
        // 预检查：截屏服务必须运行
        if (!com.keyspirit.service.ScreenCaptureService.isRunning()) {
            val state = com.keyspirit.service.ScreenCaptureService.getState()
            val error = com.keyspirit.service.ScreenCaptureService.getError()
            val message = buildString {
                append("找图功能需要截屏权限。")
                when (state) {
                    com.keyspirit.service.ScreenCaptureService.STATE_ERROR -> {
                        append("\n\n截屏服务启动失败：")
                        append(error.ifEmpty { "未知错误" })
                        append("\n\n请返回设置页重新授权截屏权限。")
                    }
                    com.keyspirit.service.ScreenCaptureService.STATE_STARTING -> {
                        append("\n\n截屏服务正在启动中，请稍候再试。")
                    }
                    else -> {
                        append("\n\n请返回设置页，开启截屏权限后再试。")
                    }
                }
            }
            showAlertDialog(
                title = "截屏服务未启动",
                message = message,
                positive = "知道了"
            ) { openEditor() }
            return
        }
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
                        try { windowManager.removeView(this) } catch (_: Exception) {}

                        if (right - left < 20 || bottom - top < 20) {
                            floatingBall?.visibility = View.VISIBLE
                            toast("区域太小，请重新选择")
                            openEditor()
                            findImageSearchRegion = null
                            findImageExistingStep = null
                            return true
                        }

                        val searchRegion = findImageSearchRegion
                        if (searchRegion == null) {
                            // 第一步：搜索区域已选，进入第二步选择目标图片
                            findImageSearchRegion = intArrayOf(left, top, right, bottom)
                            findImageExistingStep = existingStep
                            showPickerToast("搜索区域已选，请框选要找的目标图片")
                            // 重新显示选取层，让用户画目标图片区域
                            startRegionPickForFindImage(existingStep)
                        } else {
                            // 第二步：目标图片区域，截图保存
                            captureRegionAndSave(
                                searchRegion[0], searchRegion[1], searchRegion[2], searchRegion[3],
                                left, top, right, bottom,
                                findImageExistingStep
                            )
                            findImageSearchRegion = null
                            findImageExistingStep = null
                        }
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
        try {
            windowManager.addView(overlay, params)
        } catch (e: Exception) {
            Log.e(TAG, "startRegionPickForFindImage: addView failed", e)
            floatingBall?.visibility = View.VISIBLE
            toast("无法创建选取层: ${e.message}")
            openEditor()
            return
        }
        pickerOverlay = overlay
        if (findImageSearchRegion == null) {
            showPickerToast("第1步：框选搜索区域（在哪里找）")
        } else {
            showPickerToast("第2步：框选目标图片（找什么）")
        }
    }

    /**
     * 截取目标区域并保存为找图目标图片。
     * 搜索区域用于 findImage 时限定搜索范围，目标区域用于截图保存为模板。
     */
    private fun captureRegionAndSave(
        searchLeft: Int, searchTop: Int, searchRight: Int, searchBottom: Int,
        targetLeft: Int, targetTop: Int, targetRight: Int, targetBottom: Int,
        existingStep: ScriptStep?
    ) {
        try {
            val service = ScreenCaptureService.instance
            if (service == null) {
                Log.e(TAG, "captureRegionAndSave: ScreenCaptureService.instance is null")
                floatingBall?.visibility = View.VISIBLE
                val error = ScreenCaptureService.getError()
                val message = if (error.isNotEmpty()) {
                    "截屏服务已断开：$error\n\n请返回设置页重新授权截屏权限。"
                } else {
                    "截屏服务已断开。请返回设置页重新授权截屏权限。"
                }
                showAlertDialog(
                    title = "截屏失败",
                    message = message,
                    positive = "好的"
                ) { openEditor() }
                return
            }
            val script = ensureEditingScript()
            floatingBall?.visibility = View.GONE
            toast("正在截图...")
            Log.d(TAG, "captureRegionAndSave: search=($searchLeft,$searchTop)-($searchRight,$searchBottom), target=($targetLeft,$targetTop)-($targetRight,$targetBottom)")

            Thread {
                try {
                    Thread.sleep(400)

                    val bitmap = service.captureScreen()
                    if (bitmap == null) {
                        Log.e(TAG, "captureRegionAndSave: captureScreen returned null")
                        handler.post {
                            floatingBall?.visibility = View.VISIBLE
                            showAlertDialog(
                                title = "截屏失败",
                                message = "无法获取屏幕截图。请确认：\n1. 截屏权限已授予\n2. 授权后未重启应用\n3. 悬浮球和遮罩层已移除",
                                positive = "好的"
                            ) { openEditor() }
                        }
                        return@Thread
                    }
                    Log.d(TAG, "captureRegionAndSave: bitmap=${bitmap.width}x${bitmap.height}")
                    // 只裁剪目标区域（要找的图片），不是整个搜索区域
                    val cropLeft = targetLeft.coerceIn(0, bitmap.width - 1)
                    val cropTop = targetTop.coerceIn(0, bitmap.height - 1)
                    val cropRight = targetRight.coerceIn(cropLeft + 1, bitmap.width)
                    val cropBottom = targetBottom.coerceIn(cropTop + 1, bitmap.height)
                    val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop)
                    Log.d(TAG, "captureRegionAndSave: target cropped=${cropped.width}x${cropped.height}")

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
                            // 搜索区域用于限定找图范围
                            step.regionLeft = searchLeft
                            step.regionTop = searchTop
                            step.regionRight = searchRight
                            step.regionBottom = searchBottom
                            step.useRegion = true
                            if (existingStep == null) {
                                script.steps.add(step)
                                Log.d(TAG, "Added FIND_IMAGE step, total steps=${script.steps.size}")
                            }
                            toast("✓ 目标图片已保存 (${cropped.width}x${cropped.height})")
                        } else {
                            toast("图片保存失败")
                        }
                        openEditor()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "captureRegionAndSave background error", e)
                    handler.post {
                        floatingBall?.visibility = View.VISIBLE
                        toast("截图出错: ${e.message}")
                        openEditor()
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "captureRegionAndSave error", e)
            floatingBall?.visibility = View.VISIBLE
            toast("截图出错: ${e.message}")
            openEditor()
        }
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
                    val script = ensureEditingScript()
                    val step = existingStep ?: ScriptStep(type = StepType.FIND_TEXT)
                    step.text = text
                    step.regionLeft = left
                    step.regionTop = top
                    step.regionRight = right
                    step.regionBottom = bottom
                    step.useRegion = true
                    if (existingStep == null) {
                        script.steps.add(step)
                        Log.d(TAG, "Added FIND_TEXT step, total steps=${script.steps.size}")
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
                val script = ensureEditingScript()
                val step = existingStep ?: ScriptStep(type = StepType.DELAY)
                step.delay = ms
                if (existingStep == null) {
                    script.steps.add(step)
                    Log.d(TAG, "Added DELAY step, total steps=${script.steps.size}")
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
            try { windowManager.removeView(it) } catch (_: Exception) {}
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
            val error = com.keyspirit.service.ScreenCaptureService.getError()
            val msg = if (error.isNotEmpty()) {
                "截屏服务未启动：$error"
            } else {
                "截屏服务未启动，请先在设置页开启截屏权限"
            }
            toast(msg, long = true)
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
        editorView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        editorView = null
        floatingBall?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        floatingBall = null
    }

    private fun toast(msg: String, long: Boolean = false) {
        handler.post {
            android.widget.Toast.makeText(this, msg, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 通用 AlertDialog，从 Service 上下文弹出（自动设置 TYPE_APPLICATION_OVERLAY）
     */
    private fun showAlertDialog(
        title: String,
        message: String,
        positive: String = "确定",
        onPositive: (() -> Unit)? = null,
        negative: String? = null,
        onNegative: (() -> Unit)? = null
    ) {
        handler.post {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive) { _, _ -> onPositive?.invoke() }
                .apply {
                    if (negative != null) {
                        setNegativeButton(negative) { _, _ -> onNegative?.invoke() }
                    }
                }
                .create()
                .apply {
                    window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    setCancelable(false)
                }
                .show()
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
