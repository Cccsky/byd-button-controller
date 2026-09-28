package com.byd.buttoncontroller.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * 通过 WRITE_SECURE_SETTINGS 权限直接写系统安全设置（无障碍授权）。
 *
 * 权限获取方式（二选一，授予后持久有效、重启不丢）：
 * 1. App 内嵌 ADB 客户端自动执行一次 `pm grant`（[AuthRepairer]）；
 * 2. 电脑执行：adb shell pm grant com.byd.buttoncontroller android.permission.WRITE_SECURE_SETTINGS
 *
 * 授予后，恢复无障碍授权变成纯本地写入，不再依赖 ADB 握手/网络/用户弹窗，
 * 从根本上解决内嵌 ADB 认证偶发失败的问题。
 */
object SecureSettingsWriter {

    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * 把无障碍服务追加进 enabled_accessibility_services 并置 accessibility_enabled=1。
     * @return 设置是否写入成功（服务真正绑定需用 [AccessibilityUtil.isServiceEnabled] 验证）
     */
    fun writeAccessibilityService(context: Context, component: ComponentName): Boolean {
        return try {
            val cr = context.contentResolver
            val cur = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.trim().orEmpty()
            if (!listContains(cur, component)) {
                val flat = component.flattenToString()
                val newVal = if (cur.isEmpty()) flat else "$cur:$flat"
                if (!Settings.Secure.putString(
                        cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newVal
                    )
                ) return false
                AppLog.i("已直接写入无障碍服务列表: $newVal")
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (t: Throwable) {
            AppLog.e("直接写入安全设置失败（检查 WRITE_SECURE_SETTINGS 是否已授予）", t)
            false
        }
    }

    /** 判断已启用服务列表（冒号分隔，长短类名形式都可能出现）是否包含指定服务。 */
    fun listContains(enabled: String, component: ComponentName): Boolean {
        return enabled.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { entry ->
                val cn = ComponentName.unflattenFromString(entry) ?: return@any false
                cn.packageName == component.packageName && cn.className == component.className
            }
    }
}
