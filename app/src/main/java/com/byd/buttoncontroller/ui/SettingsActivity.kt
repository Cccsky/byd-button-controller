package com.byd.buttoncontroller.ui

import android.os.Bundle
import android.widget.GridLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.byd.buttoncontroller.action.launchableLabel
import com.byd.buttoncontroller.config.ConfigRepository
import com.byd.buttoncontroller.config.MappingAction
import com.byd.buttoncontroller.config.View360Targets
import com.byd.buttoncontroller.databinding.ActivitySettingsBinding
import com.byd.buttoncontroller.service.KeyMapAccessibilityService
import com.byd.buttoncontroller.util.AppLog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * 设置页：编辑 360 包名、6 个视角按钮 resource-id、长按阈值、模式键 keycode。
 * 默认值已按 2026-08-06 实车实测填入，可再微调，保存后即时生效。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 6 个视角按钮 resource-id 输入框，按 View360Targets 字段顺序。 */
    private data class TargetField(val hint: String, val getter: (View360Targets) -> String)
    private val targetFields = listOf(
        TargetField("前视") { it.front },
        TargetField("后视") { it.back },
        TargetField("左视") { it.left },
        TargetField("右视") { it.right },
        TargetField("前广角") { it.frontWide },
        TargetField("后广角") { it.backWide },
    )
    private val targetEdits = mutableListOf<TextInputEditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        buildTargetInputs()
        binding.helpText.text = HELP_TEXT

        // 加载当前配置回填
        lifecycleScope.launch {
            val cfg = ConfigRepository.get().config.value
            binding.editPkg360.setText(cfg.packageName360)
            binding.editLongPressMs.setText(cfg.longPressThresholdMs.toString())
            binding.editModeKeycode.setText(
                if (cfg.customModeKeyCode > 0) cfg.customModeKeyCode.toString() else ""
            )
            val targets = targetsFromConfig(cfg.mappings)
            targetEdits.forEachIndexed { i, et -> et.setText(targets[i]) }
        }

        binding.btnDetectPkg.setOnClickListener {
            val pkg = KeyMapAccessibilityService.lastForegroundPackage()
            if (pkg.isEmpty()) {
                binding.editPkg360.setText("")
                AppLog.w("尚未检测到前台包名，请先在 360 界面停留片刻再回来检测")
                showSnack("暂未检测到前台包名，请先打开 360 界面后再试")
            } else {
                binding.editPkg360.setText(pkg)
                showSnack("检测到前台：$pkg (${launchableLabel(this, pkg)})")
            }
        }

        binding.btnSave.setOnClickListener { save() }
    }

    /** 用当前映射里的目标反推 View360Targets（顺序与 targetFields 对应）。 */
    private fun targetsFromConfig(mappings: List<com.byd.buttoncontroller.config.KeyMapping>): List<String> {
        val byId = mappings.associateBy { it.id }
        fun target(id: String) =
            (byId[id]?.action as? MappingAction.ClickView360)?.target.orEmpty()
        return listOf(
            target("M2"), target("M3"), target("M4"),
            target("M5"), target("M6"), target("M7")
        )
    }

    private fun buildTargetInputs() {
        val grid = binding.labelsGrid
        targetFields.forEach { field ->
            val til = TextInputLayout(this).apply {
                hint = field.hint
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                }
            }
            val et = TextInputEditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            til.addView(et)
            grid.addView(til)
            targetEdits.add(et)
        }
    }

    private fun save() {
        val repo = ConfigRepository.get()
        repo.setPackageName360(binding.editPkg360.text?.toString()?.trim().orEmpty())
        repo.setLongPressThresholdMs(
            binding.editLongPressMs.text?.toString()?.trim()?.toLongOrNull() ?: 500L
        )
        val modeKey = binding.editModeKeycode.text?.toString()?.trim()?.toIntOrNull() ?: -1
        repo.setCustomModeKeyCode(modeKey)

        val targets = View360Targets(
            front = targetEdits[0].text?.toString()?.trim().orEmpty(),
            back = targetEdits[1].text?.toString()?.trim().orEmpty(),
            left = targetEdits[2].text?.toString()?.trim().orEmpty(),
            right = targetEdits[3].text?.toString()?.trim().orEmpty(),
            frontWide = targetEdits[4].text?.toString()?.trim().orEmpty(),
            backWide = targetEdits[5].text?.toString()?.trim().orEmpty(),
        )
        repo.setView360Targets(targets)
        AppLog.i("设置已保存")
        showSnack("已保存")
    }

    private fun showSnack(msg: String) {
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
            .show()
    }

    companion object {
        private val HELP_TEXT = """
当前默认值已按实车（Android 10 / DiLink）实测填入：

• 模式键 keycode = 289 (AUTO_MODE)
• 音量+ = 291, 音量- = 292
• 上一曲 = 88, 下一曲 = 87（标准媒体键）
• 360 包名 = com.byd.avc

360 视角按钮没有文案，靠 resource-id 定位（如
com.byd.avc:id/hc_front_2d_button），上方 6 个输入框
填入的是各视角按钮的 resource-id，用 uiautomator dump
可重新抓取确认。

提示：M1 播放/暂停要控制第三方音乐，需在系统设置里给
本 App 开启「通知使用权」（主界面有入口）。
        """.trimIndent()
    }
}
