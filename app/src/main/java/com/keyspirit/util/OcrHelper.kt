package com.keyspirit.util

import android.graphics.Bitmap
import android.graphics.Point
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
     */
    fun findText(targetText: String, timeout: Long): Point? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeout) {
            val bitmap = ScreenCaptureHolder.latestBitmap
            if (bitmap == null) {
                Thread.sleep(200)
                continue
            }
            val result = recognize(bitmap, targetText)
            if (result != null) return result
            Thread.sleep(300)
        }
        return null
    }

    private fun recognize(bitmap: Bitmap, target: String): Point? {
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
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
                                result = Point(rect.centerX(), rect.centerY())
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
