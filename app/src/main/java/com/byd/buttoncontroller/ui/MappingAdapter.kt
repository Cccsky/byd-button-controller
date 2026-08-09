package com.byd.buttoncontroller.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.byd.buttoncontroller.config.KeyMapping
import com.byd.buttoncontroller.config.MappingAction
import com.byd.buttoncontroller.databinding.ItemMappingBinding

/**
 * 映射列表适配器。每行展示一条映射的标题、说明与开关。
 * 开关改动通过 [onToggle] 回调上报，由 Activity 写入配置。
 */
class MappingAdapter(
    private val onToggle: (KeyMapping, Boolean) -> Unit
) : ListAdapter<KeyMapping, MappingAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemMappingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMappingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            mapTitle.text = item.title
            mapDesc.text = describe(item)
            // 切换监听先置空，避免回填时触发回调
            mapSwitch.setOnCheckedChangeListener(null)
            mapSwitch.isChecked = item.enabled
            mapSwitch.setOnCheckedChangeListener { _, isChecked -> onToggle(item, isChecked) }
        }
    }

    private fun describe(m: KeyMapping): String {
        val condition = if (m.require360) "仅在 360 影像界面生效" else "全局生效"
        val press = if (m.longPress) "长按" else "短按"
        val actionDesc = when (m.action) {
            is MappingAction.MediaPlayPause -> "播放/暂停当前音乐"
            is MappingAction.ClickView360 -> "点击 360「${m.action.target}」"
        }
        return "$press · $condition · $actionDesc"
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<KeyMapping>() {
            override fun areItemsTheSame(o: KeyMapping, n: KeyMapping) = o.id == n.id
            override fun areContentsTheSame(o: KeyMapping, n: KeyMapping) = o == n
        }
    }
}
