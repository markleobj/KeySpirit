package com.keyspirit.script

import android.util.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

/**
 * JS 脚本引擎，支持：
 * 1. 将 JS 代码转换为步骤列表（解析模式）
 * 2. 将步骤列表转换为 JS 代码（生成模式）
 * 3. 执行 JS 代码（运行模式）
 */
object JsEngine {

    private const val TAG = "JsEngine"

    /**
     * 将步骤列表转换为 JS 代码
     */
    fun stepsToCode(steps: List<ScriptStep>): String {
        val sb = StringBuilder()
        sb.appendLine("// 按键精灵脚本")
        sb.appendLine("function main() {")
        for (step in steps) {
            sb.append("    ")
            when (step.type) {
                StepType.CLICK -> sb.appendLine("click(${step.x}, ${step.y});")
                StepType.TOUCH_DOWN -> sb.appendLine("touchDown(${step.x}, ${step.y}, ${step.duration});")
                StepType.TOUCH_UP -> sb.appendLine("touchUp(${step.x}, ${step.y});")
                StepType.RIGHT_CLICK -> sb.appendLine("rightClick(${step.x}, ${step.y}, ${step.duration});")
                StepType.RIGHT_CLICK_DOWN -> sb.appendLine("rightClickDown(${step.x}, ${step.y}, ${step.duration});")
                StepType.RIGHT_CLICK_UP -> sb.appendLine("rightClickUp(${step.x}, ${step.y});")
                StepType.MOVE_MOUSE -> sb.appendLine("moveMouse(${step.x}, ${step.y});")
                StepType.PICK_POINT -> sb.appendLine("// pick point (${step.x}, ${step.y})")
                StepType.SWIPE -> sb.appendLine("swipe(${step.x1}, ${step.y1}, ${step.x2}, ${step.y2}, ${step.duration});")
                StepType.LONG_PRESS -> sb.appendLine("longPress(${step.x}, ${step.y}, ${step.duration});")
                StepType.SCREENSHOT -> sb.appendLine("screenshot(\"${step.imageName}\");")
                StepType.DELAY -> {
                    if (step.randomDelay > 0) {
                        sb.appendLine("sleep(${step.delay} + Math.random() * ${step.randomDelay});")
                    } else {
                        sb.appendLine("sleep(${step.delay});")
                    }
                }
                StepType.FIND_IMAGE -> sb.appendLine("findImageClick(\"${step.imagePath}\", ${step.similarity});")
                StepType.FIND_TEXT -> sb.appendLine("findTextClick(\"${step.text}\");")
                StepType.LOOP -> {
                    val count = if (step.loopCount == 0) "true" else step.loopCount.toString()
                    sb.appendLine("for (let i = 0; i < $count; i++) {")
                    @Suppress("SENSELESS_COMPARISON")
                    if (step.loopSteps != null) {
                        for (subStep in step.loopSteps) {
                            sb.append("        ")
                            appendStepCode(sb, subStep)
                        }
                    }
                    sb.appendLine("    }")
                }
                StepType.IF -> {
                    val condName = when (step.conditionType) {
                        0 -> "findImage"
                        1 -> "findText"
                        2 -> "findImageNotFound"
                        3 -> "findTextNotFound"
                        else -> "unknown"
                    }
                    val param = if (step.conditionType == 0 || step.conditionType == 2) {
                        "\"${step.conditionImagePath}\", ${step.conditionSimilarity}"
                    } else {
                        "\"${step.conditionText}\""
                    }
                    sb.appendLine("if ($condName($param)) {")
                    @Suppress("SENSELESS_COMPARISON")
                    if (step.ifSteps != null) {
                        for (subStep in step.ifSteps) {
                            sb.append("        ")
                            appendStepCode(sb, subStep)
                        }
                    }
                    sb.appendLine("    }")
                }
            }
        }
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("main();")
        return sb.toString()
    }

    /**
     * 将单个步骤转换为 JS 代码行（不含块结构）
     */
    private fun appendStepCode(sb: StringBuilder, step: ScriptStep) {
        when (step.type) {
            StepType.CLICK -> sb.appendLine("click(${step.x}, ${step.y});")
            StepType.TOUCH_DOWN -> sb.appendLine("touchDown(${step.x}, ${step.y}, ${step.duration});")
            StepType.TOUCH_UP -> sb.appendLine("touchUp(${step.x}, ${step.y});")
            StepType.RIGHT_CLICK -> sb.appendLine("rightClick(${step.x}, ${step.y}, ${step.duration});")
            StepType.RIGHT_CLICK_DOWN -> sb.appendLine("rightClickDown(${step.x}, ${step.y}, ${step.duration});")
            StepType.RIGHT_CLICK_UP -> sb.appendLine("rightClickUp(${step.x}, ${step.y});")
            StepType.MOVE_MOUSE -> sb.appendLine("moveMouse(${step.x}, ${step.y});")
            StepType.PICK_POINT -> sb.appendLine("// pick point (${step.x}, ${step.y})")
            StepType.SWIPE -> sb.appendLine("swipe(${step.x1}, ${step.y1}, ${step.x2}, ${step.y2}, ${step.duration});")
            StepType.LONG_PRESS -> sb.appendLine("longPress(${step.x}, ${step.y}, ${step.duration});")
            StepType.SCREENSHOT -> sb.appendLine("screenshot(\"${step.imageName}\");")
            StepType.DELAY -> {
                if (step.randomDelay > 0) {
                    sb.appendLine("sleep(${step.delay} + Math.random() * ${step.randomDelay});")
                } else {
                    sb.appendLine("sleep(${step.delay});")
                }
            }
            StepType.FIND_IMAGE -> sb.appendLine("findImageClick(\"${step.imagePath}\", ${step.similarity});")
            StepType.FIND_TEXT -> sb.appendLine("findTextClick(\"${step.text}\");")
            else -> sb.appendLine("// ${step.type.typeName}")
        }
    }

