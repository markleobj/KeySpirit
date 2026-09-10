package com.keyspirit.util

import android.graphics.Bitmap
import android.graphics.Point
import android.util.Log

class ImageMatcher private constructor() {

    companion object {
        private const val TAG = "ImageMatcher"
        var instance: ImageMatcher? = null
            private set

        fun init() {
            if (instance == null) instance = ImageMatcher()
        }
    }

    /**
     * 在当前屏幕中查找指定图片
     * @param imagePath 模板图片路径
     * @param similarity 相似度阈值 0~1
     * @param timeout 超时时间 ms
     * @return 找到的中心点坐标，未找到返回 null
     */
    fun findImage(imagePath: String, similarity: Double, timeout: Long): Point? {
        val screenBitmap = captureScreen() ?: return null
        val templateBitmap = loadTemplate(imagePath) ?: return null

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeout) {
            val result = matchTemplate(screenBitmap, templateBitmap, similarity)
            if (result != null) return result
            Thread.sleep(200)
        }
        return null
    }

    private fun captureScreen(): Bitmap? {
        // 通过 ScreenCaptureService 截屏
        return ScreenCaptureHolder.latestBitmap
    }

    private fun loadTemplate(path: String): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            Log.e(TAG, "加载模板图片失败: $path", e)
            null
        }
    }

    /**
     * 模板匹配 - 使用简单的像素比较算法
     * 生产环境建议用 OpenCV matchTemplate
     */
    private fun matchTemplate(screen: Bitmap, template: Bitmap, threshold: Double): Point? {
        val sw = screen.width
        val sh = screen.height
        val tw = template.width
        val th = template.height

        if (tw > sw || th > sh) return null

        // 降采样以提高速度
        val scale = 4
        val sw2 = sw / scale
        val sh2 = sh / scale
        val tw2 = tw / scale
        val th2 = th / scale

        val screenSmall = Bitmap.createScaledBitmap(screen, sw2, sh2, false)
        val templateSmall = Bitmap.createScaledBitmap(template, tw2, th2, false)

        val screenPixels = IntArray(sw2 * sh2)
        screenSmall.getPixels(screenPixels, 0, sw2, 0, 0, sw2, sh2)
        val templatePixels = IntArray(tw2 * th2)
        templateSmall.getPixels(templatePixels, 0, tw2, 0, 0, tw2, th2)

        var bestX = -1
        var bestY = -1
        var bestScore = 0.0

        for (y in 0..sh2 - th2 step 2) {
            for (x in 0..sw2 - tw2 step 2) {
                val score = calculateSimilarity(screenPixels, sw2, x, y, templatePixels, tw2, th2)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
            }
        }

        if (bestScore >= threshold && bestX >= 0) {
            return Point(bestX * scale + tw / 2, bestY * scale + th / 2)
        }
        return null
    }

    private fun calculateSimilarity(
        screen: IntArray, screenW: Int, sx: Int, sy: Int,
        template: IntArray, templateW: Int, templateH: Int
    ): Double {
        var match = 0
        var total = 0
        for (y in 0 until templateH) {
            for (x in 0 until templateW) {
                val screenPixel = screen[(sy + y) * screenW + (sx + x)]
                val templatePixel = template[y * templateW + x]
                val sr = (screenPixel shr 16) and 0xff
                val sg = (screenPixel shr 8) and 0xff
                val sb = screenPixel and 0xff
                val tr = (templatePixel shr 16) and 0xff
                val tg = (templatePixel shr 8) and 0xff
                val tb = templatePixel and 0xff
                val diff = Math.abs(sr - tr) + Math.abs(sg - tg) + Math.abs(sb - tb)
                if (diff < 60) match++
                total++
            }
        }
        return if (total == 0) 0.0 else match.toDouble() / total
    }
}
