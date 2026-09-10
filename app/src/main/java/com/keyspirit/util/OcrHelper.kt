package com.keyspirit.util

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrHelper private constructor() {

    companion object {
        private const val TAG = "OcrHelper"
        var instance: OcrHelper? = null
            private set

        fun init() {
            if (instance == null) instance = OcrHelper()
        }
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * 在屏幕中查找指定文字并返回其坐标
     * @param targetText 要查找的文字
     * @param timeout 超时时间 ms
     * @param region 限定查找区域（null 表示全屏）
     */
    fun findText(targetText: String, timeout: Long, region: Rect? = null): Point? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeout) {
            val bitmap = com.keyspirit.service.ScreenCaptureService.instance?.captureScreen()
            if (bitmap == null) {
                Thread.sleep(200)
                continue
            }
            val result = recognize(bitmap, targetText, region)
            if (result != null) return result
            Thread.sleep(300)
        }
        return null
    }

    private fun recognize(bitmap: Bitmap, target: String, region: Rect?): Point? {
        Log.d(TAG, "OCR截图尺寸: ${bitmap.width}x${bitmap.height}, 查找区域: ${region?.left},${region?.top},${region?.right},${region?.bottom}")
        // 如果指定了区域，裁剪位图
        val (cropBitmap, offsetX, offsetY) = if (region != null) {
            val left = region.left.coerceIn(0, bitmap.width)
            val top = region.top.coerceIn(0, bitmap.height)
            val right = region.right.coerceIn(left, bitmap.width)
            val bottom = region.bottom.coerceIn(top, bitmap.height)
            Log.d(TAG, "OCR裁剪后区域: $left,$top,$right,$bottom, 裁剪尺寸: ${right-left}x${bottom-top}")
            if (right - left < 10 || bottom - top < 10) {
                Triple(bitmap, 0, 0)
            } else {
                Triple(Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top), left, top)
            }
        } else {
            Triple(bitmap, 0, 0)
        }

        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(cropBitmap, 0)
        val lock = Object()
        var result: Point? = null
        var done = false

        recognizer.process(image)
            .addOnSuccessListener { text ->
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        if (line.text.contains(target, ignoreCase = true)) {
                            val rect = line.boundingBox
                            if (rect != null) {
                                // 加上区域偏移，转换为屏幕坐标
                                result = Point(rect.centerX() + offsetX, rect.centerY() + offsetY)
                            }
                        }
                    }
                }
                synchronized(lock) { done = true; lock.notifyAll() }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR 识别失败", e)
                synchronized(lock) { done = true; lock.notifyAll() }
            }

        synchronized(lock) {
            while (!done) lock.wait(5000)
        }
        return result
    }
}
