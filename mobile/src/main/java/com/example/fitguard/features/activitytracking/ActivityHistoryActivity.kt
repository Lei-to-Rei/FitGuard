package com.example.fitguard.features.activitytracking

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.fitguard.data.repository.AuthRepository
import com.example.fitguard.databinding.ActivityActivityHistoryBinding

class ActivityHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivityHistoryBinding
    private val viewModel: ActivityHistoryViewModel by viewModels()
    private lateinit var adapter: ActivityHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val userId = AuthRepository.currentUser?.uid ?: ""
        adapter = ActivityHistoryAdapter { item ->
            startActivity(Intent(this, ActivityDetailActivity::class.java).apply {
                putExtra("session_dir", item.sessionDirName)
                putExtra("user_id", userId)
                putExtra("activity_type", item.activityType)
                putExtra("start_time", item.startTimeMillis)
            })
        }
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadSessions()
    }

    private fun observeViewModel() {
        viewModel.sessions.observe(this) { items ->
            adapter.submitList(items)
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.isEmpty.observe(this) { empty ->
            binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        }
    }
}
