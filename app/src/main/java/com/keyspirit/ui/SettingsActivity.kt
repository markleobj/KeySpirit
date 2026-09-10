package com.keyspirit.ui

import android.content.Intent
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

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchAccessibility: Switch
    private lateinit var switchFloating: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchAccessibility = findViewById(R.id.switchAccessibility)
        switchFloating = findViewById(R.id.switchFloating)

        findViewById<View>(R.id.itemAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }
        findViewById<View>(R.id.itemFloatingPermission).setOnClickListener {
            openFloatingPermissionSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        switchAccessibility.isChecked = AutoAccessibilityService.isRunning()
        switchFloating.isChecked = canDrawOverlays()
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
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
    }
}
