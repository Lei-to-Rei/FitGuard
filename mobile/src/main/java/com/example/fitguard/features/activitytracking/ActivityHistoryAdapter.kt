package com.example.fitguard.features.activitytracking

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.fitguard.R
import com.example.fitguard.data.model.ActivityHistoryItem
import com.example.fitguard.databinding.ItemActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityHistoryAdapter(
    private val onItemClick: (ActivityHistoryItem) -> Unit = {}
) : RecyclerView.Adapter<ActivityHistoryAdapter.HistoryViewHolder>() {

    private var items: List<ActivityHistoryItem> = emptyList()

    fun submitList(newItems: List<ActivityHistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemActivityHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HistoryViewHolder(
        private val binding: ItemActivityHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)

        fun bind(item: ActivityHistoryItem) {
            binding.tvActivityTitle.text = item.activityType
            binding.tvActivityDate.text = dateFormat.format(Date(item.startTimeMillis))

            val iconRes = when (item.activityType.lowercase(Locale.US)) {
                "running" -> R.drawable.ic_treadmill
                "cycling" -> R.drawable.ic_stationary_bike
                "walking" -> R.drawable.ic_activity
                else -> R.drawable.ic_workout
            }
            binding.ivActivityIcon.setImageResource(iconRes)

            binding.tvTimeValue.text = formatDuration(item.durationMillis)

            if (item.totalDistanceMeters > 0f) {
                val km = item.totalDistanceMeters / 1000.0
                binding.tvDistanceValue.text = if (km < 1.0) {
                    String.format(Locale.US, "%.0f m", item.totalDistanceMeters)
                } else {
                    String.format(Locale.US, "%.2f km", km)
                }
            } else {
                binding.tvDistanceValue.text = "--"
            }

            if (item.avgPaceMinPerKm > 0 && item.avgPaceMinPerKm <= 99) {
                val minutes = item.avgPaceMinPerKm.toInt()
                val seconds = ((item.avgPaceMinPerKm - minutes) * 60).toInt()
                binding.tvPaceValue.text = String.format(Locale.US, "%d:%02d", minutes, seconds)
            } else {
                binding.tvPaceValue.text = "--"
            }

            itemView.setOnClickListener { onItemClick(item) }
        }

        private fun formatDuration(millis: Long): String {
            val totalSeconds = millis / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }
    }
}
