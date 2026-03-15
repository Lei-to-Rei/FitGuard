package com.example.fitguard.features.activitytracking

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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

        adapter = ActivityHistoryAdapter()
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
