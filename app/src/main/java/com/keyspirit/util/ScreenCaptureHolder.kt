package com.keyspirit.util

import android.graphics.Bitmap

/**
 * 持有最新的截屏 Bitmap，供找图/找文字使用
 */
object ScreenCaptureHolder {
    @Volatile
    var latestBitmap: Bitmap? = null
}
