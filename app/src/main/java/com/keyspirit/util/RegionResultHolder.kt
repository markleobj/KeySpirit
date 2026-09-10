package com.keyspirit.util

import android.graphics.Rect

/**
 * 持有最近一次区域选取的结果，供悬浮窗服务读取
 */
object RegionResultHolder {
    @Volatile
    var lastRegion: Rect? = null

    @Volatile
    var hasNewResult: Boolean = false

    fun setRegion(rect: Rect) {
        lastRegion = rect
        hasNewResult = true
    }

    fun consumeRegion(): Rect? {
        return if (hasNewResult) {
            hasNewResult = false
            lastRegion
        } else {
            null
        }
    }
}
