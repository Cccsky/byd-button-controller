package com.byd.buttoncontroller.util

import android.content.ComponentName
import android.content.Context
import com.byd.buttoncontroller.service.KeyMapAccessibilityService
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 无障碍授权自动修复器。
 *
 * 修复链路（按优先级）：
 * 1. [SecureSettingsWriter]：已持有 WRITE_SECURE_SETTINGS 时直接写系统设置，
 *    纯本地操作、秒级完成，重启丢授权后由保活/开机广播静默恢复；
 * 2. 内嵌 ADB（[AdbAuth]）执行一次性 `pm grant` 获取上述权限（首次需车机弹窗点允许），
 *    授权成功后以后都走链路 1；
 * 3. ADB 兼容兜底：直接用 shell 写无障碍设置（老链路，偶发失败时自动重试）。
 *
 * 三条链路都失败时，结果消息里会带上可在电脑执行的一条命令，手动救回。
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
     * @return 结果（ok=true 表示无障碍服务已开启/已写入生效）
     */
    fun repair(context: Context): Result {
        val service = ComponentName(context, KeyMapAccessibilityService::class.java)

        if (AccessibilityUtil.isServiceEnabled(context, KeyMapAccessibilityService::class.java)) {
            return Result(true, "无障碍服务已开启，无需修复")
        }

        // 链路 1：已持有写安全设置权限 -> 直接写 + 轮询验证
        if (SecureSettingsWriter.hasPermission(context)) {
            AppLog.i("授权修复: 持有 WRITE_SECURE_SETTINGS，直接写入")
            val ok = writeAndVerify(context, service)
            if (ok) return Result(true, "授权已恢复 ✓")
            AppLog.w("直接写入未生效，转 ADB 兜底")
        }

        // 链路 2/3：内嵌 ADB
        return repairViaAdb(context, service)
    }

    /** 链路 1：直接写安全设置并轮询服务绑定（最多约 5 秒）。 */
    private fun writeAndVerify(context: Context, service: ComponentName): Boolean {
        if (!SecureSettingsWriter.writeAccessibilityService(context, service)) return false
        repeat(VERIFY_TIMES) {
            if (AccessibilityUtil.isServiceEnabled(context, KeyMapAccessibilityService::class.java)) {
                AppLog.i("无障碍服务已绑定生效")
                return true
            }
            Thread.sleep(VERIFY_INTERVAL_MS)
        }
        // 绑定稍慢但设置已写入：只要设置里有本服务即视为成功（服务随后由系统拉起）
        val cur = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        } catch (t: Throwable) {
            ""
        }
        val written = SecureSettingsWriter.listContains(cur, service)
        if (written) AppLog.i("设置已写入，服务绑定稍有延迟")
        return written
    }

    /** 链路 2/3：通过内嵌 ADB 先做一次性 pm grant，失败再用 shell 直接写。 */
    private fun repairViaAdb(context: Context, service: ComponentName): Result {
        val host = findAdbHost()
            ?: return Result(false, "车机 ADB 5555 端口未开放，无法自动修复。" +
                    "\n可在开发者选项开启「ADB 网络调试」，或在电脑执行一次授权命令")
        AppLog.i("ADB 修复: 使用本机 IP $host")

        val kp = try {
            AdbAuth.loadOrCreateKeyPair(File(context.filesDir, "adbkey"))
        } catch (t: Throwable) {
            AppLog.e("生成 ADB 密钥失败", t)
            return Result(false, "ADB 密钥生成失败: ${t.message}")
        }

        // 链路 2：一次性 pm grant（授予后持久有效，以后修复都走链路 1）
        try {
            adbExecRetry(host, kp, "pm grant $PACKAGE ${SecureSettingsWriter.PERMISSION}")
            AppLog.i("已通过 ADB 授予 WRITE_SECURE_SETTINGS")
            if (SecureSettingsWriter.hasPermission(context)) {
                if (writeAndVerify(context, service)) {
                    return Result(true, "已获得写安全设置权限，授权已恢复 ✓\n以后重启会自动静默恢复，无需再弹窗")
                }
            }
        } catch (t: Throwable) {
            AppLog.w("pm grant 失败（首次授权需在车机弹窗点允许）: ${t.message}")
        }

        // 链路 3：兼容兜底——shell 直接写设置（重试 2 次）
        try {
            val cur = adbExecRetry(host, kp,
                "settings get secure enabled_accessibility_services").trim()
            AppLog.i("ADB 读取当前无障碍设置: [$cur]")
            if (!cur.contains(PACKAGE)) {
                val newVal = if (cur.isEmpty()) SERVICE_COMPONENT else "$cur:$SERVICE_COMPONENT"
                var written = false
                repeat(2) {
                    adbExecRetry(host, kp,
                        "settings put secure enabled_accessibility_services \"$newVal\"")
                    adbExecRetry(host, kp, "settings put secure accessibility_enabled 1")
                    val back = adbExecRetry(host, kp,
                        "settings get secure enabled_accessibility_services").trim()
                    written = back.contains(PACKAGE)
                    if (written) return@repeat
                    Thread.sleep(600)
                }
                if (!written) return Result(false, "已写入但读回校验失败（偶发），请重试一次")
                AppLog.i("已通过 ADB 写入无障碍授权: $newVal")
            }
            return Result(true, "授权已恢复 ✓（ADB 兜底链路）\n建议重试「自动获取权限」以获得永久自动修复")
        } catch (t: Throwable) {
            AppLog.e("自动修复授权失败", t)
            return Result(false, "自动修复失败: ${t.message}" +
                    "\n备用方案：在电脑执行授权助手页的「电脑授权命令」")
        }
    }

    /** shell 命令带重试（偶发认证/连接失败时自动重连）。 */
    private fun adbExecRetry(host: String, kp: java.security.KeyPair, command: String): String {
        var last: Throwable? = null
        repeat(ADB_RETRY_TIMES) { i ->
            try {
                return AdbAuth.execShell(host, AdbAuth.ADB_PORT, kp, command)
            } catch (t: Throwable) {
                last = t
                AppLog.w("ADB 命令第 ${i + 1} 次失败: ${t.message}")
                if (i < ADB_RETRY_TIMES - 1) Thread.sleep(ADB_RETRY_DELAY_MS)
            }
        }
        throw last ?: IllegalStateException("ADB 命令失败")
    }

    private const val VERIFY_TIMES = 6
    private const val VERIFY_INTERVAL_MS = 800L
    private const val ADB_RETRY_TIMES = 3
    private const val ADB_RETRY_DELAY_MS = 900L
}
