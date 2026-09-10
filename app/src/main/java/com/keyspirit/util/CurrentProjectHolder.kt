package com.keyspirit.util

/**
 * 持有当前激活的项目（脚本）ID，供悬浮窗截图时使用
 */
object CurrentProjectHolder {
    @Volatile
    var currentScriptId: String? = null

    @Volatile
    var currentScriptName: String? = null
}
