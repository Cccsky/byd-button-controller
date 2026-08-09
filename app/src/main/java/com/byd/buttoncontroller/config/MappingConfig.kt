package com.byd.buttoncontroller.config

import android.view.KeyEvent

/**
 * 一条按键映射的定义。
 *
 * @param id          映射唯一标识 "M1".."M7"
 * @param title       展示名（如 "模式键 - 播放/暂停"）
 * @param keyCode     触发的 Android keycode（已按 2026-08-06 上车实测校准）
 * @param require360  是否仅当当前处于 360 影像界面时生效
 * @param longPress   true=长按触发，false=短按触发
 * @param action      执行的动作
 */
data class KeyMapping(
    val id: String,
    val title: String,
    val keyCode: Int,
    val require360: Boolean,
    val longPress: Boolean,
    val action: MappingAction,
    val enabled: Boolean = true
)

/**
 * 映射执行的动作。
 * - MediaPlayPause: 播放/暂停当前媒体（通过活跃 MediaSession）
 * - ClickView360: 在 360 界面点击按钮；[target] 为 resource-id 或文案，[byId] 指定匹配方式
 */
sealed interface MappingAction {
    data object MediaPlayPause : MappingAction
    data class ClickView360(val target: String, val byId: Boolean = false) : MappingAction
}

/**
 * 运行时可读的完整配置快照。
 * 由 [ConfigRepository] 通过 StateFlow 暴露，Service 订阅后实时生效。
 *
 * @param mappings         7 条映射（enabled 与 keyCode 可被 UI/配置改动）
 * @param packageName360   360 影像 App 包名（已实测: com.byd.avc）
 * @param longPressThresholdMs 长按判定阈值，默认 500ms
 * @param customModeKeyCode 模式键自定义 keycode，<=0 表示用默认 [KeyMappingDefaults.MODE_KEY]
 */
data class MappingConfig(
    val mappings: List<KeyMapping> = KeyMappingDefaults.defaultMappings(),
    val packageName360: String = KeyMappingDefaults.PKG_360,
    val longPressThresholdMs: Long = 500L,
    val customModeKeyCode: Int = -1
) {
    /** 取某 keycode 下、指定短按/长按的映射（enabled 与否都返回，调用方再判条件）。 */
    fun mapsFor(keyCode: Int, longPress: Boolean): List<KeyMapping> =
        mappings.filter { it.keyCode == keyCode && it.longPress == longPress }
}

/**
 * 7 条映射的静态默认定义与默认键值。
 *
 * 2026-08-06 在实车（Android 10 / DiLink，360 包名 com.byd.avc）用无障碍探测日志实测：
 * - 模式键   : scanCode 89  -> keyCode 289 (AUTO_MODE)
 * - 音量+    : scanCode 115 -> keyCode 291 (AUTO_VOLUME_UP)
 * - 音量-    : scanCode 114 -> keyCode 292 (AUTO_VOLUME_DOWN)
 * - 上一曲   : scanCode 268 -> keyCode 88  (MEDIA_PREVIOUS)
 * - 下一曲   : scanCode 270 -> keyCode 87  (MEDIA_NEXT)
 * - 长按上一曲: scanCode 269 -> keyCode 303 (AUTO_MEDIA_PREVIOUS_LP, 独立按键)
 * - 长按下一曲: scanCode 271 -> keyCode 302 (AUTO_MEDIA_NEXT_LP, 独立按键)
 * - 360 开关 : scanCode 288 -> keyCode 294 (AUTO_VIDEO)
 *
 * 360 视角按钮没有文案，但有稳定 resource-id，故 M2-M7 按 resource-id 点击：
 *   hc_front_2d_button / hc_back_2d_button / hc_left_2d_button / hc_right_2d_button
 *   hc_front_wide_2d_button / hc_back_wide_2d_button
 */
object KeyMappingDefaults {
    const val MODE_KEY = 289 // 实测: AUTO_MODE
    const val PKG_360 = "com.byd.avc"

    // 360 界面视角按钮 resource-id（实测 uiautomator dump 得到）
    const val ID_VIEW_FRONT = "com.byd.avc:id/hc_front_2d_button"
    const val ID_VIEW_BACK = "com.byd.avc:id/hc_back_2d_button"
    const val ID_VIEW_LEFT = "com.byd.avc:id/hc_left_2d_button"
    const val ID_VIEW_RIGHT = "com.byd.avc:id/hc_right_2d_button"
    const val ID_VIEW_FRONT_WIDE = "com.byd.avc:id/hc_front_wide_2d_button"
    const val ID_VIEW_BACK_WIDE = "com.byd.avc:id/hc_back_wide_2d_button"

    fun defaultMappings(): List<KeyMapping> = listOf(
        // M1 播放/暂停需控制第三方媒体会话，当前车机(Android 10/DiLink)不支持通知使用权，
        // 暂默认关闭（不拦截模式键，保留原厂功能），后续有方案再开启。
        KeyMapping("M1", "模式键 - 播放/暂停", MODE_KEY, require360 = false, longPress = false,
            action = MappingAction.MediaPlayPause, enabled = false),
        KeyMapping("M2", "音量+ - 前视", 291, require360 = true, longPress = false,
            action = MappingAction.ClickView360(ID_VIEW_FRONT, byId = true)),
        KeyMapping("M3", "音量- - 后视", 292, require360 = true, longPress = false,
            action = MappingAction.ClickView360(ID_VIEW_BACK, byId = true)),
        KeyMapping("M4", "上一曲 - 左视", KeyEvent.KEYCODE_MEDIA_PREVIOUS, require360 = true, longPress = false,
            action = MappingAction.ClickView360(ID_VIEW_LEFT, byId = true)),
        KeyMapping("M5", "下一曲 - 右视", KeyEvent.KEYCODE_MEDIA_NEXT, require360 = true, longPress = false,
            action = MappingAction.ClickView360(ID_VIEW_RIGHT, byId = true)),
        KeyMapping("M6", "长按上一曲 - 前广角", 303, require360 = true, longPress = false,
            action = MappingAction.ClickView360(ID_VIEW_FRONT_WIDE, byId = true)),
        KeyMapping("M7", "长按下一曲 - 后广角", 302, require360 = true, longPress = false,
            action = MappingAction.ClickView360(ID_VIEW_BACK_WIDE, byId = true)),
    )
}
