package com.keyspirit.util

/**
 * 持有坐标选取结果，供编辑器读取
 */
object CoordinateResultHolder {
    @Volatile
    var x: Int = 0

    @Volatile
    var y: Int = 0

    @Volatile
    var hasResult: Boolean = false

    fun consume(): Pair<Int, Int>? {
        return if (hasResult) {
            hasResult = false
            Pair(x, y)
        } else {
            null
        }
    }
}
