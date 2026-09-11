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

        // Android 14+ 必须在 5 秒内调用 startForeground，否则崩溃。
        // 所以无论成功失败，都先想办法调到 startForeground，再决定是否 stopSelf。
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "onStartCommand 缺少截屏授权数据 (resultCode=$resultCode)")
            startForegroundFallback("截屏授权数据缺失，服务未启动")
            stopSelf()
            return START_NOT_STICKY
        }

        // 第一步：创建 MediaProjection 对象（Android 14 要求 mediaProjection 前台服务启动前必须已有 MediaProjection）
        var projectionOk = false
        try {
            createMediaProjection(resultCode, data)
            projectionOk = mediaProjection != null
        } catch (e: Exception) {
            Log.e(TAG, "createMediaProjection 失败", e)
        }

        // 第二步：立即启动前台服务（必须在 onStartCommand 5秒内调用）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (projectionOk) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, createNotification(), type)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            Log.d(TAG, "前台服务已启动 (projectionOk=$projectionOk)")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
        }

        if (!projectionOk) {
            Log.e(TAG, "MediaProjection 创建失败，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }

        // 第三步：服务成为前台后，再创建 VirtualDisplay（避免在非前台状态创建导致崩溃）
        try {
            setupVirtualDisplay()
        } catch (e: Exception) {
            Log.e(TAG, "setupVirtualDisplay 失败", e)
        }

        return START_NOT_STICKY
    }

    /**
     * 当 MediaProjection 不可用时，用 specialUse 类型启动前台服务，避免 Android 14 崩溃。
     */
    private fun startForegroundFallback(reason: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            Log.w(TAG, "已用 specialUse 类型启动前台服务（兜底）: $reason")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundFallback 失败", e)
        }
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
     * 仅创建 MediaProjection 对象并获取屏幕尺寸，不创建 VirtualDisplay。
     * 这一步在 startForeground 之前完成，满足 Android 14 的要求。
     */
    private fun createMediaProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data)
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        // 获取真实屏幕尺寸
        val sysMetrics = Resources.getSystem().displayMetrics
        screenWidth = sysMetrics.widthPixels
        screenHeight = sysMetrics.heightPixels
        screenDensity = sysMetrics.densityDpi

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
            Log.w(TAG, "getRealMetrics 失败", e)
        }

        Log.d(TAG, "MediaProjection 已创建，屏幕尺寸: ${screenWidth}x${screenHeight}, density=$screenDensity")
        lastError = "屏幕尺寸: ${screenWidth}x${screenHeight}, density=$screenDensity"
    }

    /**
     * 创建 ImageReader 和 VirtualDisplay。必须在 startForeground 之后调用。
     */
    private fun setupVirtualDisplay() {
        val projection = mediaProjection ?: run {
            Log.e(TAG, "setupVirtualDisplay: mediaProjection 为 null")
            return
        }
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader!!.setOnImageAvailableListener(imageAvailableListener, mainHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "KeySpiritCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, mainHandler
        )

        initDone = true
        Log.d(TAG, "VirtualDisplay 已创建，等待首帧...")
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

        // 直接从 ImageReader 取最新帧，避免用到遮罩层还在时的旧帧
        var capturedImage: Image? = null
        try {
            capturedImage = imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage 异常，回退到缓存帧", e)
        }

        // 如果没取到新帧，用缓存的 latestImage
        if (capturedImage == null) {
            synchronized(imageLock) {
                capturedImage = latestImage
                if (capturedImage == null) {
                    try {
                        val deadline = System.currentTimeMillis() + 3000
                        while (latestImage == null && System.currentTimeMillis() < deadline) {
                            imageLock.wait(16)
                        }
                    } catch (e: InterruptedException) {
                        // ignore
                    }
                    capturedImage = latestImage
                }
            }
        }

        val image = capturedImage
        if (image == null) {
            lastError = "失败: 3秒内未收到任何图像帧（已收到 $frameReceivedCount 帧，VirtualDisplay可能未正常工作）"
            lastCaptureSuccess = false
            Log.e(TAG, "截屏失败: 3秒内未获取到任何图像帧")
            return null
        }

        return try {
            Log.d(TAG, "截取图像: ${image.width}x${image.height} (期望 ${screenWidth}x${screenHeight})")
            val bitmap = imageToBitmap(image)
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
