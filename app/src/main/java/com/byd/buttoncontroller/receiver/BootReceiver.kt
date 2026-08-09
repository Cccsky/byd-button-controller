package com.byd.buttoncontroller.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.byd.buttoncontroller.R
import com.byd.buttoncontroller.config.ConfigRepository
import com.byd.buttoncontroller.service.KeepAliveService
import com.byd.buttoncontroller.service.KeyMapAccessibilityService
import com.byd.buttoncontroller.util.AccessibilityUtil
import com.byd.buttoncontroller.util.AppLog
import com.byd.buttoncontroller.util.AuthRepairer
import kotlin.concurrent.thread

/**
 * 开机自启 + 无障碍授权自动修复。
 *
 * 实车问题：DiLink 车机重启会清除第三方 App（非预装）的无障碍授权。
 * 本接收器开机后延迟检测：
 * 1. 授权丢失且车机 ADB(127.0.0.1:5555) 可用 -> 用内置 ADB 客户端自动静默修复
 *    （无需电脑；前提是首次已在车机弹窗点过允许，key 已信任）。
 * 2. 自动修复失败 -> 发「一键重新授权」通知，点击直达系统无障碍设置。
 *
 * 注意：收到 BOOT_COMPLETED 的前提是 App 已被加入 DiLink「自启动管理」白名单，
 * 否则开机广播不会送达（App 内「授权助手 - 打开自启动管理」可放行）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ConfigRepository.init(context)
        AppLog.i("收到开机广播，启动保活服务")
        KeepAliveService.start(context)

        // 等系统完全启动 + adbd 就绪后再检测（12 秒）
        Handler(Looper.getMainLooper()).postDelayed({
            autoRepair(context)
        }, 12_000L)
    }

    private fun autoRepair(context: Context) {
        val ok = try {
            AccessibilityUtil.isServiceEnabled(context, KeyMapAccessibilityService::class.java)
        } catch (t: Throwable) {
            AppLog.w("开机检测无障碍异常: ${t.message}")
            false
        }
        if (ok) {
            AppLog.i("开机检测：无障碍授权正常")
            return
        }
        AppLog.w("开机检测：无障碍授权已丢失，尝试无线 ADB 自动修复")
        thread {
            val result = AuthRepairer.repair(context.applicationContext)
            AppLog.i("开机自动修复结果: ok=${result.ok} msg=${result.message}")
            if (!result.ok) {
                Handler(Looper.getMainLooper()).post {
                    showRepairNotification(context)
                }
            }
        }
    }

    private fun showRepairNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "授权修复", NotificationManager.IMPORTANCE_HIGH
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }

        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("方向盘按键映射授权已丢失")
            .setContentText("自动修复未成功，点击重新开启无障碍服务")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        try {
            nm.notify(NOTIF_ID, notification)
            AppLog.i("已发送授权修复通知")
        } catch (t: Throwable) {
            AppLog.e("发送授权修复通知失败", t)
        }
    }

    companion object {
        private const val CHANNEL_ID = "auth_repair"
        private const val NOTIF_ID = 1001
    }
}
