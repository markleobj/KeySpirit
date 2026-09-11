package com.keyspirit.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.keyspirit.R
import com.keyspirit.service.AutoAccessibilityService
import com.keyspirit.service.ScreenCaptureService

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 1001
    }

    private lateinit var switchAccessibility: Switch
    private lateinit var switchFloating: Switch
    private lateinit var switchScreenCapture: Switch

    private val projectionManager: MediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val screenCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(ScreenCaptureService.EXTRA_STATE, ScreenCaptureService.STATE_STOPPED)
                val error = intent.getStringExtra(ScreenCaptureService.EXTRA_ERROR) ?: ""
                handleScreenCaptureState(state, error)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchAccessibility = findViewById(R.id.switchAccessibility)
        switchFloating = findViewById(R.id.switchFloating)
        switchScreenCapture = findViewById(R.id.switchScreenCapture)

        findViewById<View>(R.id.itemAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }
        findViewById<View>(R.id.itemFloatingPermission).setOnClickListener {
            openFloatingPermissionSettings()
        }
        findViewById<View>(R.id.itemScreenCapture).setOnClickListener {
            requestScreenCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ScreenCaptureService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenCaptureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenCaptureReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(screenCaptureReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun handleScreenCaptureState(state: Int, error: String) {
        when (state) {
            ScreenCaptureService.STATE_RUNNING -> {
                switchScreenCapture.isChecked = true
                Toast.makeText(this, "截屏服务已启动", Toast.LENGTH_SHORT).show()
            }
            ScreenCaptureService.STATE_ERROR -> {
                switchScreenCapture.isChecked = false
                val msg = if (error.isNotEmpty()) {
                    "截屏服务启动失败：$error"
                } else {
                    "截屏服务启动失败，请重试"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            ScreenCaptureService.STATE_STOPPED -> {
                switchScreenCapture.isChecked = false
            }
            ScreenCaptureService.STATE_STARTING -> {
                switchScreenCapture.isChecked = false
            }
        }
    }

    private fun updatePermissionStatus() {
        switchAccessibility.isChecked = AutoAccessibilityService.isRunning()
        switchFloating.isChecked = canDrawOverlays()
        // 使用 isRunning 检查（状态为 RUNNING 才认为已开启）
        switchScreenCapture.isChecked = ScreenCaptureService.isRunning()
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "请在列表中找到「按键精灵」并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFloatingPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }

    private fun requestScreenCapture() {
        // 如果服务已经在运行，提示用户
        if (ScreenCaptureService.isRunning()) {
            Toast.makeText(this, "截屏服务已在运行中", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
        } catch (e: Exception) {
            Toast.makeText(this, "无法请求截屏权限: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    val intent = Intent(this, ScreenCaptureService::class.java).apply {
                        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ScreenCaptureService.EXTRA_DATA, data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    // 不在这里显示"已启动"toast，等待服务状态广播确认
                    Toast.makeText(this, "正在启动截屏服务...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "截屏服务启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "未授权截屏，找图/找文字/截图功能不可用", Toast.LENGTH_LONG).show()
            }
        }
    }
}
