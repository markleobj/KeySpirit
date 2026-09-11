package com.keyspirit.script

data class ScriptStep(
    var type: StepType = StepType.CLICK,
    // 点击/按下/抬起坐标
    var x: Int = 0,
    var y: Int = 0,
    // 滑动起点
    var x1: Int = 0,
    var y1: Int = 0,
    // 滑动终点
    var x2: Int = 0,
    var y2: Int = 0,
    // 持续时间（毫秒）
    var duration: Long = 300,
    // 延迟时间（毫秒）
    var delay: Long = 500,
    // 随机延迟上限（0 表示不随机）
    var randomDelay: Long = 0,
    // 找图相关
    var imagePath: String = "",
    var similarity: Double = 0.9,
    var findTimeout: Long = 5000,
    // 找文字相关
    var text: String = "",
    // 区域查找（用于找图/找文字的限定区域）
    var regionLeft: Int = 0,
    var regionTop: Int = 0,
    var regionRight: Int = 0,
    var regionBottom: Int = 0,
    var useRegion: Boolean = false,
    // 循环相关
    var loopCount: Int = 1,
    var loopStartIndex: Int = 0,
    var loopEndIndex: Int = 0,
    // 备注
    var remark: String = ""
) {
    fun getDescription(): String {
        return when (type) {
            StepType.CLICK -> "左键点击 ($x, $y)"
            StepType.TOUCH_DOWN -> "左键按下 ($x, $y)"
            StepType.TOUCH_UP -> "左键抬起 ($x, $y)"
            StepType.RIGHT_CLICK -> "右键点击 ($x, $y) ${duration}ms"
            StepType.RIGHT_CLICK_DOWN -> "右键按下 ($x, $y) ${duration}ms"
            StepType.RIGHT_CLICK_UP -> "右键抬起 ($x, $y)"
            StepType.SWIPE -> "($x1,$y1) → ($x2,$y2) ${duration}ms"
            StepType.LONG_PRESS -> "长按 ($x, $y) ${duration}ms"
            StepType.DELAY -> if (randomDelay > 0) "${delay}~${delay + randomDelay}ms" else "${delay}ms"
            StepType.FIND_IMAGE -> {
                val regionStr = if (useRegion) " [区域:($regionLeft,$regionTop)-($regionRight,$regionBottom)]" else ""
                "图片: ${imagePath.substringAfterLast('/')} 相似度: $similarity$regionStr"
            }
            StepType.FIND_TEXT -> {
                val regionStr = if (useRegion) " [区域:($regionLeft,$regionTop)-($regionRight,$regionBottom)]" else ""
                "文字: \"$text\"$regionStr"
            }
            StepType.LOOP -> "重复 $loopCount 次，步骤 ${loopStartIndex + 1}-${loopEndIndex + 1}"
        }
    }
}
