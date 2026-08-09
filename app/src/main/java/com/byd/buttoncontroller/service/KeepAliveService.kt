package com.byd.buttoncontroller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.byd.buttoncontroller.R
import com.byd.buttoncontroller.service.KeyMapAccessibilityService
import com.byd.buttoncontroller.util.AccessibilityUtil
import com.byd.buttoncontroller.util.AppLog
import com.byd.buttoncontroller.util.AuthRepairer
import kotlin.concurrent.thread

/**
 * 前台保活服务。本身不做按键逻辑（那是无障碍服务的职责），
 * 主要作用：开机自启载体 + 进程保活 + 无障碍未开启时通过通知引导用户。
 *
 * 额外职责：运行期间周期检测无障碍授权，丢失则用无线 ADB 自动修复
 * （覆盖 DiLink 运行中清除授权的场景；开机自动修复由 BootReceiver 负责）。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** 周期授权守护任务：每 60 秒检测一次。 */
    private val authGuard = object : Runnable {
        override fun run() {
            try {
                val enabled = AccessibilityUtil.isServiceEnabled(
                    this@KeepAliveService, KeyMapAccessibilityService::class.java
                )
                if (!enabled) {
                    AppLog.w("保活服务检测到无障碍授权丢失，尝试无线 ADB 自动修复")
                    thread { AuthRepairer.repair(applicationContext) }
                }
            } catch (t: Throwable) {
                AppLog.w("保活服务授权检测异常: ${t.message}")
            }
            handler.postDelayed(this, AUTH_GUARD_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        handler.postDelayed(authGuard, AUTH_GUARD_INTERVAL_MS)
        AppLog.i("保活服务已启动（授权守护已启用）")
    }

    override fun onDestroy() {
        handler.removeCallbacks(authGuard)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // 被杀后尽量重启
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "保活服务", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("按键映射运行中")
            .setContentText("方向盘按键映射服务保持运行")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIF_ID = 1
        private const val AUTH_GUARD_INTERVAL_MS = 60_000L

        /** 从 Activity 或 BootReceiver 启动本服务。 */
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