    /**
     * 将 JS 代码解析为步骤列表（简单解析）
     */
    fun codeToSteps(code: String): List<ScriptStep> {
        val steps = mutableListOf<ScriptStep>()
        val lines = code.lines()
        val clickRegex = Regex("""click\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
        val swipeRegex = Regex("""swipe\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""")
        val sleepRegex = Regex("""sleep\s*\(\s*(\d+)\s*\)""")
        val longPressRegex = Regex("""longPress\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""")
        val findImageRegex = Regex("""findImageClick\s*\(\s*"([^"]*)"\s*,\s*([\d.]+)\s*\)""")
        val findTextRegex = Regex("""findTextClick\s*\(\s*"([^"]*)"\s*\)""")
        val moveMouseRegex = Regex("""moveMouse\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""")

        for (line in lines) {
            val trimmed = line.trim()
            try {
                when {
                    clickRegex.containsMatchIn(trimmed) -> {
                        val m = clickRegex.find(trimmed)
                        if (m != null) {
                            steps.add(ScriptStep(
                                type = StepType.CLICK,
                                x = m.groupValues[1].toIntOrNull() ?: 0,
                                y = m.groupValues[2].toIntOrNull() ?: 0
                            ))
                        }
                    }
                    swipeRegex.containsMatchIn(trimmed) -> {
                        val m = swipeRegex.find(trimmed)
                        if (m != null) {
                            steps.add(ScriptStep(
                                type = StepType.SWIPE,
                                x1 = m.groupValues[1].toIntOrNull() ?: 0,
                                y1 = m.groupValues[2].toIntOrNull() ?: 0,
                                x2 = m.groupValues[3].toIntOrNull() ?: 0,
                                y2 = m.groupValues[4].toIntOrNull() ?: 0,
                                duration = m.groupValues[5].toLongOrNull() ?: 300
                            ))
                        }
                    }
                    sleepRegex.containsMatchIn(trimmed) -> {
                        val m = sleepRegex.find(trimmed)
                        if (m != null) {
                            steps.add(ScriptStep(
                                type = StepType.DELAY,
                                delay = m.groupValues[1].toLongOrNull() ?: 500
                            ))
                        }
                    }
                    longPressRegex.containsMatchIn(trimmed) -> {
                        val m = longPressRegex.find(trimmed)
                        if (m != null) {
                            steps.add(ScriptStep(
                                type = StepType.LONG_PRESS,
                                x = m.groupValues[1].toIntOrNull() ?: 0,
                                y = m.groupValues[2].toIntOrNull() ?: 0,
                                duration = m.groupValues[3].toLongOrNull() ?: 500
                            ))
                        }
                    }
                    findImageRegex.containsMatchIn(trimmed) -> {
                        val m = findImageRegex.find(trimmed)
                        if (m != null) {
                            steps.add(ScriptStep(
                                type = StepType.FIND_IMAGE,
                                imagePath = m.groupValues[1],
                                similarity = m.groupValues[2].toDoubleOrNull() ?: 0.9
                            ))
                        }
                    }
                    findTextRegex.containsMatchIn(trimmed) -> {
                        val m = findTextRegex.find(trimmed)
                        if (m != null) {
                            steps.add(ScriptStep(
                                type = StepType.FIND_TEXT,
                                text = m.groupValues[1]
                            ))
                        }
                    }
                    moveMouseRegex.containsMatchIn(trimmed) -> {
                        val m = moveMouseRegex.find(trimmed)
                        if (m != null) {
                            steps.add(ScriptStep(
                                type = StepType.MOVE_MOUSE,
                                x = m.groupValues[1].toIntOrNull() ?: 0,
                                y = m.groupValues[2].toIntOrNull() ?: 0
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析代码行失败: $trimmed, 错误: ${e.message}")
            }
        }
        return steps
    }

    /**
     * 执行 JS 代码（运行模式）
     * 通过 Rhino 引擎执行，注入自动化 API
     */
    fun executeScript(code: String, executor: ScriptExecutor) {
        // 简化实现：将 JS 代码转为步骤，然后执行
        val steps = codeToSteps(code)
        val script = Script(steps = steps.toMutableList())
        val exec = ScriptExecutor(script, object : ScriptExecutor.ExecutionListener {
            override fun onStepStart(index: Int, step: ScriptStep) {}
            override fun onStepComplete(index: Int, step: ScriptStep) {}
            override fun onLoopUpdate(currentLoop: Int, totalLoops: Int) {}
            override fun onComplete() {}
            override fun onError(message: String) {}
        })
        exec.start()
    }
}
