package com.keyspirit.util

import android.content.Context
import android.util.TypedValue

/**
 * 屏幕适配工具类
 * 所有代码中动态创建的 View 都应使用 dp 而非 px
 */
object UiUtils {

    /** dp 转 px */
    fun dp2px(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    /** sp 转 px */
    fun sp2px(context: Context, sp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            context.resources.displayMetrics
        ).toInt()
    }

    /** 获取屏幕宽度 (px) */
    fun getScreenWidth(context: Context): Int {
        return context.resources.displayMetrics.widthPixels
    }

    /** 获取屏幕高度 (px) */
    fun getScreenHeight(context: Context): Int {
        return context.resources.displayMetrics.heightPixels
    }

    /** 获取屏幕密度 */
    fun getDensity(context: Context): Float {
        return context.resources.displayMetrics.density
    }

    /** 获取屏幕宽度 (dp) */
    fun getScreenWidthDp(context: Context): Int {
        return (getScreenWidth(context) / getDensity(context)).toInt()
    }

    /** 获取屏幕高度 (dp) */
    fun getScreenHeightDp(context: Context): Int {
        return (getScreenHeight(context) / getDensity(context)).toInt()
    }

    /** 判断是否为平板（宽度 >= 600dp） */
    fun isTablet(context: Context): Boolean {
        return getScreenWidthDp(context) >= 600
    }

    /**
     * 获取内容区域最大宽度 (px)
     * 平板上限制内容宽度居中显示，手机上返回屏幕宽度
     */
    fun getContentMaxWidth(context: Context): Int {
        val maxW = context.resources.getDimensionPixelSize(
            com.keyspirit.R.dimen.content_max_width
        )
        return if (maxW > 0) maxW else getScreenWidth(context)
    }
}
