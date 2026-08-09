package com.byd.buttoncontroller.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 授权助手：
 * 1. 检测 ADB 网络调试 / 无障碍 / 通知使用权 / 自启动状态；
 * 2. 一键跳转系统设置；
 * 3. 生成并复制「电脑端 ADB 授权命令」；
 * 4. 导出 APK 到 Download（分发/换车用）。
 *
 * 说明：车机上的 App 无法自行执行 adb（无 root），首次授权必须由电脑执行一次，
 * 本页把命令生成好，复制到电脑终端粘贴即可。
 */
class PermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionBinding

    /** 已导出到车机 Download 的 APK 路径（若已导出）。 */
    private var exportedApkPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnOpenAccessibility.setOnClickListener {
            AccessibilityUtil.openAccessibilitySettings(this)
        }
        binding.btnOpenNotif.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        binding.btnOpenStartup.setOnClickListener {
            openStartupManager()
        }
        binding.btnCopyCommands.setOnClickListener { copyCommands() }
        binding.btnRefreshCommands.setOnClickListener { refreshAll() }
        binding.btnExportApk.setOnClickListener { exportApk() }
        binding.btnAdbAuth.setOnClickListener { runAdbAuth() }
        binding.btnResetAdbKey.setOnClickListener { resetAdbKey() }

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        refreshAdbStatus()
        refreshAccessibilityStatus()
        refreshNotifStatus()
        refreshCommands()
    }

    // ---------------- ADB 网络调试检测 ----------------

    private fun refreshAdbStatus() {
        lifecycleScope.launch {
            val ips = AdbHelper.localIpv4Addresses()
            var openHost: String? = null
            // 依次探测自身 IP 与回环，任一 5555 端口开放即视为已开启
            val candidates = ips + "127.0.0.1"
            for (ip in candidates.distinct()) {
                if (withContext(Dispatchers.IO) { AdbHelper.isAdbPortOpen(ip) }) {
                    openHost = ip
                    break
                }
            }
            val ipText = if (ips.isEmpty()) "未获取到本机 IP"
            else "本机 IP：${ips.joinToString("  ")}"
            binding.txtIpList.text = ipText
            binding.txtAdbStatus.text = if (openHost != null) {
                "已开启 ✓（$openHost:5555 可连接）\n此车机可被电脑 adb 连接并授权"
            } else {
                "未开启 ✗\n需先在车机开启「ADB 网络调试」（开发者选项），或用电脑执行：adb tcpip 5555"
            }
        }
    }

    // ---------------- 授权状态 ----------------

    private fun refreshAccessibilityStatus() {
        val enabled = AccessibilityUtil.isServiceEnabled(this, KeyMapAccessibilityService::class.java)
        binding.txtAccessibilityStatus.text =
            if (enabled) "已开启 ✓\n方向盘按键映射功能已生效"
            else "未开启 ✗\n需开启后才能捕获方向盘按键"
    }

    private fun refreshNotifStatus() {
        val enabled = ActionExecutor.isNotificationListenerEnabled(this)
        binding.txtNotifStatus.text =
            if (enabled) "已开启 ✓\nM1 模式键可控制第三方音乐"
            else "未开启 ✗\nDiLink 可能不支持此页面（M1 暂不可用，不影响 360 映射）"
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

    // ---------------- 授权命令 ----------------

    private fun currentEnabledAccessibility(): String? {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (t: Throwable) {
            null
        }
    }

    private fun refreshCommands() {
        val ip = AdbHelper.localIpv4Addresses().firstOrNull() ?: "<车机IP>"
        binding.editCommands.setText(
            AdbHelper.buildGrantCommands(ip, currentEnabledAccessibility(), exportedApkPath)
        )
    }

    private fun copyCommands() {
        val text = binding.editCommands.text?.toString().orEmpty()
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BYD adb 授权命令", text))
        Toast.makeText(this, "已复制，请到电脑终端粘贴执行", Toast.LENGTH_LONG).show()
        AppLog.i("授权命令已复制")
    }

    // ---------------- 无线 ADB 自授权 ----------------

    private fun runAdbAuth() {
        binding.btnAdbAuth.isEnabled = false
        binding.txtAdbAuthStatus.text = "正在连接车机 ADB 并检测授权…\n首次使用若车机弹出「允许调试」，请点允许。"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AuthRepairer.repair(this@PermissionActivity) }
            binding.txtAdbAuthStatus.text =
                if (result.ok) "✅ ${result.message}"
                else "❌ ${result.message}"
            binding.btnAdbAuth.isEnabled = true
            refreshAccessibilityStatus()
            refreshCommands()
            if (result.ok) {
                android.widget.Toast.makeText(this@PermissionActivity, "授权已恢复", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetAdbKey() {
        try {
            val dir = java.io.File(filesDir, "adbkey")
            java.io.File(dir, "adbkey.pk8").delete()
            java.io.File(dir, "adbkey.x509").delete()
            binding.txtAdbAuthStatus.text = "已重置 ADB 密钥。\n请再次点击「检测并修复授权」，并在弹窗勾选「一律允许」后点允许。"
            AppLog.i("ADB 密钥已重置")
        } catch (t: Throwable) {
            AppLog.w("重置密钥失败: ${t.message}")
        }
    }

    // ---------------- 导出 APK ----------------

    private fun exportApk() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { exportApkToDownload() }
            if (ok != null) {
                exportedApkPath = ok
                binding.txtExportStatus.text = "已导出：$ok\n授权命令已改用此路径，电脑上无需再准备 APK。"
                refreshCommands()
                Toast.makeText(this@PermissionActivity, "APK 已导出", Toast.LENGTH_SHORT).show()
            } else {
                binding.txtExportStatus.text = "导出失败（Android 9 及以下请直接用电脑安装 APK）"
            }
        }
    }

    /** 把当前已安装 APK 复制到公共 Download 目录，返回车机端路径；失败返回 null。 */
    private fun exportApkToDownload(): String? {
        return try {
            val src = File(applicationInfo.sourceDir)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+：MediaStore 写入公共 Download，无需存储权限
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "byd-button-controller.apk")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri: Uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                "/sdcard/Download/byd-button-controller.apk"
            } else {
                // Android 9-：需要写外部存储权限，此处不自动申请，提示用电脑安装
                null
            }
        } catch (t: Throwable) {
            AppLog.e("导出 APK 失败", t)
            null
        }
    }
}
