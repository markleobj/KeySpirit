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

        // 服务状态广播
        const val ACTION_STATE_CHANGED = "com.keyspirit.SCREEN_CAPTURE_STATE_CHANGED"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"

        // 状态值
        const val STATE_STOPPED = 0
        const val STATE_STARTING = 1
        const val STATE_RUNNING = 2
        const val STATE_ERROR = 3

        // 前台服务类型
        private const val FG_TYPE_NONE = 0
        private const val FG_TYPE_MEDIA_PROJECTION = 1
        private const val FG_TYPE_SPECIAL_USE = 2
        private const val FG_TYPE_NO_TYPE = 3

        var instance: ScreenCaptureService? = null
            private set

        @Volatile
        private var currentState: Int = STATE_STOPPED

        @Volatile
        private var currentError: String = ""

        fun isRunning(): Boolean = instance != null && currentState == STATE_RUNNING

        fun isServiceAlive(): Boolean = instance != null

        fun getState(): Int = currentState

        fun getError(): String = currentError

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
            setState(STATE_ERROR, "MediaProjection 被系统停止，请重新授权")
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
        setState(STATE_STARTING, "正在启动截屏服务...")
        Log.d(TAG, "onCreate: 截屏服务实例已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        Log.d(TAG, "onStartCommand: resultCode=$resultCode, data=${data != null}, SDK=${Build.VERSION.SDK_INT}")

        if (resultCode == 0 || data == null) {
            val msg = "截屏授权数据缺失 (resultCode=$resultCode, data=${data != null})"
            Log.e(TAG, "onStartCommand: $msg")
            startForegroundForMediaProjection()
            setState(STATE_ERROR, msg)
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ (API 34) 关键要求：
        // getMediaProjection() 必须在服务成为前台服务(mediaProjection类型)之后调用，
        // 否则会抛 SecurityException: "Media projections require a foreground service..."
        // 所以顺序必须是：startForeground → getMediaProjection → createVirtualDisplay

        // 第一步：立即启动前台服务（mediaProjection 类型）
        // 注意：Android 14 允许在没有 MediaProjection 对象的情况下，
        // 用 mediaProjection 类型调用 startForeground（只要有用户授权）。
        val fgType = startForegroundForMediaProjection()

        if (fgType == FG_TYPE_NONE) {
            Log.e(TAG, "startForeground 完全失败，停止服务")
            setState(STATE_ERROR, "无法启动前台服务")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "startForeground 成功，类型=$fgType，现在创建 MediaProjection")

        // 第二步：现在服务已是前台服务，可以安全创建 MediaProjection
        var projectionOk = false
        var projectionError = ""
        try {
            createMediaProjection(resultCode, data)
            projectionOk = mediaProjection != null
            if (!projectionOk) {
                projectionError = "MediaProjection 对象为 null"
            }
        } catch (e: Exception) {
            projectionError = "createMediaProjection 异常: ${e.message}"
            Log.e(TAG, "createMediaProjection 失败", e)
        }

        // 如果之前用了 specialUse 兜底，现在 MediaProjection 创建成功了，
        // 重新用 mediaProjection 类型启动前台服务（Android 14 要求）
        if (projectionOk && fgType != FG_TYPE_MEDIA_PROJECTION) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, createNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                    Log.d(TAG, "已升级前台服务类型为 mediaProjection")
                }
            } catch (e: Exception) {
                Log.w(TAG, "升级前台服务类型失败，继续使用 specialUse", e)
            }
        }

        if (!projectionOk) {
            Log.e(TAG, "MediaProjection 创建失败: $projectionError")
            setState(STATE_ERROR, projectionError)
            return START_NOT_STICKY
        }

        // 第三步：创建 VirtualDisplay
        try {
            setupVirtualDisplay()
            setState(STATE_RUNNING, "")
            Log.d(TAG, "截屏服务启动成功，VirtualDisplay 已创建")
        } catch (e: Exception) {
            Log.e(TAG, "setupVirtualDisplay 失败", e)
            setState(STATE_ERROR, "VirtualDisplay 创建失败: ${e.message}")
        }

        return START_NOT_STICKY
    }

    /**
     * 启动前台服务用于截屏：优先 mediaProjection 类型，失败则降级。
     * 返回实际使用的前台服务类型（FG_TYPE_*）。
     * 注意：Android 14+ 允许在创建 MediaProjection 之前就用 mediaProjection 类型
     * 调用 startForeground（只要持有用户授权）。
     */
    private fun startForegroundForMediaProjection(): Int {
        val notification = createNotification()

        // 尝试 1: mediaProjection 类型（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                Log.d(TAG, "startForeground 成功 (mediaProjection)")
                return FG_TYPE_MEDIA_PROJECTION
            } catch (e: Exception) {
                Log.w(TAG, "startForeground(mediaProjection) 失败，降级到 specialUse", e)
            }
        }

        // 尝试 2: specialUse 类型（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                Log.d(TAG, "startForeground 成功 (specialUse)")
                return FG_TYPE_SPECIAL_USE
            } catch (e: Exception) {
                Log.w(TAG, "startForeground(specialUse) 失败，降级到无类型", e)
            }
        }

        // 尝试 3: 无类型（兼容旧版本）
        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "startForeground 成功 (无类型)")
            return FG_TYPE_NO_TYPE
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(无类型) 也失败", e)
            return FG_TYPE_NONE
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
     */
    private fun createMediaProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("getMediaProjection 返回 null")
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
            throw IllegalStateException("mediaProjection 为 null")
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
     * 更新服务状态并发送广播通知 UI。
     */
    private fun setState(state: Int, error: String) {
        currentState = state
        currentError = error
        Log.d(TAG, "状态变更: ${stateName(state)}, error=$error")
        try {
            val intent = Intent(ACTION_STATE_CHANGED).apply {
                setPackage(packageName)
                putExtra(EXTRA_STATE, state)
                putExtra(EXTRA_ERROR, error)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "发送状态广播失败", e)
        }
    }

    private fun stateName(state: Int): String = when (state) {
        STATE_STOPPED -> "STOPPED"
        STATE_STARTING -> "STARTING"
        STATE_RUNNING -> "RUNNING"
        STATE_ERROR -> "ERROR"
        else -> "UNKNOWN($state)"
    }

    /**
     * 截取当前屏幕一帧。
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

        var capturedImage: Image? = null
        try {
            capturedImage = imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage 异常，回退到缓存帧", e)
        }

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
        sb.appendLine("服务状态: ${stateName(currentState)}")
        sb.appendLine("服务存活: ${instance != null}")
        sb.appendLine("MediaProjection有效: ${mediaProjection != null}")
        sb.appendLine("初始化完成: $initDone")
        sb.appendLine("屏幕尺寸: ${screenWidth}x${screenHeight}, density=$screenDensity")
        sb.appendLine("ImageReader: ${imageReader != null}")
        sb.appendLine("VirtualDisplay: ${virtualDisplay != null}")
        sb.appendLine("已收到帧数: $frameReceivedCount")
        sb.appendLine("最新帧: ${latestImage != null}")
        sb.appendLine("最近截屏: ${if (lastCaptureTime > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(lastCaptureTime)) else "无"}")
        sb.appendLine("最近结果: $lastError")
        if (currentError.isNotEmpty()) {
            sb.appendLine("错误信息: $currentError")
        }
        return sb.toString()
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            buffer.rewind()

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
        Log.d(TAG, "onDestroy: 截屏服务正在停止")
        setState(STATE_STOPPED, "服务已停止")
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
        instance = null
        Log.d(TAG, "截屏服务已停止")
    }
}
