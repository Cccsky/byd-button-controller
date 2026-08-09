package com.byd.buttoncontroller.util

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * ADB 授权相关辅助：本机 IP 获取、5555 端口探测、授权命令生成。
 *
 * 说明：车机上的普通 App 无法执行 adb，首次授权必须由电脑执行一次。
 * 本工具用于：检测车机是否已开启 ADB 网络调试、生成可粘贴到电脑的授权命令。
 */
object AdbHelper {

    const val ADB_PORT = 5555
    const val ACCESSIBILITY_SERVICE = "com.byd.buttoncontroller/.service.KeyMapAccessibilityService"
    const val PACKAGE_NAME = "com.byd.buttoncontroller"

    /** 获取本机所有非回环 IPv4 地址（车机可能有多个网卡）。 */
    fun localIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val nis = NetworkInterface.getNetworkInterfaces() ?: return result
            for (ni in nis) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { result.add(it) }
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.w("获取本机 IP 失败: ${t.message}")
        }
        return result.distinct()
    }

    /** 探测某主机 ADB 端口（5555）是否开放。TCP connect 成功即视为已开启。 */
    fun isAdbPortOpen(host: String, timeoutMs: Int = 1200): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, ADB_PORT), timeoutMs)
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 生成可在电脑上直接执行的授权命令（仅可执行行，Windows/Mac 通用）。
     *
     * @param ip              车机 IP（用于 adb connect）
     * @param enabledServices 车机当前已开启的无障碍服务列表（原始字符串，可为空）
     * @param apkOnDevice     若已在车机 Download 导出 APK，填其路径（如
     *                        /sdcard/Download/byd-button-controller.apk），
     *                        则安装命令改用车机端安装；否则用电脑端路径占位符
     */
    fun buildGrantCommands(ip: String, enabledServices: String?, apkOnDevice: String?): String {
        val sb = StringBuilder()
        sb.append("adb connect $ip:5555\n")
        sb.append("adb -s $ip:5555 wait-for-device\n")

        if (apkOnDevice != null) {
            // APK 已在车机 Download 目录：车机端直接安装（电脑上无需 APK 文件）
            sb.append("adb -s $ip:5555 shell pm install -r \"$apkOnDevice\"\n")
        } else {
            // 需要电脑上有 APK：占位符，用户替换为实际路径
            sb.append("adb -s $ip:5555 install -r \"<电脑上 APK 的完整路径>\"\n")
        }

        val cur = enabledServices?.trim().orEmpty()
        val alreadyGranted = cur.contains(PACKAGE_NAME)
        if (!alreadyGranted) {
            val newVal = if (cur.isEmpty()) ACCESSIBILITY_SERVICE else "$cur:$ACCESSIBILITY_SERVICE"
            sb.append("adb -s $ip:5555 shell settings put secure enabled_accessibility_services \"$newVal\"\n")
            sb.append("adb -s $ip:5555 shell settings put secure accessibility_enabled 1\n")
        }

        sb.append("adb -s $ip:5555 shell dumpsys accessibility | grep byd\n")
        return sb.toString()
    }
}
