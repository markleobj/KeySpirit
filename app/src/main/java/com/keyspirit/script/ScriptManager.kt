package com.keyspirit.script

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ScriptManager(private val context: Context) {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("keyspirit_scripts", Context.MODE_PRIVATE)

    fun getAllScripts(): List<Script> {
        val json = prefs.getString("scripts", null) ?: return emptyList()
        val type = object : TypeToken<List<Script>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveScript(script: Script) {
        val scripts = getAllScripts().toMutableList()
        val index = scripts.indexOfFirst { it.id == script.id }
        script.updatedAt = System.currentTimeMillis()
        if (index >= 0) {
            scripts[index] = script
        } else {
            scripts.add(script)
        }
        saveAll(scripts)
    }

    fun deleteScript(id: String) {
        val scripts = getAllScripts().toMutableList()
        scripts.removeAll { it.id == id }
        saveAll(scripts)
    }

    fun getScript(id: String): Script? {
        return getAllScripts().firstOrNull { it.id == id }
    }

    fun markRun(id: String) {
        val script = getScript(id) ?: return
        script.lastRunAt = System.currentTimeMillis()
        saveScript(script)
    }

    private fun saveAll(scripts: List<Script>) {
        prefs.edit().putString("scripts", gson.toJson(scripts)).apply()
    }
}
