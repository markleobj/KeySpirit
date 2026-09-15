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
    private var mouseIndicator: View? = null  // 鼠标位置指示器
    private var showMouseIndicator = true     // 是否显示鼠标指示器

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
        // 如果悬浮球创建失败（缺少悬浮窗权限），提示用户
        if (floatingBall == null) {
            toast("悬浮窗权限未开启，请在设置中开启")
        }
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
        val ballSize = resources.getDimensionPixelSize(R.dimen.floating_ball_size)
        val params = createOverlayParams(ballSize, ballSize).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp2px(100)
            y = dp2px(300)
        }
        try {
            windowManager.addView(floatingBall!!, params)
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingBall: addView failed", e)
            floatingBall = null
        }
    }

    private fun updateBallPosition(x: Int, y: Int) {
        val ball = floatingBall ?: return
        try {
            val params = ball.layoutParams as WindowManager.LayoutParams
            params.x = x
            params.y = y
            windowManager.updateViewLayout(ball, params)
        } catch (e: Exception) {
            Log.e(TAG, "updateBallPosition failed", e)
        }
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
            onScreenshot = { takeScreenshot() }
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
            // 确保面板不超出屏幕底部
            val displayMetrics = resources.displayMetrics
            val ballSize = resources.getDimensionPixelSize(R.dimen.floating_ball_size)
            y = (ballParams.y + ballSize + dp2px(8)).coerceAtMost(displayMetrics.heightPixels - ballSize - dp2px(8))
        }
        try {
            windowManager.addView(floatingPanel, params)
            isPanelVisible = true
        } catch (e: Exception) {
            Log.e(TAG, "showPanel: addView failed", e)
            floatingPanel = null
            toast("无法显示面板: ${e.message}")
        }
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

        if (steps.isNotEmpty()) {
            // 创建新脚本并打开编辑器让用户编辑
            val script = Script(
                name = "录制脚本",
                steps = steps.toMutableList()
            )
            editingScript = script
            com.keyspirit.util.CurrentProjectHolder.currentScriptId = script.id
            com.keyspirit.util.CurrentProjectHolder.currentScriptName = script.name
            toast("录制完成，共 ${steps.size} 步，正在打开编辑器...")
            // 延迟一下让录制浮层完全消失
            handler.postDelayed({
                openEditor()
            }, 300)
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
    // 待添加步骤的路径（空列表表示根级别，非空表示添加到某个 IF 块内）
    private var pendingAddPath: List<Int> = emptyList()
    private var pendingInsertAfter: Boolean = true  // true=之后插入, false=之前插入

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
        // 递归确保所有 ifSteps 不为 null
        ensureIfStepsNotNull(s.steps)
        return s
    }

    /**
     * 递归确保所有步骤的 ifSteps 不为 null
     */
    private fun ensureIfStepsNotNull(steps: MutableList<ScriptStep>?) {
        if (steps == null) return
        for (step in steps) {
            @Suppress("SENSELESS_COMPARISON")
            if (step.ifSteps == null) {
                step.ifSteps = mutableListOf()
            }
            if (step.type == StepType.IF && step.ifSteps.isNotEmpty()) {
                ensureIfStepsNotNull(step.ifSteps)
            }
        }
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
                override fun onAddCommand(type: StepType, selectedPath: List<Int>?) {
                    handleAddStep(type, selectedPath)
                }
                override fun onEditStep(path: List<Int>, step: ScriptStep) {
                    // 编辑步骤：根据类型重新交互式设置
                    pendingStepType = step.type
                    when (step.type) {
                        StepType.CLICK, StepType.LONG_PRESS,
                        StepType.TOUCH_DOWN, StepType.TOUCH_UP,
                        StepType.RIGHT_CLICK, StepType.RIGHT_CLICK_DOWN, StepType.RIGHT_CLICK_UP,
                        StepType.MOVE_MOUSE, StepType.PICK_POINT -> startCoordinatePickForStep(step)
                        StepType.FIND_IMAGE -> startRegionPickForFindImage(step)
                        StepType.FIND_TEXT -> startRegionPickForFindText(step)
                        StepType.SCREENSHOT -> startRegionPickForScreenshot(step)
                        StepType.SWIPE -> startSwipePickForStep(step)
                        StepType.DELAY -> showDelayDialog(step)
                        StepType.LOOP -> showLoopDialog(step)
                        StepType.IF -> showIfDialog(step)
                        else -> {}
                    }
                }
                override fun onDeleteStep(path: List<Int>) {
                    val parentList = getParentListByPath(path)
                    val index = path.last()
                    if (parentList != null && index in parentList.indices) {
                        parentList.removeAt(index)
                        editorView?.clearSelection()
                        editorView?.refreshStepList()
                    } else {
                        toast("删除失败：步骤路径无效")
                    }
                }
                override fun onSave() {
                    val script = ensureEditingScript()
                    showSaveNameDialog(script)
                }
                override fun onRun() {
                    val script = ensureEditingScript()
                    // 如果脚本没有名字或默认名字，先让用户命名
                    if (script.name.isEmpty() || script.name == "新脚本" || script.name == "录制脚本") {
                        showSaveNameDialog(script, onSaved = {
                            startExecution(script)
                            closeEditor()
                        })
                    } else {
                        scriptManager.saveScript(script)
                        startExecution(script)
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
        try {
            windowManager.addView(editorView, params)
        } catch (e: Exception) {
            Log.e(TAG, "openEditor: addView failed", e)
            editorView = null
            toast("无法显示编辑器: ${e.message}")
        }
    }

    /**
     * 根据路径获取父步骤列表
     * 例如 path=[2, 1] 表示第3个步骤的子步骤列表中的第2个步骤 → 返回该子步骤列表
     */
    private fun getParentListByPath(path: List<Int>): MutableList<ScriptStep>? {
        if (path.isEmpty()) return null
        val script = ensureEditingScript()
        var currentList: MutableList<ScriptStep> = script.steps
        // 遍历到倒数第二层（父层）
        for (i in 0 until path.size - 1) {
            val idx = path[i]
            if (idx !in currentList.indices) return null
            val step = currentList[idx]
            currentList = when (step.type) {
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
                else -> return null
            }
        }
        return currentList
    }

    /**
     * 根据路径获取步骤
     */
    private fun getStepByPath(path: List<Int>): ScriptStep? {
        if (path.isEmpty()) return null
        val script = ensureEditingScript()
        var currentList: MutableList<ScriptStep> = script.steps
        for (i in path.indices) {
            val idx = path[i]
            if (idx !in currentList.indices) return null
            val step = currentList[idx]
            if (i == path.size - 1) return step
            currentList = when (step.type) {
                StepType.IF -> step.ifSteps
                else -> return null
            }
        }
        return null
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
        // 弹出命名对话框让用户输入项目名称
        showSaveNameDialog(script)
    }

    /**
     * 保存命名对话框
     */
    private fun showSaveNameDialog(script: Script, onSaved: (() -> Unit)? = null) {
        val input = android.widget.EditText(this).apply {
            hint = "请输入项目名称"
            setText(if (script.name.isNotEmpty() && script.name != "新脚本" && script.name != "录制脚本") script.name else "")
            setSingleLine()
            setPadding(dp2px(32), dp2px(24), dp2px(32), dp2px(24))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("保存项目")
            .setMessage("请给这个项目起个名字")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("请输入项目名称")
                    return@setPositiveButton
                }
                script.name = name
                scriptManager.saveScript(script)
                com.keyspirit.util.CurrentProjectHolder.currentScriptId = script.id
                com.keyspirit.util.CurrentProjectHolder.currentScriptName = script.name
                editorView?.setScript(script)
                toast("已保存: $name")
                onSaved?.invoke()
            }
            .setNegativeButton("取消", null)
            .create()
            .apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
            .show()
    }

    /**
     * 处理添加步骤：根据是否选中步骤，决定直接添加还是弹"之前/之后"选择
     */
    private fun handleAddStep(type: StepType, selectedPath: List<Int>?) {
        pendingStepType = type
        if (selectedPath == null) {
            // 未选中步骤，添加到末尾
            pendingAddPath = emptyList()
            pendingInsertAfter = true
            proceedWithCommand(type)
        } else {
            // 选中了步骤，弹出"之前/之后"选择
            val step = getStepByPath(selectedPath)
            val desc = step?.getDescription() ?: "未知"
            val stepType = step?.type?.displayName ?: "步骤"
            android.app.AlertDialog.Builder(this)
                .setTitle("插入位置")
                .setMessage("在「$stepType: $desc」之前或之后插入？")
                .setPositiveButton("之后") { _, _ ->
                    pendingAddPath = selectedPath
                    pendingInsertAfter = true
                    proceedWithCommand(type)
                }
                .setNegativeButton("之前") { _, _ ->
                    pendingAddPath = selectedPath
                    pendingInsertAfter = false
                    proceedWithCommand(type)
                }
                .setNeutralButton("取消", null)
                .create()
                .apply {
                    window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
                .show()
        }
    }

    /**
     * 根据类型进入不同的交互式选取流程
     */
    private fun proceedWithCommand(type: StepType) {
        when (type) {
            StepType.CLICK, StepType.LONG_PRESS,
            StepType.TOUCH_DOWN, StepType.TOUCH_UP,
            StepType.RIGHT_CLICK, StepType.RIGHT_CLICK_DOWN, StepType.RIGHT_CLICK_UP,
            StepType.MOVE_MOUSE, StepType.PICK_POINT -> startCoordinatePickForStep(null)
            StepType.FIND_IMAGE -> startRegionPickForFindImage(null)
            StepType.FIND_TEXT -> startRegionPickForFindText(null)
            StepType.SCREENSHOT -> startRegionPickForScreenshot(null)
            StepType.SWIPE -> startSwipePickForStep(null)
            StepType.DELAY -> showDelayDialog(null)
            StepType.LOOP -> showLoopDialog(null)
            StepType.IF -> showIfDialog(null)
            else -> {
                insertStep(ScriptStep(type = type), pendingAddPath, pendingInsertAfter)
                editorView?.clearSelection()
                editorView?.refreshStepList()
            }
        }
    }

    /**
     * 插入步骤到指定路径的之前或之后
     * path = [] → 根级别末尾
     * path = [2], after=true → 第3个步骤之后
     * path = [2], after=false → 第3个步骤之前
     * 如果目标步骤是 LOOP/IF 且 after=true → 插入到该块的子步骤列表开头（进入块内）
     */
    private fun insertStep(step: ScriptStep, path: List<Int>, isAfter: Boolean) {
        val script = ensureEditingScript()
        if (path.isEmpty()) {
            script.steps.add(step)
            return
        }
        val parentPath = path.dropLast(1)
        val targetIdx = path.last()
        val parentList = if (parentPath.isEmpty()) script.steps else getStepListByPath(parentPath)
        if (targetIdx !in parentList.indices) {
            Log.e(TAG, "insertStep: 路径无效 $path")
            script.steps.add(step)
            return
        }
        val targetStep = parentList[targetIdx]
        // 如果目标是块(LOOP/IF)且选"之后"，插入到块内子步骤开头
        if (isAfter && (targetStep.type == StepType.LOOP || targetStep.type == StepType.IF)) {
            val childList = when (targetStep.type) {
                StepType.LOOP -> {
                    @Suppress("SENSELESS_COMPARISON")
                    if (targetStep.loopSteps == null) targetStep.loopSteps = mutableListOf()
                    targetStep.loopSteps
                }
                StepType.IF -> {
                    @Suppress("SENSELESS_COMPARISON")
                    if (targetStep.ifSteps == null) targetStep.ifSteps = mutableListOf()
                    targetStep.ifSteps
                }
                else -> parentList
            }
            childList.add(0, step)
            Log.d(TAG, "insertStep: 插入到块 $targetIdx 的子步骤开头")
        } else {
            val insertIdx = if (isAfter) targetIdx + 1 else targetIdx
            parentList.add(insertIdx.coerceIn(0, parentList.size), step)
            Log.d(TAG, "insertStep: 插入到 $insertIdx (target=$targetIdx, after=$isAfter)")
        }
    }

    /**
     * 根据路径获取步骤子列表（用于查找 IF/LOOP 块的子步骤列表）
     */
    private fun getStepListByPath(path: List<Int>): MutableList<ScriptStep> {
        val script = ensureEditingScript()
        if (path.isEmpty()) return script.steps
        var currentList: MutableList<ScriptStep> = script.steps
        for (i in path.indices) {
            val idx = path[i]
            if (idx !in currentList.indices) return script.steps
            val step = currentList[idx]
            currentList = when (step.type) {
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
                else -> return script.steps
            }
        }
        return currentList
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
                        if (existingStep == null) {
                            insertStep(step, pendingAddPath, pendingInsertAfter)
                            Log.d(TAG, "Added ${type.displayName} step at ($x, $y), path=$pendingAddPath")
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
        try {
            windowManager.addView(overlay, params)
            pickerOverlay = overlay
        } catch (e: Exception) {
            Log.e(TAG, "startCoordinatePickForStep: addView failed", e)
            floatingBall?.visibility = View.VISIBLE
            toast("无法创建选取层: ${e.message}")
            openEditor()
            return
        }
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
                            if (existingStep == null) {
                                insertStep(swipeStep!!, pendingAddPath, pendingInsertAfter)
                                Log.d(TAG, "Added SWIPE step, path=$pendingAddPath")
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
        try {
            windowManager.addView(overlay, params)
            pickerOverlay = overlay
        } catch (e: Exception) {
            Log.e(TAG, "startSwipePickForStep: addView failed", e)
            floatingBall?.visibility = View.VISIBLE
            toast("无法创建选取层: ${e.message}")
            openEditor()
            return
        }
        showPickerToast("请选取滑动起点")
    }

    /**
     * 区域选取模式，用于截图步骤：选区域后截图并让用户命名
     */
    private fun startRegionPickForScreenshot(existingStep: ScriptStep?) {
        // 预检查：截屏服务必须运行
        if (!com.keyspirit.service.ScreenCaptureService.isRunning()) {
            val state = com.keyspirit.service.ScreenCaptureService.getState()
            val error = com.keyspirit.service.ScreenCaptureService.getError()
            val message = buildString {
                append("截图功能需要截屏权限。")
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

        val overlay = object : View(this) {
            private var startX = 0f
            private var startY = 0f
            private var curX = 0f
            private var curY = 0f
            private var dragging = false
            private val dashPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#E74C3C")
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
                        pickerOverlay = null

                        if (right - left < 20 || bottom - top < 20) {
                            floatingBall?.visibility = View.VISIBLE
                            toast("区域太小，请重新选择")
                            openEditor()
                            return true
                        }
                        // 弹出命名对话框
                        showScreenshotNameDialog(left, top, right, bottom, existingStep)
                    }
                }
                return true
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
            pickerOverlay = overlay
        } catch (e: Exception) {
            Log.e(TAG, "startRegionPickForScreenshot: addView failed", e)
            floatingBall?.visibility = View.VISIBLE
            toast("无法创建选取层: ${e.message}")
            openEditor()
            return
        }
        showPickerToast("拖动框选截图区域（红色虚线）")
    }

    /**
     * 截图命名对话框
     */
    private fun showScreenshotNameDialog(
        left: Int, top: Int, right: Int, bottom: Int,
        existingStep: ScriptStep?
    ) {
        val input = android.widget.EditText(this).apply {
            hint = "请输入图片名称（如：登录按钮、标题栏）"
            setText(existingStep?.imageName ?: "")
            setSingleLine()
            setPadding(dp2px(32), dp2px(24), dp2px(32), dp2px(24))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("命名截图")
            .setMessage("给这张截图起个名字，方便后续找图时复用")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("请输入名称")
                    floatingBall?.visibility = View.VISIBLE
                    openEditor()
                    return@setPositiveButton
                }
                // 执行截图并保存
                captureScreenshotRegion(left, top, right, bottom, name, existingStep)
            }
            .setNegativeButton("取消") { _, _ ->
                floatingBall?.visibility = View.VISIBLE
                openEditor()
            }
            .create()
            .apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
            .show()
    }

    /**
     * 截取指定区域并保存为命名图片
     */
    private fun captureScreenshotRegion(
        left: Int, top: Int, right: Int, bottom: Int,
        name: String,
        existingStep: ScriptStep?
    ) {
        val service = ScreenCaptureService.instance ?: return
        val script = ensureEditingScript()
        toast("正在截图...")

        Thread {
            try {
                Thread.sleep(300)
                val bitmap = service.captureScreen()
                if (bitmap == null) {
                    handler.post {
                        floatingBall?.visibility = View.VISIBLE
                        toast("截屏失败")
                        openEditor()
                    }
                    return@Thread
                }
                val cropLeft = left.coerceIn(0, bitmap.width - 1)
                val cropTop = top.coerceIn(0, bitmap.height - 1)
                val cropRight = right.coerceIn(cropLeft + 1, bitmap.width)
                val cropBottom = bottom.coerceIn(cropTop + 1, bitmap.height)
                val cropped = android.graphics.Bitmap.createBitmap(
                    bitmap, cropLeft, cropTop,
                    cropRight - cropLeft, cropBottom - cropTop
                )

                // 确保脚本已保存
                if (script.id.isBlank() || scriptManager.getScript(script.id) == null) {
                    script.name = script.name.ifEmpty { "未命名脚本" }
                    scriptManager.saveScript(script)
                }

                // 用用户命名保存
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val path = com.keyspirit.util.ScreenshotUtils.saveToProject(
                    this@FloatingWindowService, cropped, script.id, safeName
                )

                handler.post {
                    floatingBall?.visibility = View.VISIBLE
                    if (path != null) {
                        val step = existingStep ?: ScriptStep(type = StepType.SCREENSHOT)
                        step.imagePath = path
                        step.imageName = safeName
                        step.regionLeft = left
                        step.regionTop = top
                        step.regionRight = right
                        step.regionBottom = bottom
                        step.useRegion = true
                        if (existingStep == null) {
                            insertStep(step, pendingAddPath, pendingInsertAfter)
                        }
                        editorView?.refreshStepList()
                        toast("✓ 已保存: $safeName (${cropped.width}x${cropped.height})")
                        openEditor()
                    } else {
                        toast("保存失败")
                        openEditor()
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    floatingBall?.visibility = View.VISIBLE
                    toast("截图异常: ${e.message}")
                    openEditor()
                }
            }
        }.start()
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
                            return true
                        }
                        // 不恢复悬浮球可见性，captureRegionAndSave 会先截图再恢复
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
        showPickerToast("拖动框选找图区域，松开后自动截图")
    }

    /**
     * 截取指定区域并保存为找图目标图片
     */
    private fun captureRegionAndSave(left: Int, top: Int, right: Int, bottom: Int, existingStep: ScriptStep?) {
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
            // 在主线程先获取 script 引用，避免后台线程访问 editingScript 的竞态
            val script = ensureEditingScript()
            // 截图前隐藏所有悬浮元素
            floatingBall?.visibility = View.GONE
            pickerToastView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
                pickerToastView = null
            }
            toast("正在截图...")
            Log.d(TAG, "captureRegionAndSave: region=($left,$top)-($right,$bottom), script=${script.name}, steps=${script.steps.size}")

            Thread {
                try {
                    // 等屏幕刷新（遮罩层和悬浮球移除后）
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
                    // 裁剪区域
                    val cropLeft = left.coerceIn(0, bitmap.width - 1)
                    val cropTop = top.coerceIn(0, bitmap.height - 1)
                    val cropRight = right.coerceIn(cropLeft + 1, bitmap.width)
                    val cropBottom = bottom.coerceIn(cropTop + 1, bitmap.height)
                    val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop)
                    Log.d(TAG, "captureRegionAndSave: cropped=${cropped.width}x${cropped.height}")

                    // 确保当前脚本已保存（获取 scriptId）
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
                                insertStep(step, pendingAddPath, pendingInsertAfter)
                                Log.d(TAG, "Added FIND_IMAGE step, path=$pendingAddPath")
                            }
                            toast("✓ 已截图并保存 (${cropped.width}x${cropped.height})")
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
        try {
            windowManager.addView(overlay, params)
            pickerOverlay = overlay
        } catch (e: Exception) {
            Log.e(TAG, "startRegionPickForFindText: addView failed", e)
            floatingBall?.visibility = View.VISIBLE
            toast("无法创建选取层: ${e.message}")
            openEditor()
            return
        }
        showPickerToast("拖动框选查找文字的区域")
    }

    private fun showFindTextDialog(left: Int, top: Int, right: Int, bottom: Int, existingStep: ScriptStep?) {
        val input = android.widget.EditText(this).apply {
            hint = "输入要查找的文字"
            setPadding(dp2px(32), dp2px(16), dp2px(32), dp2px(16))
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
                        insertStep(step, pendingAddPath, pendingInsertAfter)
                        Log.d(TAG, "Added FIND_TEXT step, path=$pendingAddPath")
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
            setPadding(dp2px(32), dp2px(16), dp2px(32), dp2px(16))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("设置延迟")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val ms = input.text.toString().toLongOrNull() ?: 500
                val step = existingStep ?: ScriptStep(type = StepType.DELAY)
                step.delay = ms
                if (existingStep == null) {
                    insertStep(step, pendingAddPath, pendingInsertAfter)
                    Log.d(TAG, "Added DELAY step, path=$pendingAddPath")
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

    /**
     * 循环步骤设置对话框
     * 新逻辑：循环使用块结构，子步骤通过"+子"按钮添加到循环体内
     */
    private fun showLoopDialog(existingStep: ScriptStep?) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp2px(48), dp2px(32), dp2px(48), dp2px(16))
        }

        val tvCount = android.widget.TextView(this).apply {
            text = "循环次数（0=无限循环）"
            setTextColor(android.graphics.Color.parseColor("#999999"))
            textSize = 13f
        }
        layout.addView(tvCount)
        val etCount = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((existingStep?.loopCount ?: 3).toString())
            setSingleLine()
        }
        layout.addView(etCount)

        val tvHint = android.widget.TextView(this).apply {
            text = "提示：添加循环后，点击循环右侧的「+子」按钮，将步骤添加到循环体内"
            setTextColor(android.graphics.Color.parseColor("#999999"))
            textSize = 12f
            setPadding(0, dp2px(16), 0, 0)
        }
        layout.addView(tvHint)

        android.app.AlertDialog.Builder(this)
            .setTitle(if (existingStep == null) "添加循环" else "编辑循环")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val count = etCount.text.toString().toIntOrNull() ?: 3
                val step = existingStep ?: ScriptStep(type = StepType.LOOP)
                step.loopCount = count
                if (existingStep == null) {
                    insertStep(step, pendingAddPath, pendingInsertAfter)
                    Log.d(TAG, "Added LOOP step, path=$pendingAddPath, count=$count")
                    editorView?.clearSelection()
                    editorView?.refreshStepList()
                } else {
                    editorView?.refreshStepList()
                }
            }
            .setNegativeButton("取消", null)
            .create()
            .apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
            .show()
    }

    /**
     * 条件判断步骤设置对话框
     */
    private fun showIfDialog(existingStep: ScriptStep?) {
        val script = ensureEditingScript()

        val layout = android.widget.ScrollView(this)
        val innerLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp2px(48), dp2px(32), dp2px(48), dp2px(16))
        }
        layout.addView(innerLayout)

        // 条件类型选择
        val conditionOptions = arrayOf("找到图片", "找到文字", "找不到图片", "找不到文字")
        var selectedCondition = existingStep?.conditionType ?: 0
        // 条件块内的子步骤（编辑时复制一份，确定后再写回）
        val ifStepsCopy = (existingStep?.ifSteps?.map { it.copy() }?.toMutableList()
            ?: mutableListOf())

        val tvCondition = android.widget.TextView(this).apply {
            text = "条件类型"
            setTextColor(android.graphics.Color.parseColor("#999999"))
            textSize = 13f
        }
        innerLayout.addView(tvCondition)

        val btnCondition = android.widget.Button(this).apply {
            text = conditionOptions[selectedCondition]
        }
        innerLayout.addView(btnCondition)

        // ---- 找图相关字段 ----
        val tvImg = android.widget.TextView(this).apply {
            text = "目标图片路径"
            setTextColor(android.graphics.Color.parseColor("#999999"))
            textSize = 13f
            setPadding(0, dp2px(16), 0, dp2px(4))
        }
        innerLayout.addView(tvImg)
        val etImgPath = android.widget.EditText(this).apply {
            setText(existingStep?.conditionImagePath ?: "")
            hint = "选图后自动填充"
            setSingleLine()
        }
        innerLayout.addView(etImgPath)

        val btnPickImg = android.widget.Button(this).apply {
            text = "从项目目录选择图片"
        }
        innerLayout.addView(btnPickImg)

        val tvSim = android.widget.TextView(this).apply {
            text = "相似度 (0-1)"
            setTextColor(android.graphics.Color.parseColor("#999999"))
            textSize = 13f
            setPadding(0, dp2px(16), 0, dp2px(4))
        }
        innerLayout.addView(tvSim)
        val etSim = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_CLASS_NUMBER
            setText((existingStep?.conditionSimilarity ?: 0.9).toString())
            setSingleLine()
        }
        innerLayout.addView(etSim)
        // ---- 找图相关字段结束 ----

        // ---- 找文字相关字段 ----
        val tvText = android.widget.TextView(this).apply {
            text = "目标文字"
            setTextColor(android.graphics.Color.parseColor("#999999"))
            textSize = 13f
            setPadding(0, dp2px(16), 0, dp2px(4))
        }
        innerLayout.addView(tvText)
        val etText = android.widget.EditText(this).apply {
            setText(existingStep?.conditionText ?: "")
            hint = "输入要查找的文字"
            setSingleLine()
        }
        innerLayout.addView(etText)
        // ---- 找文字相关字段结束 ----

        // 超时时间（共用）
        val tvTimeout = android.widget.TextView(this).apply {
            text = "超时时间(ms)"
            setTextColor(android.graphics.Color.parseColor("#999999"))
            textSize = 13f
            setPadding(0, dp2px(16), 0, dp2px(4))
        }
        innerLayout.addView(tvTimeout)
        val etTimeout = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((existingStep?.conditionTimeout ?: 2000).toString())
            setSingleLine()
        }
        innerLayout.addView(etTimeout)

        // ---- 块内步骤管理 ----
        val tvBlock = android.widget.TextView(this).apply {
            text = "条件成立时执行的步骤"
            setTextColor(android.graphics.Color.parseColor("#999999"))
            textSize = 13f
            setPadding(0, dp2px(16), 0, dp2px(4))
        }
        innerLayout.addView(tvBlock)

        val btnManageSteps = android.widget.Button(this).apply {
            text = "管理块内步骤（${ifStepsCopy.size}个）"
        }
        innerLayout.addView(btnManageSteps)

        val tvHint = android.widget.TextView(this).apply {
            text = "条件成立时，按顺序执行块内的所有步骤；不成立则全部跳过"
            setTextColor(android.graphics.Color.parseColor("#999999"))
            textSize = 12f
            setPadding(0, dp2px(8), 0, 0)
        }
        innerLayout.addView(tvHint)
        // ---- 块内步骤管理结束 ----

        // 根据条件类型更新字段可见性
        fun updateVisibility(condType: Int) {
            val isImageType = condType == 0 || condType == 2
            val isTextType = condType == 1 || condType == 3
            val imgVisibility = if (isImageType) View.VISIBLE else View.GONE
            val textVisibility = if (isTextType) View.VISIBLE else View.GONE

            tvImg.visibility = imgVisibility
            etImgPath.visibility = imgVisibility
            btnPickImg.visibility = imgVisibility
            tvSim.visibility = imgVisibility
            etSim.visibility = imgVisibility

            tvText.visibility = textVisibility
            etText.visibility = textVisibility
        }

        // 刷新块内步骤数量显示
        fun refreshBlockCount() {
            btnManageSteps.text = "管理块内步骤（${ifStepsCopy.size}个）"
        }

        // 条件类型选择按钮点击
        btnCondition.setOnClickListener {
            try {
                android.app.AlertDialog.Builder(this@FloatingWindowService)
                    .setTitle("选择条件类型")
                    .setItems(conditionOptions) { _, which ->
                        selectedCondition = which
                        btnCondition.text = conditionOptions[which]
                        updateVisibility(which)
                    }
                    .create()
                    .apply {
                        window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    }
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "条件类型选择按钮异常", e)
                toast("操作失败: ${e.message}")
            }
        }

        // 选图按钮点击
        btnPickImg.setOnClickListener {
            try {
                val scriptId = script.id
                val screenshots = com.keyspirit.util.ScreenshotUtils.listProjectScreenshots(this@FloatingWindowService, scriptId)
                if (screenshots.isEmpty()) {
                    android.app.AlertDialog.Builder(this@FloatingWindowService)
                        .setTitle("提示")
                        .setMessage("项目目录下还没有截图\n\n请先返回悬浮窗，使用「截图」功能截取目标图片后再试")
                        .setPositiveButton("知道了", null)
                        .create()
                        .apply {
                            window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                        }
                        .show()
                    return@setOnClickListener
                }
                val fileNames = screenshots.map { it.name }.toTypedArray()
                android.app.AlertDialog.Builder(this@FloatingWindowService)
                    .setTitle("选择图片")
                    .setItems(fileNames) { _, which ->
                        etImgPath.setText(screenshots[which].absolutePath)
                    }
                    .create()
                    .apply {
                        window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    }
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "选图按钮异常", e)
                android.app.AlertDialog.Builder(this@FloatingWindowService)
                    .setTitle("选图失败")
                    .setMessage(e.message ?: "未知错误")
                    .setPositiveButton("确定", null)
                    .create()
                    .apply {
                        window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    }
                    .show()
            }
        }

        // 管理块内步骤按钮点击
        btnManageSteps.setOnClickListener {
            try {
                showIfBlockStepsDialog(ifStepsCopy) {
                    refreshBlockCount()
                }
            } catch (e: Exception) {
                Log.e(TAG, "管理块内步骤异常", e)
                toast("操作失败: ${e.message}")
            }
        }

        // 初始化可见性
        updateVisibility(selectedCondition)

        android.app.AlertDialog.Builder(this)
            .setTitle(if (existingStep == null) "添加条件判断" else "编辑条件判断")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                try {
                    val step = existingStep ?: ScriptStep(type = StepType.IF)
                    step.conditionType = selectedCondition
                    step.conditionImagePath = etImgPath.text.toString()
                    step.conditionText = etText.text.toString()
                    step.conditionSimilarity = etSim.text.toString().toDoubleOrNull() ?: 0.9
                    step.conditionTimeout = etTimeout.text.toString().toLongOrNull() ?: 2000
                    step.ifSteps = ifStepsCopy

                    if (existingStep == null) {
                        insertStep(step, pendingAddPath, pendingInsertAfter)
                    }
                    editorView?.refreshStepList()
                    toast("已保存条件判断步骤")
                } catch (e: Exception) {
                    Log.e(TAG, "保存条件判断失败", e)
                    toast("保存失败: ${e.message}")
                }
            }
            .setNegativeButton("取消", null)
            .create()
            .apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
            .show()
    }

    /**
     * 显示 IF 块内子步骤管理对话框
     */
    private fun showIfBlockStepsDialog(ifSteps: MutableList<ScriptStep>, onChanged: () -> Unit) {
        val stepTypesForBlock = arrayOf(
            StepType.CLICK, StepType.LONG_PRESS,
            StepType.TOUCH_DOWN, StepType.TOUCH_UP,
            StepType.RIGHT_CLICK, StepType.RIGHT_CLICK_DOWN, StepType.RIGHT_CLICK_UP,
            StepType.SWIPE,
            StepType.DELAY, StepType.FIND_IMAGE, StepType.FIND_TEXT
        )
        val typeNames = stepTypesForBlock.map { it.displayName }.toTypedArray()

        // 构建步骤列表显示
        fun buildStepsText(): String {
            if (ifSteps.isEmpty()) return "（暂无步骤，点击下方添加）"
            return ifSteps.mapIndexed { i, step ->
                "${i + 1}. ${step.getDescription()}"
            }.joinToString("\n")
        }

        val tvSteps = android.widget.TextView(this).apply {
            text = buildStepsText()
            setPadding(dp2px(24), dp2px(16), dp2px(24), dp2px(16))
            textSize = 13f
            setTextColor(android.graphics.Color.BLACK)
        }
        val scrollView = android.widget.ScrollView(this).apply {
            addView(tvSteps)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("条件块内步骤（${ifSteps.size}个）")
            .setView(scrollView)
            .setPositiveButton("完成", null)
            .setNeutralButton("添加步骤") { _, _ ->
                // 弹出步骤类型选择
                android.app.AlertDialog.Builder(this@FloatingWindowService)
                    .setTitle("选择步骤类型")
                    .setItems(typeNames) { _, which ->
                        val type = stepTypesForBlock[which]
                        // 复用 handleAddStep 逻辑，但把步骤加到 ifSteps 里
                        when (type) {
                            StepType.CLICK, StepType.LONG_PRESS,
                            StepType.TOUCH_DOWN, StepType.TOUCH_UP,
                            StepType.RIGHT_CLICK, StepType.RIGHT_CLICK_DOWN, StepType.RIGHT_CLICK_UP -> {
                                startCoordinatePickForIfBlock(type, ifSteps) {
                                    tvSteps.text = buildStepsText()
                                    onChanged()
                                }
                            }
                            StepType.SWIPE -> {
                                startSwipePickForIfBlock(type, ifSteps) {
                                    tvSteps.text = buildStepsText()
                                    onChanged()
                                }
                            }
                            StepType.DELAY -> {
                                ifSteps.add(ScriptStep(type = StepType.DELAY, delay = 500))
                                tvSteps.text = buildStepsText()
                                onChanged()
                                toast("已添加延迟步骤")
                            }
                            StepType.FIND_IMAGE, StepType.FIND_TEXT -> {
                                ifSteps.add(
                                    ScriptStep(
                                        type = type,
                                        imagePath = if (type == StepType.FIND_IMAGE) "" else "",
                                        text = if (type == StepType.FIND_TEXT) "" else "",
                                        similarity = 0.9,
                                        findTimeout = 2000
                                    )
                                )
                                tvSteps.text = buildStepsText()
                                onChanged()
                                toast("已添加${type.displayName}步骤")
                            }
                            else -> {
                                ifSteps.add(ScriptStep(type = type))
                                tvSteps.text = buildStepsText()
                                onChanged()
                            }
                        }
                    }
                    .create()
                    .apply {
                        window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    }
                    .show()
            }
            .setNegativeButton("清空全部") { _, _ ->
                ifSteps.clear()
                tvSteps.text = buildStepsText()
                onChanged()
                toast("已清空块内步骤")
            }
            .create()
            .apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
        dialog.show()
    }

    /**
     * 为 IF 块选取坐标并添加步骤
     */
    private fun startCoordinatePickForIfBlock(
        type: StepType,
        ifSteps: MutableList<ScriptStep>,
        onDone: () -> Unit
    ) {
        // 隐藏悬浮球和编辑器
        floatingBall?.visibility = View.GONE
        closeEditor()

        val overlay = android.view.View(this).apply {
            setBackgroundColor(0x66000000.toInt())
            isClickable = true
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
            pickerOverlay = overlay
        } catch (e: Exception) {
            Log.e(TAG, "startCoordinatePickForIfBlock: addView failed", e)
            floatingBall?.visibility = View.VISIBLE
            openEditor()
            toast("无法创建选取层: ${e.message}")
            return
        }
        showPickerToast("点击屏幕选取${type.displayName}坐标")

        overlay.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                ifSteps.add(ScriptStep(type = type, x = x, y = y))
                // 清理
                try { windowManager.removeView(overlay) } catch (_: Exception) {}
                pickerOverlay = null
                hidePickerToast()
                floatingBall?.visibility = View.VISIBLE
                onDone()
                openEditor()
                toast("已添加${type.displayName}步骤 ($x, $y)")
                true
            } else false
        }
    }

    /**
     * 为 IF 块选取滑动坐标并添加步骤
     */
    private fun startSwipePickForIfBlock(
        type: StepType,
        ifSteps: MutableList<ScriptStep>,
        onDone: () -> Unit
    ) {
        floatingBall?.visibility = View.GONE
        closeEditor()
        showSwipePickerOverlay(
            onComplete = { x1, y1, x2, y2 ->
                ifSteps.add(
                    ScriptStep(
                        type = type,
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        duration = 300
                    )
                )
                floatingBall?.visibility = View.VISIBLE
                onDone()
                openEditor()
                toast("已添加滑动步骤")
            },
            onCancel = {
                floatingBall?.visibility = View.VISIBLE
                openEditor()
            }
        )
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
        val indicatorSize = resources.getDimensionPixelSize(R.dimen.touch_indicator_size)
        container.addView(touchIndicator, android.widget.FrameLayout.LayoutParams(indicatorSize, indicatorSize))

        // 坐标文本显示（屏幕左上角，实时显示 rawX/rawY）
        coordTextView = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 14f
            setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8))
            text = "触摸坐标: (-, -)"
        }
        container.addView(coordTextView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp2px(16)
            topMargin = dp2px(16)
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
        try {
            windowManager.addView(recordingOverlay, params)
        } catch (e: Exception) {
            Log.e(TAG, "showRecordingOverlay: addView failed", e)
            recordingOverlay = null
            toast("无法显示录制覆盖层: ${e.message}")
            return
        }
        // 把悬浮球提到最上层，确保可以点击停止
        floatingBall?.let { ball ->
            try {
                windowManager.removeView(ball)
                windowManager.addView(ball, ball.layoutParams)
            } catch (e: Exception) {
                Log.e(TAG, "showRecordingOverlay: re-add ball failed", e)
            }
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
        val scripts = scriptManager.getAllScripts().sortedByDescending { it.updatedAt }
        if (scripts.isEmpty()) {
            toast("没有可用脚本，请先创建或录制一个脚本")
            return
        }
        val scriptNames = scripts.map { "${it.name} (${it.stepCount()}步)" }.toTypedArray()
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("选择要运行的脚本")
            .setItems(scriptNames) { _, which ->
                val script = scripts[which]
                startExecution(script)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
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
                // 更新鼠标指示器位置
                when (step.type) {
                    StepType.CLICK, StepType.TOUCH_DOWN, StepType.TOUCH_UP,
                    StepType.RIGHT_CLICK, StepType.RIGHT_CLICK_DOWN, StepType.RIGHT_CLICK_UP,
                    StepType.LONG_PRESS, StepType.MOVE_MOUSE, StepType.PICK_POINT -> {
                        updateMouseIndicator(step.x, step.y)
                    }
                    StepType.SWIPE -> {
                        updateMouseIndicator(step.x1, step.y1)
                    }
                    else -> {}
                }
            }
            override fun onStepComplete(index: Int, step: com.keyspirit.script.ScriptStep) {
                // 滑动完成后更新到终点位置
                if (step.type == StepType.SWIPE) {
                    updateMouseIndicator(step.x2, step.y2)
                }
            }
            override fun onLoopUpdate(currentLoop: Int, totalLoops: Int) {
                val total = if (totalLoops <= 0) "∞" else totalLoops.toString()
                updatePanelInfo("循环: $currentLoop/$total")
            }
            override fun onComplete() {
                isExecuting = false
                scriptExecutor = null
                hideMouseIndicator()
                hidePanel()
                floatingBall?.state = FloatingBallView.BallState.IDLE
                toast("脚本执行完成")
                scriptManager.markRun(script.id)
            }
            override fun onError(message: String) {
                isExecuting = false
                scriptExecutor = null
                hideMouseIndicator()
                hidePanel()
                floatingBall?.state = FloatingBallView.BallState.IDLE
                // 用对话框显示错误，让用户能清楚看到
                showAlertDialog(
                    title = "执行出错",
                    message = message,
                    positive = "知道了"
                )
            }
        })
        // 应用防检测设置
        val prefs = getSharedPreferences("keyspirit_settings", Context.MODE_PRIVATE)
        scriptExecutor?.setAntiDetect(
            prefs.getBoolean("random_delay", false),
            prefs.getBoolean("coord_offset", false)
        )
        floatingBall?.state = FloatingBallView.BallState.EXECUTING
        showPanel()
        showMouseIndicator() // 显示鼠标指示器
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
        hideMouseIndicator()
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
        try {
            windowManager.addView(overlay, params)
            pickerOverlay = overlay
        } catch (e: Exception) {
            Log.e(TAG, "showCoordinateOverlay: addView failed", e)
            floatingBall?.visibility = View.VISIBLE
            toast("无法创建坐标选取层: ${e.message}")
            return
        }
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
                        try {
                            windowManager.addView(regionView, rParams)
                        } catch (e: Exception) {
                            Log.e(TAG, "showRegionOverlay: addView regionView failed", e)
                        }
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
        try {
            windowManager.addView(overlay, params)
            pickerOverlay = overlay
        } catch (e: Exception) {
            Log.e(TAG, "showRegionOverlay: addView failed", e)
            floatingBall?.visibility = View.VISIBLE
            toast("无法创建区域选取层: ${e.message}")
            return
        }
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
            setPadding(dp2px(40), dp2px(20), dp2px(40), dp2px(20))
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
        try {
            windowManager.addView(tv, params)
            pickerToastView = tv
        } catch (e: Exception) {
            Log.e(TAG, "showPickerToast: addView failed", e)
        }
        // 自动消失（仅对非实时提示）
        handler.postDelayed({
            pickerToastView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
                pickerToastView = null
            }
        }, 3000)
    }

    private fun hidePickerToast() {
        pickerToastView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            pickerToastView = null
        }
    }

    /**
     * 通用滑动坐标选取覆盖层：依次选起点和终点，完成后回调
     */
    private fun showSwipePickerOverlay(
        onComplete: (Int, Int, Int, Int) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        var swipeState = 0 // 0=选起点, 1=选终点
        var startX = 0
        var startY = 0

        val overlay = View(this).apply {
            setBackgroundColor(0x33000000)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        val msg = if (swipeState == 0) "选起点" else "选终点"
                        showPickerToast("$msg: X: ${event.rawX.toInt()}, Y: ${event.rawY.toInt()}（松开确认）")
                    }
                    MotionEvent.ACTION_UP -> {
                        val x = event.rawX.toInt()
                        val y = event.rawY.toInt()
                        if (swipeState == 0) {
                            startX = x
                            startY = y
                            swipeState = 1
                            showPickerToast("已选起点，请选取终点")
                        } else {
                            removePickerOverlay()
                            hidePickerToast()
                            onComplete(startX, startY, x, y)
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
        try {
            windowManager.addView(overlay, params)
            pickerOverlay = overlay
        } catch (e: Exception) {
            Log.e(TAG, "showSwipePickerOverlay: addView failed", e)
            onCancel()
            return
        }
        showPickerToast("请选取滑动起点")
    }

    private fun takeScreenshot() {
        // 预检查：截屏服务必须运行
        if (!com.keyspirit.service.ScreenCaptureService.isRunning()) {
            val state = com.keyspirit.service.ScreenCaptureService.getState()
            val error = com.keyspirit.service.ScreenCaptureService.getError()
            val message = buildString {
                append("截图功能需要截屏权限。")
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
            )
            return
        }

        // 隐藏面板和悬浮球，准备区域选取
        val wasPanelVisible = isPanelVisible
        hidePanel()
        floatingBall?.visibility = View.GONE
        pickerToastView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            pickerToastView = null
        }

        // 显示区域选取覆盖层（虚线框选择截图区域）
        val overlay = object : View(this) {
            private var startX = 0f
            private var startY = 0f
            private var curX = 0f
            private var curY = 0f
            private var dragging = false
            private val dashPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#E74C3C")
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
                        pickerOverlay = null

                        if (right - left < 20 || bottom - top < 20) {
                            // 区域太小，恢复界面
                            floatingBall?.visibility = View.VISIBLE
                            if (wasPanelVisible) {
                                showPanel()
                            }
                            toast("区域太小，请重新选择")
                            return true
                        }
                        // 弹出命名对话框
                        showStandaloneScreenshotNameDialog(left, top, right, bottom, wasPanelVisible)
                    }
                }
                return true
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
            pickerOverlay = overlay
        } catch (e: Exception) {
            floatingBall?.visibility = View.VISIBLE
            if (wasPanelVisible) {
                showPanel()
            }
            toast("无法显示选取层: ${e.message}")
        }

        // 提示用户
        toast("请拖动选择截图区域")
    }

    /**
     * 独立截图的命名对话框（不关联编辑器，直接保存到项目）
     */
    private fun showStandaloneScreenshotNameDialog(
        left: Int, top: Int, right: Int, bottom: Int,
        wasPanelVisible: Boolean
    ) {
        val input = android.widget.EditText(this).apply {
            hint = "请输入图片名称（如：登录按钮、标题栏）"
            setSingleLine()
            setPadding(dp2px(32), dp2px(24), dp2px(32), dp2px(24))
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("命名截图")
            .setMessage("给这张截图起个名字，方便后续找图时复用")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("请输入名称")
                    floatingBall?.visibility = View.VISIBLE
                    if (wasPanelVisible) {
                        showPanel()
                    }
                    return@setPositiveButton
                }
                // 执行截图并保存
                captureStandaloneScreenshot(left, top, right, bottom, name, wasPanelVisible)
            }
            .setNegativeButton("取消") { _, _ ->
                floatingBall?.visibility = View.VISIBLE
                if (wasPanelVisible) {
                    showPanel()
                }
            }
            .create()
            .apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                setCancelable(false)
            }
            .show()
    }

    /**
     * 截取指定区域并保存为命名图片（独立截图，不关联脚本步骤）
     */
    private fun captureStandaloneScreenshot(
        left: Int, top: Int, right: Int, bottom: Int,
        name: String,
        wasPanelVisible: Boolean
    ) {
        val service = com.keyspirit.service.ScreenCaptureService.instance ?: run {
            floatingBall?.visibility = View.VISIBLE
            if (wasPanelVisible) {
                showPanel()
            }
            toast("截屏服务异常")
            return
        }
        toast("正在截图...")

        Thread {
            try {
                Thread.sleep(300)
                val bitmap = service.captureScreen()
                if (bitmap == null) {
                    handler.post {
                        floatingBall?.visibility = View.VISIBLE
                        if (wasPanelVisible) {
                            showPanel()
                        }
                        showAlertDialog(
                            title = "截图失败",
                            message = "截屏返回为空图片。\n\n诊断信息：${service.getDiagnosticInfo()}",
                            positive = "知道了"
                        )
                    }
                    return@Thread
                }
                val cropLeft = left.coerceIn(0, bitmap.width - 1)
                val cropTop = top.coerceIn(0, bitmap.height - 1)
                val cropRight = right.coerceIn(cropLeft + 1, bitmap.width)
                val cropBottom = bottom.coerceIn(cropTop + 1, bitmap.height)
                val cropped = android.graphics.Bitmap.createBitmap(
                    bitmap, cropLeft, cropTop,
                    cropRight - cropLeft, cropBottom - cropTop
                )

                // 确保有当前项目，没有则创建默认项目
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

                // 用用户命名保存
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val path = com.keyspirit.util.ScreenshotUtils.saveToProject(
                    this@FloatingWindowService, cropped, scriptId, safeName
                )

                handler.post {
                    floatingBall?.visibility = View.VISIBLE
                    if (wasPanelVisible) {
                        showPanel()
                    }
                    if (path != null) {
                        toast("✓ 已保存到【$scriptName】: $safeName (${cropped.width}x${cropped.height})")
                    } else {
                        showAlertDialog(
                            title = "截图保存失败",
                            message = "图片写入失败，可能是存储空间不足。",
                            positive = "知道了"
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FloatingWindow", "独立截图异常", e)
                handler.post {
                    floatingBall?.visibility = View.VISIBLE
                    if (wasPanelVisible) {
                        showPanel()
                    }
                    showAlertDialog(
                        title = "截图异常",
                        message = "截图过程中发生异常：${e.message}\n\n${e.stackTrace.take(3).joinToString("\n") { it.toString() }}",
                        positive = "知道了"
                    )
                }
            }
        }.start()
    }

    // ============ 鼠标指示器 ============

    /**
     * 显示鼠标位置指示器
     */
    private fun showMouseIndicator() {
        if (!showMouseIndicator) return
        if (mouseIndicator != null) return
        mouseIndicator = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E74C3C"))
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(resources.getDimensionPixelSize(R.dimen.indicator_stroke_width), android.graphics.Color.parseColor("#FFFFFF"))
            }
            elevation = resources.getDimensionPixelSize(R.dimen.indicator_elevation).toFloat()
        }
        val size = resources.getDimensionPixelSize(R.dimen.mouse_indicator_size)
        val params = createOverlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.addView(mouseIndicator, params)
        } catch (e: Exception) {
            Log.e(TAG, "showMouseIndicator: addView failed", e)
            mouseIndicator = null
        }
    }

    /**
     * 隐藏鼠标位置指示器
     */
    private fun hideMouseIndicator() {
        mouseIndicator?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        mouseIndicator = null
    }

    /**
     * 更新鼠标指示器位置
     */
    private fun updateMouseIndicator(x: Int, y: Int) {
        val indicator = mouseIndicator ?: return
        val params = indicator.layoutParams as? WindowManager.LayoutParams ?: return
        // 居中显示：指示器中心对齐坐标点
        val indicatorSize = resources.getDimensionPixelSize(R.dimen.mouse_indicator_size)
        val halfIndicator = indicatorSize / 2
        params.x = x - halfIndicator
        params.y = y - halfIndicator
        try {
            windowManager.updateViewLayout(indicator, params)
        } catch (_: Exception) {}
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

    /**
     * 将 dp 值转换为像素值，用于代码中需要以 dp 为单位设置尺寸的场景
     */
    private fun dp2px(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
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
