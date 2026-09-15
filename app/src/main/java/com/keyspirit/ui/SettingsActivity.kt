package com.keyspirit.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Switch
import android.widget.TextView
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
    private lateinit var switchStorage: Switch
    private lateinit var switchRandomDelay: Switch
    private lateinit var switchOffset: Switch

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("keyspirit_settings", Context.MODE_PRIVATE)
    }

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
        com.keyspirit.util.TabletLayoutHelper.applyMaxWidth(this)

        switchAccessibility = findViewById(R.id.switchAccessibility)
        switchFloating = findViewById(R.id.switchFloating)
        switchScreenCapture = findViewById(R.id.switchScreenCapture)
        switchStorage = findViewById(R.id.switchStorage)
        switchRandomDelay = findViewById(R.id.switchRandomDelay)
        switchOffset = findViewById(R.id.switchOffset)

        // 显示动态版本号
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.tvVersion).text = "v$versionName"
        } catch (e: Exception) {}

        // 防检测开关
        switchRandomDelay.isChecked = prefs.getBoolean("random_delay", false)
        switchRandomDelay.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("random_delay", checked).apply()
            Toast.makeText(this, if (checked) "已开启随机延迟" else "已关闭随机延迟", Toast.LENGTH_SHORT).show()
        }
        switchOffset.isChecked = prefs.getBoolean("coord_offset", false)
        switchOffset.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("coord_offset", checked).apply()
            Toast.makeText(this, if (checked) "已开启坐标偏移" else "已关闭坐标偏移", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.itemAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }
        findViewById<View>(R.id.itemFloatingPermission).setOnClickListener {
            openFloatingPermissionSettings()
        }
        findViewById<View>(R.id.itemScreenCapture).setOnClickListener {
            requestScreenCapture()
        }
        findViewById<View>(R.id.itemStorage).setOnClickListener {
            requestStoragePermission()
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
                // 用对话框显示完整错误信息，避免 Toast 截断
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("截屏服务启动失败")
                    .setMessage(error.ifEmpty { "未知错误" })
                    .setPositiveButton("确定", null)
                    .setNeutralButton("查看诊断") { _, _ ->
                        val diag = ScreenCaptureService.instance?.getDiagnosticInfo()
                            ?: "截屏服务未运行"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("截屏诊断信息")
                            .setMessage(diag)
                            .setPositiveButton("确定", null)
                            .show()
                    }
                    .show()
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
        switchStorage.isChecked = hasStoragePermission()
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestStoragePermission() {
        if (hasStoragePermission()) {
            Toast.makeText(this, "存储权限已开启", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES), 1002)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1002)
        }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            switchStorage.isChecked = granted
            Toast.makeText(this, if (granted) "存储权限已开启" else "存储权限被拒绝", Toast.LENGTH_SHORT).show()
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
