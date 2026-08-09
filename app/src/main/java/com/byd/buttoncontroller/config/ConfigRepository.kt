package com.byd.buttoncontroller.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 360 影像各视角按钮的定位标识（实测为 resource-id，见 [KeyMappingDefaults]）。
 * UI 可编辑，默认为实车实测值。
 */
data class View360Targets(
    val front: String = KeyMappingDefaults.ID_VIEW_FRONT,
    val back: String = KeyMappingDefaults.ID_VIEW_BACK,
    val left: String = KeyMappingDefaults.ID_VIEW_LEFT,
    val right: String = KeyMappingDefaults.ID_VIEW_RIGHT,
    val frontWide: String = KeyMappingDefaults.ID_VIEW_FRONT_WIDE,
    val backWide: String = KeyMappingDefaults.ID_VIEW_BACK_WIDE
)

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "byd_button_config")

/**
 * 配置仓库：单例。读取/持久化映射开关与可配置项，通过 [config] StateFlow 暴露运行时快照。
 *
 * UI 改动调用 set* 方法 -> 写 DataStore -> 更新 StateFlow -> Service 订阅即时生效。
 */
class ConfigRepository private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _config = MutableStateFlow(MappingConfig())
    val config: StateFlow<MappingConfig> = _config.asStateFlow()

    init {
        // 启动时从 DataStore 加载一次，构建初始快照
        scope.launch {
            val prefs = context.configDataStore.data.first()
            _config.value = buildConfig(prefs)
        }
    }

    /** 当前快照（同步可读，用于不便挂起调用的场景，如 onKeyEvent）。 */
    fun snapshot(): MappingConfig = _config.value

    private fun enabledKey(id: String) = booleanPreferencesKey("enabled_$id")

    private fun buildConfig(prefs: Preferences): MappingConfig {
        val targets = View360Targets(
            front = prefs[KEY_TARGET_FRONT] ?: KeyMappingDefaults.ID_VIEW_FRONT,
            back = prefs[KEY_TARGET_BACK] ?: KeyMappingDefaults.ID_VIEW_BACK,
            left = prefs[KEY_TARGET_LEFT] ?: KeyMappingDefaults.ID_VIEW_LEFT,
            right = prefs[KEY_TARGET_RIGHT] ?: KeyMappingDefaults.ID_VIEW_RIGHT,
            frontWide = prefs[KEY_TARGET_FRONT_WIDE] ?: KeyMappingDefaults.ID_VIEW_FRONT_WIDE,
            backWide = prefs[KEY_TARGET_BACK_WIDE] ?: KeyMappingDefaults.ID_VIEW_BACK_WIDE,
        )
        val defaults = KeyMappingDefaults.defaultMappings()
        val customMode = prefs[KEY_CUSTOM_MODE_KEYCODE] ?: -1
        val modeKeyCode = if (customMode > 0) customMode else KeyMappingDefaults.MODE_KEY
        // 用配置的 resource-id 覆盖默认 action，读 enabled，并把 M1 的 keyCode 设为生效的模式键
        val mappings = defaults.map { m ->
            val action = when (m.action) {
                is MappingAction.ClickView360 -> {
                    val target = when (m.id) {
                        "M2" -> targets.front
                        "M3" -> targets.back
                        "M4" -> targets.left
                        "M5" -> targets.right
                        "M6" -> targets.frontWide
                        "M7" -> targets.backWide
                        else -> m.action.target
                    }
                    MappingAction.ClickView360(target, byId = true)
                }
                MappingAction.MediaPlayPause -> m.action
            }
            val effectiveKeyCode = if (m.id == "M1") modeKeyCode else m.keyCode
            m.copy(keyCode = effectiveKeyCode, action = action, enabled = prefs[enabledKey(m.id)] ?: m.enabled)
        }
        return MappingConfig(
            mappings = mappings,
            packageName360 = prefs[KEY_PKG_360] ?: KeyMappingDefaults.PKG_360,
            longPressThresholdMs = prefs[KEY_LONG_PRESS_MS] ?: 500L,
            customModeKeyCode = customMode,
        )
    }

    private fun rebuild() {
        scope.launch {
            val prefs = context.configDataStore.data.first()
            _config.value = buildConfig(prefs)
        }
    }

    // ---- 写入 API ----

    fun setMappingEnabled(id: String, enabled: Boolean) {
        scope.launch {
            context.configDataStore.edit { it[enabledKey(id)] = enabled }
            rebuild()
        }
    }

    fun setView360Targets(targets: View360Targets) {
        scope.launch {
            context.configDataStore.edit { p ->
                p[KEY_TARGET_FRONT] = targets.front
                p[KEY_TARGET_BACK] = targets.back
                p[KEY_TARGET_LEFT] = targets.left
                p[KEY_TARGET_RIGHT] = targets.right
                p[KEY_TARGET_FRONT_WIDE] = targets.frontWide
                p[KEY_TARGET_BACK_WIDE] = targets.backWide
            }
            rebuild()
        }
    }

    fun setPackageName360(pkg: String) {
        scope.launch {
            context.configDataStore.edit { it[KEY_PKG_360] = pkg }
            rebuild()
        }
    }

    fun setLongPressThresholdMs(ms: Long) {
        scope.launch {
            context.configDataStore.edit { it[KEY_LONG_PRESS_MS] = ms.coerceAtLeast(50L) }
            rebuild()
        }
    }

    fun setCustomModeKeyCode(keyCode: Int) {
        scope.launch {
            context.configDataStore.edit { it[KEY_CUSTOM_MODE_KEYCODE] = keyCode }
            rebuild()
        }
    }

    companion object {
        private val KEY_TARGET_FRONT = stringPreferencesKey("target_front")
        private val KEY_TARGET_BACK = stringPreferencesKey("target_back")
        private val KEY_TARGET_LEFT = stringPreferencesKey("target_left")
        private val KEY_TARGET_RIGHT = stringPreferencesKey("target_right")
        private val KEY_TARGET_FRONT_WIDE = stringPreferencesKey("target_front_wide")
        private val KEY_TARGET_BACK_WIDE = stringPreferencesKey("target_back_wide")
        private val KEY_PKG_360 = stringPreferencesKey("pkg_360")
        private val KEY_LONG_PRESS_MS = longPreferencesKey("long_press_ms")
        private val KEY_CUSTOM_MODE_KEYCODE = intPreferencesKey("custom_mode_keycode")

        @Volatile private var instance: ConfigRepository? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = ConfigRepository(context.applicationContext)
                    }
                }
            }
        }

        fun get(): ConfigRepository = instance
            ?: error("ConfigRepository 未初始化，请先调用 init()")
    }
}
