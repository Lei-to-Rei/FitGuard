package com.example.fitguard.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.fitguard.databinding.ItemDashboardBinding
import com.example.fitguard.ui.model.DashboardItem

class DashboardAdapter(
    private val items: List<DashboardItem>,
    private val onItemClick: (DashboardItem) -> Unit
) : RecyclerView.Adapter<DashboardAdapter.DashboardViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DashboardViewHolder {
        val binding = ItemDashboardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DashboardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DashboardViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class DashboardViewHolder(
        private val binding: ItemDashboardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DashboardItem) {
            binding.apply {
                tvTitle.text = item.title
                tvDescription.text = item.description
                ivIcon.setImageResource(item.icon)

                root.setOnClickListener {
                    onItemClick(item)
                }
            }
        }
    }
}