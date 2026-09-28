package com.byd.buttoncontroller.util

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * ADB 授权相关辅助：本机 IP 获取、5555 端口探测、电脑端授权命令生成。
 *
 * 自动授权机制：App 持有 WRITE_SECURE_SETTINGS 后可自行恢复无障碍授权；
 * 该权限用 adb 执行一次 `pm grant` 即可获得（App 内嵌 ADB 自动做，
 * 失败时用这里生成的命令在电脑上手动执行一次）。
 */
object AdbHelper {

    const val ADB_PORT = 5555
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
     * 生成电脑端一次性授权命令（Windows/Mac 通用，仅可执行行）。
     * 核心是 pm grant 授予写安全设置权限；若该命令不被支持，附兼容的无障碍直写命令。
     */
    fun buildPcGrantCommand(ip: String): String {
        val sb = StringBuilder()
        sb.append("adb connect $ip:5555\n")
        sb.append("adb -s $ip:5555 shell pm grant $PACKAGE_NAME ${SecureSettingsWriter.PERMISSION}\n")
        sb.append("# 若上一行报错（老系统不支持），改用兼容命令直接开无障碍：\n")
        sb.append("# adb -s $ip:5555 shell settings put secure enabled_accessibility_services \"${AuthRepairer.SERVICE_COMPONENT}\"\n")
        sb.append("# adb -s $ip:5555 shell settings put secure accessibility_enabled 1\n")
        return sb.toString()
    }
}
