package com.byd.buttoncontroller.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍服务开启状态检测。
 *
 * 优先用 AccessibilityManager.getEnabledAccessibilityServiceList()（公开 API，可靠），
 * 失败时降级读 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES。
 * （部分 ROM——如本车机 DiLink——对普通 App 读 Secure 设置有限制，导致误判。）
 */
object AccessibilityUtil {

    fun isServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expected = ComponentName(context, serviceClass)

        // 方式 1：AccessibilityManager（公开 API，最可靠）
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                val hit = list.any { info ->
                    val si = info.resolveInfo?.serviceInfo
                    si != null && si.packageName == expected.packageName && si.name == expected.className
                }
                return hit
            }
        } catch (t: Throwable) {
            AppLog.w("AccessibilityManager 检测失败，降级读设置: ${t.message}")
        }

        // 方式 2：读 Settings.Secure
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expectedFlat = expected.flattenToString()
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            while (splitter.hasNext()) {
                if (splitter.next().equals(expectedFlat, ignoreCase = true)) return true
            }
            false
        } catch (t: Throwable) {
            AppLog.w("读取无障碍设置异常: ${t.message}")
            false
        }
    }

    /** 跳转到系统无障碍设置页。 */
    fun openAccessibilitySettings(context: Context) {
        val intent = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
