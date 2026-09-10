package com.keyspirit.script

import java.util.UUID

data class Script(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "未命名脚本",
    var steps: MutableList<ScriptStep> = mutableListOf(),
    var loopCount: Int = 1, // 0 表示无限循环
    var loopInterval: Long = 0, // 每次循环间隔
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var lastRunAt: Long = 0
) {
    fun stepCount(): Int = steps.size
}
