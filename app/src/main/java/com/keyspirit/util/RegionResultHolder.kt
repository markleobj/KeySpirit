package com.keyspirit.util

/**
 * 持有最近一次区域选取的结果，供编辑器读取
 */
object RegionResultHolder {
    @Volatile
    var region: IntArray? = null  // [left, top, right, bottom]

    @Volatile
    var hasNewResult: Boolean = false

    fun consumeRegion(): IntArray? {
        return if (hasNewResult && region != null) {
            hasNewResult = false
            region
        } else {
            null
        }
    }
}
