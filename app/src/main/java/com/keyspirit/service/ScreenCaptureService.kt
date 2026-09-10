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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.keyspirit.KeySpiritApp
import com.keyspirit.R
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1002

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        var instance: ScreenCaptureService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /**
         * 判断 MediaProjection 是否仍然有效（未被系统停止）。
         * 若返回 false，需要重新请求截屏权限。
         */
        fun isProjectionActive(): Boolean = instance?.mediaProjection != null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * MediaProjection 回调：当系统停止投影时（用户点"停止"、系统回收等），
     * 把 mediaProjection 置空，让 captureScreen 能正确判断并给出提示。
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection 已被系统停止")
            mediaProjection = null
        }
    }

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
        @Suppress("DEPRECATION")
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
     * 初始化 MediaProjection：获取真实屏幕尺寸，创建持久化的 ImageReader + VirtualDisplay。
     * VirtualDisplay 只创建一次，后续截屏直接取最新帧，比每次重建更稳定。
     */
    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data)
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        // 使用真实屏幕尺寸（包含状态栏和导航栏）
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        if (screenDensity == 0) screenDensity = Resources.getSystem().displayMetrics.densityDpi
        if (screenWidth == 0 || screenHeight == 0) {
            val realMetrics = Resources.getSystem().displayMetrics
            screenWidth = realMetrics.widthPixels
            screenHeight = realMetrics.heightPixels
        }

        Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}, density=$screenDensity")

        // 创建持久化的 ImageReader（3 缓冲，减少丢帧）
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)

        // 创建 VirtualDisplay，镜像真实屏幕
        virtualDisplay = projection.createVirtualDisplay(
            "KeySpiritCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, mainHandler
        )

        Log.d(TAG, "MediaProjection 初始化成功，VirtualDisplay 已创建")
    }

    /**
     * 截取当前屏幕一帧（同步方法，在调用线程执行）。
     * 由于 VirtualDisplay 是持久化的，直接 acquireLatestImage 取最新帧即可。
     */
    fun captureScreen(): Bitmap? {
        val projection = mediaProjection
        if (projection == null) {
            Log.e(TAG, "截屏失败: mediaProjection 为 null（可能已被系统停止，请重新授权）")
            return null
        }
        val reader = imageReader
        if (reader == null) {
            Log.e(TAG, "截屏失败: imageReader 为 null")
            return null
        }
        if (screenWidth == 0 || screenHeight == 0) {
            Log.e(TAG, "截屏失败: 屏幕尺寸为 0")
            return null
        }

        var image: Image? = null
        return try {
            // 先丢弃旧帧，确保拿到最新画面
            val old = reader.acquireLatestImage()
            old?.close()

            // 等待新帧（最长 3 秒）
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                image = reader.acquireLatestImage()
                if (image != null) break
                Thread.sleep(16)
            }
            val capturedImage = image
            if (capturedImage == null) {
                Log.e(TAG, "截屏超时(3s)，未获取到图像")
                return null
            }

            Log.d(TAG, "获取到图像: ${capturedImage.width}x${capturedImage.height}")
            val bitmap = imageToBitmap(capturedImage)
            if (bitmap == null) {
                Log.e(TAG, "Image 转 Bitmap 失败")
            } else {
                Log.d(TAG, "截屏成功: ${bitmap.width}x${bitmap.height}")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "截屏异常", e)
            null
        } finally {
            image?.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            // 确保 buffer 从起始位置读取
            buffer.rewind()

            // 计算 bitmap 宽度（向上取整，避免整除丢失数据）
            val bitmapWidth = if (rowPadding > 0) {
                image.width + (rowPadding + pixelStride - 1) / pixelStride
            } else {
                image.width
            }

            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪掉 padding 部分，返回与 image 等大的 bitmap
            if (rowPadding > 0 && bitmapWidth != image.width) {
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
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放 VirtualDisplay 异常", e)
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭 ImageReader 异常", e)
        }
        imageReader = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止 MediaProjection 异常", e)
        }
        mediaProjection = null
        Log.d(TAG, "截屏服务已停止")
    }
}
