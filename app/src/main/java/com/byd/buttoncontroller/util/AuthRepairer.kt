package com.byd.buttoncontroller.util

import android.content.Context
import com.byd.buttoncontroller.service.KeyMapAccessibilityService
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 无障碍授权自动修复器。
 *
 * 原理：App 内置轻量 ADB 客户端（[AdbAuth]），通过无线 ADB 连接车机自身
 * （本机局域网 IP:5555，实测车机 adbd 不监听 127.0.0.1 回环），认证后用 shell
 * 重新写入无障碍授权设置——解决 DiLink 车机重启清除第三方 App 无障碍授权的问题。
 *
 * 首次使用：需在车机弹窗点一次「允许」（key 持久化后免确认）；
 * 之后开机检测到授权丢失会自动静默修复，全程无需电脑。
 */
object AuthRepairer {

    const val SERVICE_COMPONENT = "com.byd.buttoncontroller/.service.KeyMapAccessibilityService"
    const val PACKAGE = "com.byd.buttoncontroller"

    data class Result(val ok: Boolean, val message: String)

    /** 取车机本机可连的局域网 IPv4（ADB 5555 端口开放的那个）。 */
    fun findAdbHost(timeoutMs: Int = 800): String? {
        for (ip in AdbHelper.localIpv4Addresses()) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, AdbAuth.ADB_PORT), timeoutMs)
                    return ip
                }
            } catch (t: Throwable) {
                // 尝试下一个
            }
        }
        return null
    }

    /** 探测车机自身 ADB 5555 端口是否开放。 */
    fun isAdbAvailable(timeoutMs: Int = 800): Boolean = findAdbHost(timeoutMs) != null

    /**
     * 执行授权修复。
     * @return 结果（ok=true 表示授权已恢复）
     */
    fun repair(context: Context): Result {
        // 已授权则无需修复
        if (AccessibilityUtil.isServiceEnabled(context, KeyMapAccessibilityService::class.java)) {
            return Result(true, "无障碍服务已开启，无需修复")
        }
        val host = findAdbHost()
        if (host == null) {
            return Result(false, "车机 ADB 5555 端口未开放，无法自修复。\n请在开发者选项开启「ADB 网络调试」")
        }
        AppLog.i("ADB 自修复: 使用本机 IP $host")

        val kp = try {
            AdbAuth.loadOrCreateKeyPair(File(context.filesDir, "adbkey"))
        } catch (t: Throwable) {
            AppLog.e("生成 ADB 密钥失败", t)
            return Result(false, "ADB 密钥生成失败: ${t.message}")
        }

        return try {
            val cur = AdbAuth.execShell(host, AdbAuth.ADB_PORT, kp,
                "settings get secure enabled_accessibility_services").trim()
            AppLog.i("ADB 读取当前无障碍设置: [$cur]")

            val already = cur.contains(PACKAGE)
            if (!already) {
                val newVal = if (cur.isEmpty()) SERVICE_COMPONENT else "$cur:$SERVICE_COMPONENT"
                AdbAuth.execShell(host, AdbAuth.ADB_PORT, kp,
                    "settings put secure enabled_accessibility_services \"$newVal\"")
                AdbAuth.execShell(host, AdbAuth.ADB_PORT, kp,
                    "settings put secure accessibility_enabled 1")
                AppLog.i("已通过 ADB 写入无障碍授权: $newVal")
            }

            // 验证（读回设置确认，服务绑定可能有延迟）
            Thread.sleep(1200)
            val verify = AdbAuth.execShell(host, AdbAuth.ADB_PORT, kp,
                "settings get secure enabled_accessibility_services").trim()
            val ok = verify.contains(PACKAGE)
            if (ok) AppLog.i("授权修复完成，当前: $verify")
            Result(ok, if (ok) "授权已自动恢复 ✓" else "已写入但未生效，请重试")
        } catch (t: Throwable) {
            AppLog.e("自动修复授权失败", t)
            Result(false, t.message ?: "修复失败")
        }
    }
}
