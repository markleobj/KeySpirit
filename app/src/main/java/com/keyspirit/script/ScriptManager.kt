package com.keyspirit.script

import android.content.Context
import com.google.gson.Gson
import java.io.File

class ScriptManager(private val context: Context) {

    private val gson = Gson()

    // 所有项目的根目录
    private val projectsDir: File
        get() = File(context.filesDir, "projects").apply {
            if (!exists()) mkdirs()
        }

    /**
     * 获取指定脚本的项目目录
     */
    fun getProjectDir(scriptId: String): File {
        return File(projectsDir, scriptId).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * 获取指定脚本的截图目录
     */
    fun getScreenshotsDir(scriptId: String): File {
        return File(getProjectDir(scriptId), "screenshots").apply {
            if (!exists()) mkdirs()
        }
    }

    fun getAllScripts(): List<Script> {
        val scripts = mutableListOf<Script>()
        projectsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val scriptFile = File(dir, "script.json")
                if (scriptFile.exists()) {
                    try {
                        val script = gson.fromJson(scriptFile.readText(), Script::class.java)
                        // Gson 可能绕过构造函数导致 steps 为 null，兜底初始化
                        ensureStepsNotNull(script)
                        scripts.add(script)
                    } catch (e: Exception) {
                        // 忽略损坏的脚本
                    }
                }
            }
        }
        return scripts
    }

    fun saveScript(script: Script) {
        script.updatedAt = System.currentTimeMillis()
        val projectDir = getProjectDir(script.id)
        val scriptFile = File(projectDir, "script.json")
        scriptFile.writeText(gson.toJson(script))
    }

    fun deleteScript(id: String) {
        val projectDir = getProjectDir(id)
        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }
    }

    fun getScript(id: String): Script? {
        val scriptFile = File(getProjectDir(id), "script.json")
        return if (scriptFile.exists()) {
            try {
                val script = gson.fromJson(scriptFile.readText(), Script::class.java)
                // Gson 可能绕过构造函数导致 steps 为 null，兜底初始化
                ensureStepsNotNull(script)
                script
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    /**
     * 确保 script.steps 不为 null（Gson 反序列化可能绕过构造函数）
     */
    private fun ensureStepsNotNull(script: Script) {
        @Suppress("SENSELESS_COMPARISON")
        if (script.steps == null) {
            script.steps = mutableListOf()
        }
    }

    fun markRun(id: String) {
        val script = getScript(id) ?: return
        script.lastRunAt = System.currentTimeMillis()
        saveScript(script)
    }

    /**
     * 列出指定脚本目录下的所有图片文件
     */
    fun listScreenshots(scriptId: String): List<File> {
        val dir = getScreenshotsDir(scriptId)
        return dir.listFiles { file ->
            file.extension.lowercase() in listOf("png", "jpg", "jpeg", "bmp")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
