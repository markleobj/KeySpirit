package com.keyspirit.script

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.keyspirit.service.AutoAccessibilityService
import com.keyspirit.util.ImageMatcher
import com.keyspirit.util.OcrHelper

class ScriptExecutor(
    private val script: Script,
    private val listener: ExecutionListener
) {
    companion object {
        private const val TAG = "ScriptExecutor"

        fun isRandomDelayEnabled(context: android.content.Context): Boolean {
            return context.getSharedPreferences("keyspirit_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("random_delay", false)
        }

        fun isCoordOffsetEnabled(context: android.content.Context): Boolean {
            return context.getSharedPreferences("keyspirit_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("coord_offset", false)
        }
    }

    interface ExecutionListener {
        fun onStepStart(index: Int, step: ScriptStep)
        fun onStepComplete(index: Int, step: ScriptStep)
        fun onLoopUpdate(currentLoop: Int, totalLoops: Int)
        fun onComplete()
        fun onError(message: String)
    }

    @Volatile
    private var isRunning = false

    @Volatile
    private var isPaused = false

    private val handler = Handler(Looper.getMainLooper())
    private var currentThread: Thread? = null

    // 防检测设置
    private var useRandomDelay = false
    private var useCoordOffset = false

    fun setAntiDetect(randomDelay: Boolean, coordOffset: Boolean) {
        useRandomDelay = randomDelay
        useCoordOffset = coordOffset
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        isPaused = false
        currentThread = Thread {
            try {
                execute()
            } catch (e: InterruptedException) {
                Log.d(TAG, "执行被中断")
            } catch (e: Exception) {
                Log.e(TAG, "执行出错", e)
                handler.post { listener.onError(e.message ?: "未知错误") }
            } catch (e: Throwable) {
                Log.e(TAG, "执行发生严重错误", e)
                handler.post { listener.onError("严重错误: ${e.message ?: e::class.java.simpleName}") }
            }
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                Log.e(TAG, "线程未捕获异常", throwable)
                handler.post { listener.onError("未捕获异常: ${throwable.message ?: throwable::class.java.simpleName}") }
            }
            start()
        }
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    fun stop() {
        isRunning = false
        currentThread?.interrupt()
    }

    fun isRunning(): Boolean = isRunning
    fun isPaused(): Boolean = isPaused

    private fun execute() {
        val service = AutoAccessibilityService.instance
        if (service == null) {
            handler.post { listener.onError("无障碍服务未开启") }
            return
        }

        val totalLoops = if (script.loopCount <= 0) Int.MAX_VALUE else script.loopCount
        var loop = 0

        while (isRunning && loop < totalLoops) {
            loop++
            val finalLoop = loop
            handler.post { listener.onLoopUpdate(finalLoop, script.loopCount) }

            executeSteps(script.steps, service)

            if (!isRunning) break

            // 循环间隔
            if (script.loopInterval > 0) {
                sleep(script.loopInterval)
            }
        }

        handler.post { listener.onComplete() }
    }

    private fun executeSteps(steps: List<ScriptStep>, service: AutoAccessibilityService) {
        var i = 0
        while (i < steps.size && isRunning) {
            // 暂停等待
            while (isPaused && isRunning) {
                sleep(100)
            }
            if (!isRunning) break

            val step = steps[i]
            val finalIndex = i
            handler.post { listener.onStepStart(finalIndex, step) }

            val result = executeStep(step, service, steps, i)
            handler.post { listener.onStepComplete(finalIndex, step) }

            if (!result.success) {
                Log.w(TAG, "步骤执行失败: ${step.getDescription()}")
            }

            // 防检测：步骤间随机延迟
            if (useRandomDelay) {
                val randomMs = 50 + (Math.random() * 200).toLong()
                sleep(randomMs)
            }

            i++
        }
    }

    /**
     * 步骤执行结果
     * @param success 是否执行成功
     * @param jumpTo 跳转到的步骤索引（-1 表示继续下一步）
     */
    private data class StepResult(val success: Boolean, val jumpTo: Int = -1)

    private fun executeStep(
        step: ScriptStep,
        service: AutoAccessibilityService,
        allSteps: List<ScriptStep>,
        currentIndex: Int
    ): StepResult {
        return try {
            executeStepInternal(step, service, allSteps, currentIndex)
        } catch (e: Exception) {
            Log.e(TAG, "步骤 ${step.type.displayName} 执行异常: ${e.message}", e)
            handler.post { listener.onError("步骤${currentIndex + 1}(${step.type.displayName})执行异常: ${e.message}") }
            StepResult(false)
        }
    }

    private fun executeStepInternal(
        step: ScriptStep,
        service: AutoAccessibilityService,
        allSteps: List<ScriptStep>,
        currentIndex: Int
    ): StepResult {
        return when (step.type) {
            StepType.CLICK -> {
                val (ox, oy) = applyOffset(step.x, step.y)
                performClick(service, ox, oy)
                StepResult(true)
            }
            StepType.TOUCH_DOWN -> {
                val (ox, oy) = applyOffset(step.x, step.y)
                performTouchDown(service, ox, oy, step.duration)
                StepResult(true)
            }
            StepType.TOUCH_UP -> {
                // touch up 通过 click 的快速释放实现
                val (ox, oy) = applyOffset(step.x, step.y)
                performClick(service, ox, oy)
                StepResult(true)
            }
            StepType.RIGHT_CLICK -> {
                // 右键点击 = 长按一次（模拟很多游戏的右键）
                val pressDuration = if (step.duration > 0) step.duration else 300L
                val (ox, oy) = applyOffset(step.x, step.y)
                performTouchDown(service, ox, oy, pressDuration)
                StepResult(true)
            }
            StepType.RIGHT_CLICK_DOWN -> {
                // 右键按下 = 长按模拟（很多游戏把长按当右键）
                val pressDuration = if (step.duration > 0) step.duration else 300L
                val (ox, oy) = applyOffset(step.x, step.y)
                performTouchDown(service, ox, oy, pressDuration)
                StepResult(true)
            }
            StepType.RIGHT_CLICK_UP -> {
                // 右键抬起 = 快速点击释放
                val (ox, oy) = applyOffset(step.x, step.y)
                performClick(service, ox, oy)
                StepResult(true)
            }
            StepType.SWIPE -> {
                val (ox1, oy1) = applyOffset(step.x1, step.y1)
                val (ox2, oy2) = applyOffset(step.x2, step.y2)
                performSwipe(service, ox1, oy1, ox2, oy2, step.duration)
                StepResult(true)
            }
            StepType.LONG_PRESS -> {
                val (ox, oy) = applyOffset(step.x, step.y)
                performLongPress(service, ox, oy, step.duration)
                StepResult(true)
            }
            StepType.SCREENSHOT -> {
                // 执行截图（截取指定区域或全屏）
                val captureService = com.keyspirit.service.ScreenCaptureService.instance
                if (captureService != null) {
                    val bitmap = captureService.captureScreen()
                    if (bitmap != null && script.id.isNotBlank()) {
                        val cropped = if (step.useRegion) {
                            val cropLeft = step.regionLeft.coerceIn(0, bitmap.width - 1)
                            val cropTop = step.regionTop.coerceIn(0, bitmap.height - 1)
                            val cropRight = step.regionRight.coerceIn(cropLeft + 1, bitmap.width)
                            val cropBottom = step.regionBottom.coerceIn(cropTop + 1, bitmap.height)
                            android.graphics.Bitmap.createBitmap(
                                bitmap, cropLeft, cropTop,
                                cropRight - cropLeft, cropBottom - cropTop
                            )
                        } else bitmap
                        val name = step.imageName.ifEmpty { null }
                        com.keyspirit.util.ScreenshotUtils.saveToProject(
                            service, cropped, script.id, name
                        )
                    }
                }
                StepResult(true)
            }
            StepType.DELAY -> {
                val delay = if (step.randomDelay > 0) {
                    step.delay + (Math.random() * step.randomDelay).toLong()
                } else {
                    step.delay
                }
                sleep(delay)
                StepResult(true)
            }
            StepType.FIND_IMAGE -> {
                val found = findAndClickImage(step)
                StepResult(found)
            }
            StepType.FIND_TEXT -> {
                val found = findAndClickText(step)
                StepResult(found)
            }
            StepType.LOOP -> {
                executeLoop(step, allSteps, service)
                StepResult(true)
            }
            StepType.IF -> {
                executeIfBlock(step, service)
                StepResult(true)
            }
        }
    }

    private fun performClick(service: AutoAccessibilityService, x: Int, y: Int) {
        val lock = Object()
        var done = false
        service.click(x, y, object : AutoAccessibilityService.GestureCallback {
            override fun onCompleted() {
                synchronized(lock) { done = true; lock.notifyAll() }
            }
            override fun onCancelled() {
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        })
        synchronized(lock) {
            while (!done) lock.wait(3000)
        }
        sleep(50) // 手势完成后短暂等待
    }

    private fun performTouchDown(service: AutoAccessibilityService, x: Int, y: Int, duration: Long) {
        val lock = Object()
        var done = false
        service.touchDown(x, y, duration, object : AutoAccessibilityService.GestureCallback {
            override fun onCompleted() {
                synchronized(lock) { done = true; lock.notifyAll() }
            }
            override fun onCancelled() {
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        })
        synchronized(lock) {
            while (!done) lock.wait(duration + 3000)
        }
    }

    private fun performSwipe(service: AutoAccessibilityService, x1: Int, y1: Int, x2: Int, y2: Int, duration: Long) {
        val lock = Object()
        var done = false
        service.swipe(x1, y1, x2, y2, duration, object : AutoAccessibilityService.GestureCallback {
            override fun onCompleted() {
                synchronized(lock) { done = true; lock.notifyAll() }
            }
            override fun onCancelled() {
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        })
        synchronized(lock) {
            while (!done) lock.wait(duration + 3000)
        }
        sleep(50)
    }

    private fun performLongPress(service: AutoAccessibilityService, x: Int, y: Int, duration: Long) {
        performTouchDown(service, x, y, duration)
    }

    private fun findAndClickImage(step: ScriptStep): Boolean {
        val matcher = ImageMatcher.instance ?: return false
        val region = if (step.useRegion) {
            android.graphics.Rect(step.regionLeft, step.regionTop, step.regionRight, step.regionBottom)
        } else null
        val result = matcher.findImage(step.imagePath, step.similarity, step.findTimeout, region)
        if (result != null) {
            val service = AutoAccessibilityService.instance ?: return false
            performClick(service, result.x, result.y)
            return true
        }
        return false
    }

    private fun findAndClickText(step: ScriptStep): Boolean {
        val ocr = OcrHelper.instance ?: return false
        val region = if (step.useRegion) {
            android.graphics.Rect(step.regionLeft, step.regionTop, step.regionRight, step.regionBottom)
        } else null
        val result = ocr.findText(step.text, step.findTimeout, region)
        if (result != null) {
            val service = AutoAccessibilityService.instance ?: return false
            performClick(service, result.x, result.y)
            return true
        }
        return false
    }

    private fun executeLoop(step: ScriptStep, allSteps: List<ScriptStep>, service: AutoAccessibilityService) {
        // 边界安全检查：步骤列表为空时直接返回
        if (allSteps.isEmpty()) {
            Log.w(TAG, "executeLoop: 步骤列表为空，跳过循环")
            return
        }
        val start = step.loopStartIndex.coerceIn(0, allSteps.size - 1)
        val end = step.loopEndIndex.coerceIn(start, allSteps.size - 1)
        val loopSteps = allSteps.subList(start, end + 1)
        val count = if (step.loopCount <= 0) 1 else step.loopCount
        for (i in 0 until count) {
            if (!isRunning) break
            while (isPaused && isRunning) sleep(100)
            executeSteps(loopSteps, service)
        }
    }

    /**
     * 执行 IF 条件块：条件成立则执行 ifSteps 内的所有子步骤，不成立则跳过
     */
    private fun executeIfBlock(step: ScriptStep, service: AutoAccessibilityService) {
        val conditionMet = when (step.conditionType) {
            0 -> checkFindImage(step, true)    // 找图成功
            1 -> checkFindText(step, true)     // 找文字成功
            2 -> checkFindImage(step, false)   // 找图失败
            3 -> checkFindText(step, false)    // 找文字失败
            else -> false
        }

        if (conditionMet && step.ifSteps.isNotEmpty()) {
            Log.d(TAG, "IF 条件成立，执行 ${step.ifSteps.size} 个子步骤")
            executeSteps(step.ifSteps, service)
        } else {
            Log.d(TAG, "IF 条件不成立，跳过 ${step.ifSteps.size} 个子步骤")
        }
    }

    /**
     * 检查找图条件（不点击，只判断是否存在）
     */
    private fun checkFindImage(step: ScriptStep, expectFound: Boolean): Boolean {
        val matcher = ImageMatcher.instance ?: return !expectFound
        val region = if (step.conditionUseRegion) {
            android.graphics.Rect(
                step.conditionRegionLeft, step.conditionRegionTop,
                step.conditionRegionRight, step.conditionRegionBottom
            )
        } else null
        val result = matcher.findImage(
            step.conditionImagePath,
            step.conditionSimilarity,
            step.conditionTimeout,
            region
        )
        val found = result != null
        return if (expectFound) found else !found
    }

    /**
     * 检查找文字条件（不点击，只判断是否存在）
     */
    private fun checkFindText(step: ScriptStep, expectFound: Boolean): Boolean {
        val ocr = OcrHelper.instance ?: return !expectFound
        val region = if (step.conditionUseRegion) {
            android.graphics.Rect(
                step.conditionRegionLeft, step.conditionRegionTop,
                step.conditionRegionRight, step.conditionRegionBottom
            )
        } else null
        val result = ocr.findText(
            step.conditionText,
            step.conditionTimeout,
            region
        )
        val found = result != null
        return if (expectFound) found else !found
    }

    private fun applyOffset(x: Int, y: Int): Pair<Int, Int> {
        return if (useCoordOffset) {
            val offsetX = (Math.random() * 10 - 5).toInt()
            val offsetY = (Math.random() * 10 - 5).toInt()
            Pair(x + offsetX, y + offsetY)
        } else {
            Pair(x, y)
        }
    }

    private fun sleep(ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (!isRunning) return
            Thread.sleep(10)
        }
    }
}
