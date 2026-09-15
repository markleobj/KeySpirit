package com.keyspirit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.keyspirit.script.ScriptManager
import com.keyspirit.util.ImageMatcher
import com.keyspirit.util.OcrHelper

class KeySpiritApp : Application() {

    lateinit var scriptManager: ScriptManager
        private set

    companion object {
        lateinit var instance: KeySpiritApp
            private set

        const val CHANNEL_FLOATING = "floating_service"
        const val CHANNEL_CAPTURE = "capture_service"

        // 主线程 Handler，用于全局异常处理等
        val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 全局未捕获异常处理：防止应用直接崩溃退出，用对话框展示错误
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("KeySpiritApp", "未捕获异常 [${thread.name}]: ${throwable.message}", throwable)
            val errorMsg = buildString {
                append("异常类型：")
                append(throwable::class.java.simpleName)
                append("\n\n异常信息：")
                append(throwable.message ?: "无")
                append("\n\n发生线程：")
                append(thread.name)
                append("\n\n堆栈信息（前5行）：\n")
                append(throwable.stackTrace.take(5).joinToString("\n") { it.toString() })
            }
            // 用 AlertDialog 显示错误（需要在主线程）
            handler.post {
                try {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("程序异常")
                        .setMessage(errorMsg)
                        .setPositiveButton("知道了", null)
                        .setCancelable(true)
                        .create()
                        .apply {
                            // 确保使用应用上下文的窗口类型
                            window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                        }
                        .show()
                } catch (e: Exception) {
                    // 如果对话框弹不出来，至少用toast
                    android.widget.Toast.makeText(
                        this,
                        "程序异常: ${throwable.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        scriptManager = ScriptManager(this)
        ImageMatcher.init()
        OcrHelper.init()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val floatingChannel = NotificationChannel(
                CHANNEL_FLOATING,
                getString(R.string.channel_floating),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(floatingChannel)

            val captureChannel = NotificationChannel(
                CHANNEL_CAPTURE,
                getString(R.string.channel_capture),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(captureChannel)
        }
    }
}
