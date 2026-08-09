package com.byd.buttoncontroller.action

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.byd.buttoncontroller.config.MappingAction
import com.byd.buttoncontroller.service.MediaControlNotificationListener
import com.byd.buttoncontroller.util.AppLog

/**
 * 动作执行器：把一条 [MappingAction] 落地为实际系统操作。
 *
 * 实现为 object + 函数，由 AccessibilityService 在按键回调里同步调用。
 * 360 点击涉及控件树遍历，放在 Service 提供的 root 上执行。
 */
object ActionExecutor {

    /**
     * 执行动作。
     * @param service 当前 AccessibilityService（用于取 rootInActiveWindow 等）
     * @param action  要执行的动作
     * @return true 表示已处理（应吞键），false 表示未处理（透传）
     */
    fun execute(service: AccessibilityService, action: MappingAction): Boolean {
        return when (action) {
            is MappingAction.MediaPlayPause -> togglePlayPause(service)
            is MappingAction.ClickView360 -> clickView360Button(service, action.target, action.byId)
        }
    }

    // ---------------- M1: 播放/暂停 ----------------

    /**
     * 取当前活跃的第三方 MediaController，根据播放状态在 PLAY/PAUSE 间切换。
     *
     * 普通 App 的 getActiveSessions(null) 只能看到自己的会话；要控制第三方音乐
     * （车机自带的 mediacenter / 酷我等），需要本 App 开启「通知使用权」，
     * 之后用通知监听组件查询全部活跃会话。未开启时回退为 null 查询（仅自有会话）。
     */
    private fun togglePlayPause(context: Context): Boolean {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = getActiveControllers(context, msm)
            val target = controllers.firstOrNull { isInteresting(it) } ?: controllers.firstOrNull()
            if (target == null) {
                AppLog.w("播放/暂停：无活跃媒体会话（如音乐未播放，或未开启通知使用权）")
                return false
            }
            val state = target.playbackState
            val isPlaying = state != null && state.state == PlaybackState.STATE_PLAYING
            if (isPlaying) {
                target.transportControls.pause()
                AppLog.i("播放/暂停 -> 暂停 (${sessionLabel(target)})")
            } else {
                target.transportControls.play()
                AppLog.i("播放/暂停 -> 播放 (${sessionLabel(target)})")
            }
            true
        } catch (t: SecurityException) {
            AppLog.e("播放/暂停：无权限获取媒体会话", t)
            false
        } catch (t: Throwable) {
            AppLog.e("播放/暂停执行异常", t)
            false
        }
    }

    private fun getActiveControllers(
        context: Context,
        msm: MediaSessionManager
    ): List<MediaController> {
        // 通知使用权开启时，用监听组件查询可看到全部第三方会话
        if (isNotificationListenerEnabled(context)) {
            return try {
                msm.getActiveSessions(
                    ComponentName(context, MediaControlNotificationListener::class.java)
                )
            } catch (t: Throwable) {
                AppLog.w("按通知监听组件查会话失败，回退默认查询: ${t.message}")
                msm.getActiveSessions(null)
            }
        }
        return msm.getActiveSessions(null)
    }

    /** 检测本 App 的通知使用权（NotificationListenerService）是否已在系统设置开启。 */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = ComponentName(context, MediaControlNotificationListener::class.java).flattenToString()
        return Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )?.split(":")?.any { it.equals(flat, ignoreCase = true) } == true
    }

    private fun isInteresting(controller: MediaController): Boolean {
        // 排除无包名/系统 UI 类会话，优先有元数据或正在播放的
        val pkg = controller.packageName ?: return false
        if (pkg.startsWith("com.android.systemui")) return false
        return controller.playbackState != null || controller.metadata != null
    }

    private fun sessionLabel(controller: MediaController): String {
        val pkg = controller.packageName ?: "?"
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        return if (title.isNullOrEmpty()) pkg else "$pkg / $title"
    }

    // ---------------- M2-M7: 点击 360 视角按钮 ----------------

    /**
     * 在当前活跃窗口的控件树里定位 360 视角按钮并点击。
     *
     * [byId] 为 true 时优先按 resource-id 匹配（实车 360 按钮无文案，靠 resource-id），
     * 找不到时降级按文案匹配；为 false 时反之。若匹配节点本身不可点击，
     * 向上找最近的可点击祖先再点击。
     */
    fun clickView360Button(service: AccessibilityService, target: String, byId: Boolean): Boolean {
        if (target.isBlank()) {
            AppLog.w("360 视角按钮标识为空，跳过（请在设置页填入）")
            return false
        }
        val root = service.rootInActiveWindow ?: run {
            AppLog.w("360 点击：取不到 rootInActiveWindow")
            return false
        }
        val node = if (byId) {
            findNodeById(root, target) ?: findNodeByText(root, target)
        } else {
            findNodeByText(root, target) ?: findNodeById(root, target)
        } ?: run {
            AppLog.w("360 点击：未找到标识为「$target」的节点")
            return false
        }
        val clickable = findClickableAncestorOrSelf(node) ?: node
        val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        AppLog.i("360 点击「$target」-> $ok")
        return ok
    }

    /** 按 resource-id 查找（如 "com.byd.avc:id/hc_front_2d_button"）。 */
    private fun findNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        return try {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
        } catch (t: Throwable) {
            AppLog.w("按 id 查找节点异常: ${t.message}")
            null
        }
    }

    /** 递归查找 text 或 contentDescription 包含 [text] 的节点。 */
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (nodeText.contains(text) || desc.contains(text)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByText(child, text)?.let { return it }
        }
        return null
    }

    /** 找最近的可点击祖先（含自身）。 */
    private fun findClickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n
            n = n.parent
        }
        return null
    }
}

/**
 * 辅助：判断某包名对应的 App 是否安装（用于设置页校验 360 包名）。
 */
fun isPackageInstalled(context: Context, pkg: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * 辅助：用无障碍当前包名回填 360 包名时，提供一个解析当前栈顶包名的入口。
 * 仅用于 UI「检测当前前台包名」按钮的提示文案。
 */
fun launchableLabel(context: Context, pkg: String): String {
    return try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
