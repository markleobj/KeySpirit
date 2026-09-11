package com.keyspirit.script

enum class StepType(val typeName: String, val displayName: String) {
    CLICK("click", "点击"),
    TOUCH_DOWN("touch_down", "按下"),
    TOUCH_UP("touch_up", "抬起"),
    LEFT_CLICK_UP("left_click_up", "左键抬起"),
    RIGHT_CLICK_DOWN("right_click_down", "右键按下"),
    RIGHT_CLICK_UP("right_click_up", "右键抬起"),
    SWIPE("swipe", "滑动"),
    LONG_PRESS("long_press", "长按"),
    DELAY("delay", "延迟"),
    FIND_IMAGE("find_image", "找图"),
    FIND_TEXT("find_text", "找文字"),
    LOOP("loop", "循环");

    companion object {
        fun fromTypeName(name: String): StepType {
            return values().firstOrNull { it.typeName == name } ?: CLICK
        }
    }
}
