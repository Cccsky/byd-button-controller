package com.byd.buttoncontroller.util

import android.util.Log

/**
 * 统一日志入口。tag 固定，便于 adb logcat 过滤：`logcat -s BYD-BtnMap`。
 */
object AppLog {
    private const val TAG = "BYD-BtnMap"

    fun d(msg: String) = Log.d(TAG, msg)
    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String) = Log.w(TAG, msg)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}
