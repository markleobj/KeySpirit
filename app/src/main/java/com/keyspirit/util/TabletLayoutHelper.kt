package com.keyspirit.util

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * 平板布局适配助手
 * 在平板上限制内容区域宽度并居中，避免内容拉伸过宽
 */
object TabletLayoutHelper {

    /**
     * 在 Activity 的 onCreate 中调用，限制内容区域最大宽度
     * 手机上不做任何操作，平板上包裹内容并居中
     */
    fun applyMaxWidth(activity: Activity) {
        val maxW = activity.resources.getDimensionPixelSize(com.keyspirit.R.dimen.content_max_width)
        if (maxW <= 0) return

        val screenWidth = activity.resources.displayMetrics.widthPixels
        if (maxW >= screenWidth) return

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root.childCount == 0) return
        val child = root.getChildAt(0)

        val wrapper = FrameLayout(activity)
        root.removeAllViews()
        wrapper.addView(child, FrameLayout.LayoutParams(maxW, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(wrapper)
    }
}
