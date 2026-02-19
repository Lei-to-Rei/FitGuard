package com.example.fitguard.features.nutrition

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.fitguard.data.model.FoodEntry
import com.example.fitguard.databinding.ItemFoodEntryBinding

class FoodEntryAdapter(
    private val onDeleteClick: (FoodEntry) -> Unit
) : RecyclerView.Adapter<FoodEntryAdapter.FoodEntryViewHolder>() {

    private var items: List<FoodEntry> = emptyList()

    fun submitList(newItems: List<FoodEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FoodEntryViewHolder {
        val binding = ItemFoodEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FoodEntryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FoodEntryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class FoodEntryViewHolder(
        private val binding: ItemFoodEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FoodEntry) {
            binding.apply {
                tvFoodName.text = entry.name
                tvServingSize.text = entry.servingSize
                tvCalories.text = "${entry.calories} cal"
                tvMacros.text = "P: ${entry.protein}g  C: ${entry.carbs}g  F: ${entry.fat}g  Na: ${entry.sodium}mg"
                btnDelete.setOnClickListener { onDeleteClick(entry) }
            }
        }
    }
}
