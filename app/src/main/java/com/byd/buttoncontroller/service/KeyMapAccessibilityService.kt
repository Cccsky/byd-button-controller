package com.byd.buttoncontroller.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.byd.buttoncontroller.action.ActionExecutor
import com.byd.buttoncontroller.config.ConfigRepository
import com.byd.buttoncontroller.config.KeyMapping
import com.byd.buttoncontroller.util.AccessibilityUtil
import com.byd.buttoncontroller.util.AppLog
import com.byd.buttoncontroller.util.AuthRepairer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * 核心无障碍服务：全局按键捕获 + 长短按判定 + 条件式吞键 + 360 界面检测 + 动作执行。
 *
 * 三大职责：
 * 1. [onAccessibilityEvent]: 监听窗口切换，维护「当前是否在 360 影像界面」。
 * 2. [onKeyEvent]: 捕获方向盘按键，区分短按/长按，按映射条件执行动作或透传。
 * 3. 通过 [ActionExecutor] 执行播放/暂停与 360 视角按钮点击。
 *
 * 吞键策略（2026-08-06 实车两次修正）：
 * - 按键有映射且「映射 enabled + 条件满足」时，DOWN 即吞（返回 true），
 *   避免系统默认行为（如音量键被同时调音量）；
 * - 条件不满足（如不在 360 时按音量键）则 DOWN/UP 全透传，保留系统默认行为；
 * - 按键按下期间的系统 repeat 事件同样吞掉（防止音量连发）。
 *
 * 长短按判定：
 * - DOWN 时若有长按映射，启动定时器（阈值见配置），到点触发长按动作并标记已处理。
 * - UP 时：若长按已触发 -> 仅吞键收尾；否则触发短按动作。
 *   （注意：无长按映射的纯短按键——如 M1/M2/M3——也要在 UP 触发短按，
 *    不能因为没启动过定时器就吞键不动作。此为实车修复点。）
 */
class KeyMapAccessibilityService : AccessibilityService() {

    /** 当前前台是否为 360 影像 App。由 onAccessibilityEvent / 活跃窗口刷新维护。 */
    @Volatile
    private var isOn360 = false

    /** 最近一次窗口事件里的包名（活跃窗口拿不到时的兜底）。 */
    @Volatile
    private var lastEventPkg: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 正在按下且已被本服务吞掉的 keycode（DOWN 已吞，等待 UP）。 */
    private val consumedDown = ConcurrentHashMap.newKeySet<Int>()

    /** 长按已触发过的 keycode（UP 时据此判断只收尾不触发短按）。 */
    private val longFired = ConcurrentHashMap.newKeySet<Int>()

    /** 每个正在按下且待判长短按的 keycode -> 其长按定时任务。 */
    private val pending = ConcurrentHashMap<Int, Runnable>()

