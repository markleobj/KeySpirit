package com.keyspirit.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenshotUtils {

    private const val TAG = "ScreenshotUtils"

    /**
     * 保存截图到指定项目的截图目录
     * @param context 上下文
     * @param bitmap 截图位图
     * @param scriptId 脚本（项目）ID
     * @param customName 自定义文件名（不含扩展名），为空则自动生成
     * @return 保存的文件路径
     */
    fun saveToProject(context: Context, bitmap: Bitmap, scriptId: String, customName: String? = null): String? {
        return try {
            val dir = File(context.filesDir, "projects/$scriptId/screenshots").apply {
                if (!exists()) mkdirs()
            }
            val fileName = if (customName.isNullOrEmpty()) {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                "screenshot_$timeStamp.png"
            } else {
                "$customName.png"
            }
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "截图已保存到项目目录: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存截图失败", e)
            null
        }
    }

    /**
     * 获取指定项目的截图目录
     */
    fun getProjectScreenshotsDir(context: Context, scriptId: String): File {
        return File(context.filesDir, "projects/$scriptId/screenshots").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * 列出指定项目目录下的所有截图
     */
    fun listProjectScreenshots(context: Context, scriptId: String): List<File> {
        val dir = getProjectScreenshotsDir(context, scriptId)
        return dir.listFiles { file ->
            file.extension.lowercase() in listOf("png", "jpg", "jpeg", "bmp")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
