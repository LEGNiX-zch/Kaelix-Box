package com.kaelixbox.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kaelixbox.R
import com.kaelixbox.container.ContainerConfig
import com.kaelixbox.databinding.ItemContainerBinding

class ContainerAdapter(
    private var items: List<ContainerConfig> = emptyList(),
    private val currentId: () -> String,
    private val onSwitch: (ContainerConfig) -> Unit,
    private val onDelete: (ContainerConfig) -> Unit
) : RecyclerView.Adapter<ContainerAdapter.VH>() {

    fun submit(list: List<ContainerConfig>, current: String) {
        items = list
        currentIdInternal = current
        notifyDataSetChanged()
    }

    private var currentIdInternal: String = ""

    class VH(val b: ItemContainerBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemContainerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.containerName.text = item.name +
            if (item.id == currentIdInternal) "  (当前)" else ""
        holder.b.containerMeta.text = buildMeta(item)
        holder.b.btnSwitch.setOnClickListener { onSwitch(item) }
        holder.b.btnDelete.setOnClickListener { onDelete(item) }
    }

    private fun buildMeta(item: ContainerConfig): String {
        return "${item.arch} · ${item.distribution}" +
            if (item.isDefaultDebian13) " · 默认(自动XFCE)" else ""
    }

    override fun getItemCount(): Int = items.size
}
