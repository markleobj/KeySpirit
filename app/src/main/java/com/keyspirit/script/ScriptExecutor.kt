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
            }
        }.apply { start() }
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

            val success = executeStep(step, service, steps, i)
            handler.post { listener.onStepComplete(finalIndex, step) }

            if (!success) {
                Log.w(TAG, "步骤执行失败: ${step.getDescription()}")
            }

            i++
        }
    }

    private fun executeStep(
        step: ScriptStep,
        service: AutoAccessibilityService,
        allSteps: List<ScriptStep>,
        currentIndex: Int
    ): Boolean {
        return when (step.type) {
            StepType.CLICK -> {
                performClick(service, step.x, step.y)
                true
            }
            StepType.TOUCH_DOWN -> {
                performTouchDown(service, step.x, step.y, step.duration)
                true
            }
            StepType.TOUCH_UP -> {
                // touch up 通过 click 的快速释放实现
                performClick(service, step.x, step.y)
                true
            }
            StepType.SWIPE -> {
                performSwipe(service, step.x1, step.y1, step.x2, step.y2, step.duration)
                true
            }
            StepType.LONG_PRESS -> {
                performLongPress(service, step.x, step.y, step.duration)
                true
            }
            StepType.DELAY -> {
                val delay = if (step.randomDelay > 0) {
                    step.delay + (Math.random() * step.randomDelay).toLong()
                } else {
                    step.delay
                }
                sleep(delay)
                true
            }
            StepType.FIND_IMAGE -> {
                findAndClickImage(step)
            }
            StepType.FIND_TEXT -> {
                findAndClickText(step)
            }
            StepType.LOOP -> {
                executeLoop(step, allSteps, service)
                true
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
        val result = matcher.findImage(step.imagePath, step.similarity, step.findTimeout)
        if (result != null) {
            val service = AutoAccessibilityService.instance ?: return false
            performClick(service, result.x, result.y)
            return true
        }
        return false
    }

    private fun findAndClickText(step: ScriptStep): Boolean {
        val ocr = OcrHelper.instance ?: return false
        val result = ocr.findText(step.text, step.findTimeout)
        if (result != null) {
            val service = AutoAccessibilityService.instance ?: return false
            performClick(service, result.x, result.y)
            return true
        }
        return false
    }

    private fun executeLoop(step: ScriptStep, allSteps: List<ScriptStep>, service: AutoAccessibilityService) {
        val loopSteps = allSteps.subList(step.loopStartIndex, step.loopEndIndex + 1)
        for (i in 0 until step.loopCount) {
            if (!isRunning) break
            while (isPaused && isRunning) sleep(100)
            executeSteps(loopSteps, service)
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
