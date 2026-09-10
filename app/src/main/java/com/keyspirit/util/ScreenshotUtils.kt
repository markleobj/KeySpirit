package com.keyspirit.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenshotUtils {

    private const val TAG = "ScreenshotUtils"

    /**
     * 保存截图到应用私有目录
     * @return 保存的文件路径
     */
    fun saveToAppDir(context: Context, bitmap: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, "screenshots")
            if (!dir.exists()) dir.mkdirs()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "screenshot_$timeStamp.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "截图已保存: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存截图失败", e)
            null
        }
    }

    /**
     * 保存截图到系统相册（MediaStore）
     * @return 保存的 Uri
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val displayName = "KeySpirit_$timeStamp.png"
            val mimeType = "image/png"

            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KeySpirit")
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            )
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            Log.d(TAG, "截图已保存到相册: $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "保存截图到相册失败", e)
            null
        }
    }

    /**
     * 获取截图保存目录
     */
    fun getScreenshotDir(context: Context): File {
        val dir = File(context.filesDir, "screenshots")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 列出所有已保存的截图
     */
    fun listScreenshots(context: Context): List<File> {
        val dir = getScreenshotDir(context)
        return dir.listFiles { file -> file.extension == "png" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
