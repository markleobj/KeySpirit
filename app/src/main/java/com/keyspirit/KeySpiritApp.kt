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

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 全局未捕获异常处理：防止应用直接崩溃退出
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("KeySpiritApp", "未捕获异常 [${thread.name}]: ${throwable.message}", throwable)
            val msg = throwable.message ?: throwable::class.java.simpleName
            android.widget.Toast.makeText(this, "程序异常: $msg", android.widget.Toast.LENGTH_LONG).show()
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

    companion object {
        lateinit var instance: KeySpiritApp
            private set

        const val CHANNEL_FLOATING = "floating_service"
        const val CHANNEL_CAPTURE = "capture_service"
    }
}
