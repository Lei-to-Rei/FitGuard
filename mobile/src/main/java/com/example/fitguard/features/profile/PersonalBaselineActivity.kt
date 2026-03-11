package com.example.fitguard.features.profile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.databinding.ActivityPersonalBaselineBinding

class PersonalBaselineActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPersonalBaselineBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPersonalBaselineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
    }
}
