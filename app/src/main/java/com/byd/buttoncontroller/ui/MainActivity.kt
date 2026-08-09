package com.byd.buttoncontroller.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.byd.buttoncontroller.action.ActionExecutor
import com.byd.buttoncontroller.config.ConfigRepository
import com.byd.buttoncontroller.databinding.ActivityMainBinding
import com.byd.buttoncontroller.service.KeyMapAccessibilityService
import com.byd.buttoncontroller.service.KeepAliveService
import com.byd.buttoncontroller.util.AccessibilityUtil
import com.byd.buttoncontroller.util.AppLog
import kotlinx.coroutines.launch

/**
 * 主界面：展示无障碍服务状态、通知使用权状态 + 7 条映射的开关列表。
 * 开关改动即时写入配置，Service 订阅 StateFlow 自动生效。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = MappingAdapter { mapping, enabled ->
        ConfigRepository.get().setMappingEnabled(mapping.id, enabled)
        AppLog.d("映射 ${mapping.id} -> ${if (enabled) "开" else "关"}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                com.byd.buttoncontroller.R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                com.byd.buttoncontroller.R.id.action_permission -> {
                    startActivity(Intent(this, PermissionActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.btnEnableAccessibility.setOnClickListener {
            AccessibilityUtil.openAccessibilitySettings(this)
        }

        binding.btnEnableNotifListener.setOnClickListener {
            // 通知使用权：M1 播放/暂停控制第三方音乐需要
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        // 启动前台保活服务
        KeepAliveService.start(this)

        // 订阅配置变更，刷新列表
        lifecycleScope.launch {
            ConfigRepository.get().config.collect { cfg ->
                adapter.submitList(cfg.mappings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
        refreshNotifListenerStatus()
    }

    /** 每次回到前台刷新无障碍开关状态（用户可能去系统设置改过）。 */
    private fun refreshAccessibilityStatus() {
        val enabled = AccessibilityUtil.isServiceEnabled(this, KeyMapAccessibilityService::class.java)
        with(binding) {
            if (enabled) {
                statusText.text = "已开启 ✓\n方向盘按键映射功能已生效"
                btnEnableAccessibility.visibility = android.view.View.GONE
            } else {
                statusText.text = "未开启 ✗\n需要开启无障碍服务才能捕获方向盘按键"
                btnEnableAccessibility.visibility = android.view.View.VISIBLE
            }
        }
    }

    /** 刷新通知使用权状态（M1 控制第三方音乐需要）。 */
    private fun refreshNotifListenerStatus() {
        val enabled = ActionExecutor.isNotificationListenerEnabled(this)
        with(binding) {
            if (enabled) {
                notifStatusText.text = "已开启 ✓\nM1 模式键可控制第三方音乐播放/暂停"
                btnEnableNotifListener.visibility = android.view.View.GONE
            } else {
                notifStatusText.text = "未开启 ✗\n开启后 M1 才能控制车机自带音乐/酷我等（可选）"
                btnEnableNotifListener.visibility = android.view.View.VISIBLE
            }
        }
    }
}