    // ---------------- 360 界面检测 ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        AppLog.d("A11Y 窗口切换: pkg=$pkg cls=$cls")
        if (pkg.isNotEmpty()) lastEventPkg = pkg
        reportForeground(pkg)
        refresh360State()
    }

    override fun onInterrupt() {
        AppLog.w("无障碍服务被中断")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 确保 flagRequestFilterKeyEvents 开启（xml 已配，运行时再保险一次）
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        AppLog.i("无障碍服务已连接，按键过滤已开启")

        // 延迟检测授权：DiLink 可能在服务启动后一段时间清除第三方无障碍授权，
        // 这里在服务连接后延迟检测，丢失则用无线 ADB 自动修复（无需电脑）。
        mainHandler.postDelayed({
            try {
                val enabled = AccessibilityUtil.isServiceEnabled(
                    this, KeyMapAccessibilityService::class.java
                )
                if (!enabled) {
                    AppLog.w("检测到无障碍授权丢失，尝试无线 ADB 自动修复")
                    thread { AuthRepairer.repair(applicationContext) }
                }
            } catch (t: Throwable) {
                AppLog.w("授权延迟检测异常: ${t.message}")
            }
        }, 20_000L)
    }

    /**
     * 扫描当前无障碍窗口列表，判断是否处于 360 影像界面。
     *
     * 实车（DiLink / Android 10）上无障碍服务收不到 TYPE_WINDOW_STATE_CHANGED
     * 事件（实测日志无任何前台切换），故每次按键处理前主动用 getWindows()
     * 扫描所有可见窗口的包名来刷新状态。
     */
    private fun refresh360State() {
        val targetPkg = ConfigRepository.get().snapshot().packageName360
        if (targetPkg.isEmpty()) {
            if (isOn360) AppLog.d("360状态: 目标包名为空，置为 false")
            isOn360 = false
            return
        }
        var pkg = ""
        try {
            pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        } catch (t: Throwable) {
            AppLog.w("360状态刷新异常: ${t.message}")
        }
        // 活跃窗口拿不到时，退回到最近一次窗口事件记录的包名
        if (pkg.isEmpty()) pkg = lastEventPkg
        if (pkg.isEmpty()) {
            AppLog.d("360状态: 拿不到活跃窗口包名，保持 $isOn360")
            return
        }
        val nowOn360 = pkg == targetPkg
        if (isOn360 != nowOn360) {
            AppLog.d("360状态: 活跃窗口 pkg=$pkg -> $nowOn360")
            isOn360 = nowOn360
        }
    }

    // ---------------- 按键捕获与长短按判定 ----------------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val actStr = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> "${event.action}"
        }
        AppLog.d("KEYEVENT keyCode=${event.keyCode} action=$actStr repeat=${event.repeatCount} " +
                "scanCode=${event.scanCode} deviceId=${event.deviceId} flags=${event.flags}")

        val keyCode = event.keyCode

        // 按键处理前刷新 360 状态（以活跃窗口包名为准）
        refresh360State()
        val config = ConfigRepository.get().snapshot()

        // 已吞掉的键的 repeat 事件继续吞（防止音量连发/系统重复行为）
        if (event.repeatCount > 0 && consumedDown.contains(keyCode)) return true

        val shortMaps = config.mapsFor(keyCode, longPress = false)
        val longMaps = config.mapsFor(keyCode, longPress = true)

        if (shortMaps.isEmpty() && longMaps.isEmpty()) return false // 该键无映射，透传

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onDown(keyCode, shortMaps, longMaps)
            KeyEvent.ACTION_UP -> onUp(keyCode, shortMaps)
            KeyEvent.ACTION_MULTIPLE -> consumedDown.contains(keyCode)
            else -> consumedDown.contains(keyCode)
        }
    }

    private fun onDown(
        keyCode: Int,
        shortMaps: List<KeyMapping>,
        longMaps: List<KeyMapping>
    ): Boolean {
        val activeShort = pickActive(shortMaps)
        val activeLong = pickActive(longMaps)
        if (activeShort == null && activeLong == null) {
            AppLog.d("按键 $keyCode 无满足条件的映射，透传 (在360=$isOn360)")
            return false
        }

        consumedDown.add(keyCode)
        if (activeLong != null) {
            // 启动长按定时器
            val threshold = ConfigRepository.get().snapshot().longPressThresholdMs
            val task = Runnable { handleLongPress(keyCode, activeLong) }
            pending[keyCode] = task
            mainHandler.postDelayed(task, threshold)
        }
        AppLog.d("按键 $keyCode 已吞DOWN (短=${activeShort?.id ?: "-"} 长=${activeLong?.id ?: "-"})")
        // 吞 DOWN，避免系统默认行为（音量调节、切歌等）
        return true
    }

    private fun onUp(
        keyCode: Int,
        shortMaps: List<KeyMapping>
    ): Boolean {
        val wasConsumed = consumedDown.remove(keyCode)
        if (!wasConsumed) return false // 没吞过 DOWN，UP 也透传

        val task = pending.remove(keyCode)
        if (task != null) mainHandler.removeCallbacks(task)

        if (longFired.remove(keyCode)) {
            // 长按已触发 -> 吞键收尾
            AppLog.d("按键 $keyCode 长按收尾（不触发短按）")
        } else {
            // 短按（含：定时器未到点、或根本没有长按定时器）
            val activeShort = pickActive(shortMaps)
            if (activeShort != null) {
                AppLog.d("短按触发: ${activeShort.id} ${activeShort.title}")
                ActionExecutor.execute(this, activeShort.action)
            }
        }
        return true
    }

    // ---------------- 动作执行 ----------------

    private fun handleLongPress(keyCode: Int, mapping: KeyMapping) {
        // 定时器回调里执行长按。标记 longFired，使后续 UP 只吞键不触发短按。
        pending.remove(keyCode)
        longFired.add(keyCode)
        AppLog.d("长按触发: ${mapping.id} ${mapping.title}")
        ActionExecutor.execute(this, mapping.action)
    }

    /**
     * 从候选映射中选出「enabled 且条件满足」的一条。
     * - require360 的映射需当前在 360 界面；
     * - 不满足条件返回 null（调用方据此透传）。
     */
    private fun pickActive(maps: List<KeyMapping>): KeyMapping? {
        return maps.firstOrNull { m ->
            m.enabled && (!m.require360 || isOn360)
        }
    }

    /** 供 UI「检测当前前台包名」按钮读取最近前台包名。 */
    companion object {
        @Volatile
        private var lastKnownForegroundPkg: String = ""

        fun reportForeground(pkg: String) {
            lastKnownForegroundPkg = pkg
        }

        fun lastForegroundPackage(): String = lastKnownForegroundPkg
    }
}
