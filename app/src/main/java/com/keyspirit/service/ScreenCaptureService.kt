package com.keyspirit.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.keyspirit.KeySpiritApp
import com.keyspirit.R
import java.nio.ByteBuffer

import android.os.HandlerThread

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1002

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        var instance: ScreenCaptureService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var handlerThread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 10+ 需要在 startForeground 中指定前台服务类型
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode != 0 && data != null) {
            try {
                initMediaProjection(resultCode, data)
            } catch (e: Exception) {
                Log.e(TAG, "initMediaProjection 失败", e)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, KeySpiritApp.CHANNEL_CAPTURE)
            .setContentTitle("截屏服务运行中")
            .setContentText("用于找图和找文字功能")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    /**
     * 初始化 MediaProjection（只做一次，保存对象供按需截屏使用）
     */
    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let {
                screenWidth = it.width()
                screenHeight = it.height()
            }
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        screenDensity = metrics.densityDpi
        if (screenDensity == 0) screenDensity = Resources.getSystem().displayMetrics.densityDpi

        handlerThread = HandlerThread("ScreenCapture").apply { start() }
        Log.d(TAG, "MediaProjection 初始化成功: ${screenWidth}x${screenHeight}")
    }

    /**
     * 按需截取一帧屏幕（同步方法，在调用线程执行）
     */
    fun captureScreen(): Bitmap? {
        val projection = mediaProjection ?: return null
        if (screenWidth == 0 || screenHeight == 0) return null

        var reader: ImageReader? = null
        var vDisplay: VirtualDisplay? = null
        return try {
            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            vDisplay = projection.createVirtualDisplay(
                "KeySpiritCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            // 等待一帧图像
            var image: Image? = null
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) {
                image = reader.acquireLatestImage()
                if (image != null) break
                Thread.sleep(16)
            }
            if (image == null) {
                Log.e(TAG, "截屏超时，未获取到图像")
                return null
            }
            val bitmap = imageToBitmap(image)
            image.close()
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "截屏失败", e)
            null
        } finally {
            vDisplay?.release()
            reader?.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪掉 padding 部分
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image 转 Bitmap 失败", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mediaProjection?.stop()
        mediaProjection = null
        handlerThread?.quitSafely()
        handlerThread = null
        Log.d(TAG, "截屏服务已停止")
    }
}
