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

    // 持续缓存最新的一帧图像，captureScreen 直接取这个，不丢弃
    private val imageLock = Object()
    private var latestImage: Image? = null

    // 诊断信息
    @Volatile
    private var lastError: String = "未尝试截屏"
    @Volatile
    private var lastCaptureTime: Long = 0
    @Volatile
    private var lastCaptureSuccess: Boolean = false
    @Volatile
    private var frameReceivedCount: Int = 0
    @Volatile
    private var initDone: Boolean = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection 已被系统停止")
            mediaProjection = null
        }
    }

    /**
     * 每当 VirtualDisplay 渲染出新帧时被调用，把最新帧缓存到 latestImage。
     * 旧帧自动关闭，确保缓冲区不被占满。
     */
    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        try {
            val image = reader.acquireLatestImage()
            if (image != null) {
                frameReceivedCount++
                synchronized(imageLock) {
                    latestImage?.close()
                    latestImage = image
                    imageLock.notifyAll()
                }
                if (frameReceivedCount <= 3) {
                    Log.d(TAG, "收到第 $frameReceivedCount 帧: ${image.width}x${image.height}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "OnImageAvailableListener 异常", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode == 0 || data == null) {
            Log.e(TAG, "onStartCommand 缺少截屏授权数据 (resultCode=$resultCode)")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ 要求：必须先创建 MediaProjection，才能以 mediaProjection 类型启动前台服务
        try {
            initMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "initMediaProjection 失败", e)
            stopSelf()
            return START_NOT_STICKY
        }

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
            Log.d(TAG, "前台服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
            // 即使 startForeground 失败也不停止服务，MediaProjection 已经创建
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

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data)
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        // 获取真实屏幕尺寸：优先用 Resources.getSystem()，它总是返回真实屏幕尺寸
        val sysMetrics = Resources.getSystem().displayMetrics
        screenWidth = sysMetrics.widthPixels
        screenHeight = sysMetrics.heightPixels
        screenDensity = sysMetrics.densityDpi

        // 再用 WindowManager 校验一次
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                screenWidth = dm.widthPixels
                screenHeight = dm.heightPixels
                if (dm.densityDpi > 0) screenDensity = dm.densityDpi
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRealMetrics 失败，使用 Resources.getSystem() 的值", e)
        }

        Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}, density=$screenDensity")
        lastError = "屏幕尺寸: ${screenWidth}x${screenHeight}, density=$screenDensity"

        // 创建 ImageReader 和 VirtualDisplay
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader!!.setOnImageAvailableListener(imageAvailableListener, mainHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "KeySpiritCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, mainHandler
        )

        initDone = true
        Log.d(TAG, "MediaProjection 初始化成功，VirtualDisplay 已创建，等待首帧...")
    }

    /**
     * 截取当前屏幕一帧。
     * 直接取 latestImage（OnImageAvailableListener 持续缓存的最新帧），
     * 不丢弃、不等待新帧，所以静态屏幕也能拿到截图。
     */
    fun captureScreen(): Bitmap? {
        lastCaptureTime = System.currentTimeMillis()
        if (mediaProjection == null) {
            lastError = "失败: mediaProjection 为 null（截屏权限已失效，请重新授权）"
            lastCaptureSuccess = false
            Log.e(TAG, "截屏失败: mediaProjection 为 null")
            return null
        }
        if (!initDone) {
            lastError = "失败: 截屏服务尚未初始化完成"
            lastCaptureSuccess = false
            return null
        }
        if (screenWidth == 0 || screenHeight == 0) {
            lastError = "失败: 屏幕尺寸为 0"
            lastCaptureSuccess = false
            return null
        }

        var image: Image? = null
        synchronized(imageLock) {
            if (latestImage == null) {
                try {
                    val deadline = System.currentTimeMillis() + 3000
                    while (latestImage == null && System.currentTimeMillis() < deadline) {
                        imageLock.wait(16)
                    }
                } catch (e: InterruptedException) {
                    // ignore
                }
            }
            image = latestImage
        }

        val capturedImage = image
        if (capturedImage == null) {
            lastError = "失败: 3秒内未收到任何图像帧（已收到 $frameReceivedCount 帧，VirtualDisplay可能未正常工作）"
            lastCaptureSuccess = false
            Log.e(TAG, "截屏失败: 3秒内未获取到任何图像帧")
            return null
        }

        return try {
            Log.d(TAG, "截取图像: ${capturedImage.width}x${capturedImage.height} (期望 ${screenWidth}x${screenHeight})")
            val bitmap = imageToBitmap(capturedImage)
            if (bitmap == null) {
                lastError = "失败: Image 转 Bitmap 失败"
                lastCaptureSuccess = false
                Log.e(TAG, "Image 转 Bitmap 失败")
            } else {
                lastError = "成功: ${bitmap.width}x${bitmap.height}"
                lastCaptureSuccess = true
                Log.d(TAG, "截屏成功: ${bitmap.width}x${bitmap.height}")
            }
            bitmap
        } catch (e: Exception) {
            lastError = "失败: 截屏异常 ${e.message}"
            lastCaptureSuccess = false
            Log.e(TAG, "截屏异常", e)
            null
        }
    }

    /**
     * 获取诊断信息字符串，供 App 内展示
     */
    fun getDiagnosticInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 截屏服务诊断 ===")
        sb.appendLine("服务运行: ${instance != null}")
        sb.appendLine("MediaProjection有效: ${mediaProjection != null}")
        sb.appendLine("初始化完成: $initDone")
        sb.appendLine("屏幕尺寸: ${screenWidth}x${screenHeight}, density=$screenDensity")
        sb.appendLine("ImageReader: ${imageReader != null}")
        sb.appendLine("VirtualDisplay: ${virtualDisplay != null}")
        sb.appendLine("已收到帧数: $frameReceivedCount")
        sb.appendLine("最新帧: ${latestImage != null}")
        sb.appendLine("最近截屏: ${if (lastCaptureTime > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(lastCaptureTime)) else "无"}")
        sb.appendLine("最近结果: $lastError")
        return sb.toString()
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            // 确保 buffer 从起始位置读取（latestImage 可能被多次读取）
            buffer.rewind()

            // bitmap 宽度 = image.width + padding 对应的像素数（向上取整）
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

            // 裁剪掉 padding，返回 image.width x image.height 的 bitmap
            return if (rowPadding > 0 && bitmapWidth != image.width) {
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
        synchronized(imageLock) {
            latestImage?.close()
            latestImage = null
        }
        try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG, "释放 VirtualDisplay 异常", e) }
        virtualDisplay = null
        try { imageReader?.close() } catch (e: Exception) { Log.e(TAG, "关闭 ImageReader 异常", e) }
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
