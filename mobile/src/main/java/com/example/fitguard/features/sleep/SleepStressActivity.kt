package com.example.fitguard.features.sleep

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.databinding.ActivitySleepStressBinding

class SleepStressActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySleepStressBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySleepStressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }
}
