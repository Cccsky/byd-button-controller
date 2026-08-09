package com.byd.buttoncontroller.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.byd.buttoncontroller.util.AppLog

/**
 * 通知监听服务。
 *
 * 用途：本 App 要控制第三方音乐 App（车机自带 mediacenter / 酷我等）的播放/暂停，
 * 需要「通知使用权」。系统开启后，ActionExecutor 才能通过
 * MediaSessionManager.getActiveSessions(本组件) 看到全部活跃媒体会话。
 *
 * 本类无需处理具体通知内容，只作为「已授权」的标记组件。
 */
class MediaControlNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        AppLog.i("通知监听已连接，M1 可控制第三方音乐会话")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // 无需处理
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 无需处理
    }
}
