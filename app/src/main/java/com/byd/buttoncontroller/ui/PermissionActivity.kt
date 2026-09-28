package com.byd.buttoncontroller.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.byd.buttoncontroller.action.ActionExecutor
import com.byd.buttoncontroller.databinding.ActivityPermissionBinding
import com.byd.buttoncontroller.service.KeyMapAccessibilityService
import com.byd.buttoncontroller.util.AccessibilityUtil
import com.byd.buttoncontroller.util.AdbHelper
import com.byd.buttoncontroller.util.AppLog
import com.byd.buttoncontroller.util.AuthRepairer
import com.byd.buttoncontroller.util.SecureSettingsWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 授权助手：
 * 1. 核心是一次性获得 WRITE_SECURE_SETTINGS（内嵌 ADB 自动获取，或电脑一条命令）；
 * 2. 权限到手后无障碍授权可由 App 自行写入恢复，重启也不怕；
 * 3. 附加可选权限（通知使用权）与 DiLink 自启动管理入口。
 */
class PermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnAdbAuth.setOnClickListener { runRepair() }
        binding.btnRepair.setOnClickListener { runRepair() }
        binding.btnOpenAccessibility.setOnClickListener {
            AccessibilityUtil.openAccessibilitySettings(this)
        }
        binding.btnOpenNotif.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        binding.btnOpenStartup.setOnClickListener { openStartupManager() }
        binding.btnCopyCommands.setOnClickListener { copyPcCommand() }
        binding.btnResetAdbKey.setOnClickListener { resetAdbKey() }

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        refreshGrantStatus()
        refreshAccessibilityStatus()
        refreshNotifStatus()
    }

    // ---------------- 自动授权权限状态 ----------------

    private fun refreshGrantStatus() {
        if (SecureSettingsWriter.hasPermission(this)) {
            binding.txtGrantStatus.text = "已获得 ✓\n无障碍授权丢失时 App 会自动静默恢复，重启同样生效，无需电脑和弹窗"
            binding.btnAdbAuth.text = "重新检测修复"
        } else {
            binding.txtGrantStatus.text = "未获得 ✗\n点击「自动获取权限」通过车机无线 ADB 获取一次（车机弹窗点允许）；或复制电脑命令执行"
            binding.btnAdbAuth.text = "自动获取权限"
        }
    }

    private fun refreshAccessibilityStatus() {
        val enabled = AccessibilityUtil.isServiceEnabled(this, KeyMapAccessibilityService::class.java)
        binding.txtAccessibilityStatus.text =
            if (enabled) "已开启 ✓\n方向盘按键映射功能已生效"
            else "未开启 ✗\n点「一键修复授权」自动写入，或到系统设置手动开启"
        binding.btnRepair.visibility =
            if (enabled) View.GONE else View.VISIBLE
    }

    private fun refreshNotifStatus() {
        val enabled = ActionExecutor.isNotificationListenerEnabled(this)
        binding.txtNotifStatus.text =
            if (enabled) "已开启 ✓\nM1 模式键可控制第三方音乐"
            else "未开启（可选）\n开启后 M1 才能控制第三方音乐播放/暂停，不影响 360 视角映射"
    }

    private fun openStartupManager() {
        // 实测车机（DiLink）自启动管理入口
        val pkg = "com.byd.appstartmanagement"
        val activity = "$pkg/.frame.AppStartManagement"
        try {
            startActivity(
                Intent().setClassName(pkg, activity)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            AppLog.w("打开自启动管理失败: ${t.message}")
            Toast.makeText(this, "未能打开自启动管理，请在车机设置中手动放行本 App", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- 一键修复（同时覆盖权限获取与无障碍写入） ----------------

    private fun runRepair() {
        setBusy(true)
        binding.txtAdbAuthStatus.text = "正在修复…\n首次获取权限时，请在车机弹窗勾选「一律允许」后点允许。"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AuthRepairer.repair(this@PermissionActivity) }
            binding.txtAdbAuthStatus.text =
                if (result.ok) "✅ ${result.message}" else "❌ ${result.message}"
            setBusy(false)
            refreshAll()
            if (result.ok) {
                Toast.makeText(this@PermissionActivity, "授权已恢复", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnAdbAuth.isEnabled = !busy
        binding.btnRepair.isEnabled = !busy
    }

    private fun copyPcCommand() {
        val ip = AdbHelper.localIpv4Addresses().firstOrNull() ?: return
        val text = AdbHelper.buildPcGrantCommand(ip)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BYD 授权命令", text))
        Toast.makeText(this, "已复制，请在电脑终端（已装 adb）执行", Toast.LENGTH_LONG).show()
        AppLog.i("电脑授权命令已复制")
    }

    private fun resetAdbKey() {
        try {
            val dir = File(filesDir, "adbkey")
            File(dir, "adbkey.pk8").delete()
            File(dir, "adbkey.x509").delete()
            binding.txtAdbAuthStatus.text = "已重置 ADB 密钥。\n再次点击「自动获取权限」，并在车机弹窗勾选「一律允许」后点允许。"
            AppLog.i("ADB 密钥已重置")
        } catch (t: Throwable) {
            AppLog.w("重置密钥失败: ${t.message}")
        }
    }
}
