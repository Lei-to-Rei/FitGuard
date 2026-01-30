package com.example.fitguard.features.settings

import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.databinding.ActivityBatchSettingsBinding
import com.google.android.gms.wearable.Wearable

/**
 * Activity to configure batch transfer settings
 * Settings are saved locally and synced to watch
 */
class BatchSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBatchSettingsBinding

    companion object {
        private const val TAG = "BatchSettingsActivity"
        private const val MIN_BATCH_SIZE_KB = 1
        private const val MAX_BATCH_SIZE_KB = 500
        private const val MIN_INTERVAL_MINUTES = 1
        private const val MAX_INTERVAL_MINUTES = 60
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupSeekBars()
        setupButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Batch Transfer Settings"
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("batch_settings", MODE_PRIVATE)
        val batchSizeKB = prefs.getInt("batch_size_kb", 50)
        val transferIntervalMinutes = prefs.getInt("transfer_interval_minutes", 5)

        binding.seekBatchSize.progress = batchSizeKB - MIN_BATCH_SIZE_KB
        binding.seekInterval.progress = transferIntervalMinutes - MIN_INTERVAL_MINUTES

        updateBatchSizeLabel(batchSizeKB)
        updateIntervalLabel(transferIntervalMinutes)
    }

    private fun setupSeekBars() {
        // Batch size seek bar
        binding.seekBatchSize.max = MAX_BATCH_SIZE_KB - MIN_BATCH_SIZE_KB
        binding.seekBatchSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val batchSizeKB = progress + MIN_BATCH_SIZE_KB
                updateBatchSizeLabel(batchSizeKB)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Transfer interval seek bar
        binding.seekInterval.max = MAX_INTERVAL_MINUTES - MIN_INTERVAL_MINUTES
        binding.seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val intervalMinutes = progress + MIN_INTERVAL_MINUTES
                updateIntervalLabel(intervalMinutes)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        binding.btnReset.setOnClickListener {
            resetToDefaults()
        }

        binding.btnSyncNow.setOnClickListener {
            syncSettingsToWatch()
        }
    }

    private fun updateBatchSizeLabel(sizeKB: Int) {
        binding.tvBatchSizeValue.text = "$sizeKB KB"

        // Update description based on size
        val description = when {
            sizeKB < 10 -> "Very frequent transfers (high battery usage)"
            sizeKB < 50 -> "Frequent transfers (moderate battery usage)"
            sizeKB < 100 -> "Balanced transfers (recommended)"
            sizeKB < 200 -> "Less frequent transfers (better battery)"
            else -> "Infrequent transfers (best battery, may lose data if app crashes)"
        }
        binding.tvBatchSizeDescription.text = description
    }

    private fun updateIntervalLabel(minutes: Int) {
        binding.tvIntervalValue.text = when {
            minutes < 60 -> "$minutes minutes"
            minutes == 60 -> "1 hour"
            else -> "${minutes / 60} hours ${minutes % 60} minutes"
        }

        // Update description based on interval
        val description = when {
            minutes < 5 -> "Very frequent sync (high battery usage)"
            minutes < 15 -> "Frequent sync (moderate battery usage)"
            minutes < 30 -> "Balanced sync (recommended)"
            else -> "Infrequent sync (better battery, delayed data)"
        }
        binding.tvIntervalDescription.text = description
    }

    private fun saveSettings() {
        val batchSizeKB = binding.seekBatchSize.progress + MIN_BATCH_SIZE_KB
        val transferIntervalMinutes = binding.seekInterval.progress + MIN_INTERVAL_MINUTES

        val prefs = getSharedPreferences("batch_settings", MODE_PRIVATE)
        prefs.edit().apply {
            putInt("batch_size_kb", batchSizeKB)
            putInt("transfer_interval_minutes", transferIntervalMinutes)
        }.apply()

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()

        // Sync to watch
        syncSettingsToWatch()
    }

    private fun resetToDefaults() {
        binding.seekBatchSize.progress = 50 - MIN_BATCH_SIZE_KB
        binding.seekInterval.progress = 5 - MIN_INTERVAL_MINUTES

        saveSettings()
        Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
    }

    private fun syncSettingsToWatch() {
        val prefs = getSharedPreferences("batch_settings", MODE_PRIVATE)
        val batchSizeKB = prefs.getInt("batch_size_kb", 50)
        val transferIntervalMinutes = prefs.getInt("transfer_interval_minutes", 5)

        val nodeClient = Wearable.getNodeClient(this)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) {
                Toast.makeText(this, "No watch connected", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            nodes.forEach { node ->
                val messageClient = Wearable.getMessageClient(this)
                val message = "$batchSizeKB,$transferIntervalMinutes"
                messageClient.sendMessage(node.id, "/batch_settings", message.toByteArray())
                    .addOnSuccessListener {
                        Log.d(TAG, "Settings synced to watch")
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Settings synced to watch",
                                Toast.LENGTH_SHORT
                            ).show()
                            binding.tvSyncStatus.text = "✓ Synced to watch"
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to sync settings: ${e.message}")
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Failed to sync: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            binding.tvSyncStatus.text = "✗ Sync failed"
                        }
                    }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}