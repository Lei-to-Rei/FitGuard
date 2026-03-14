package com.example.fitguard.features.profile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.databinding.ActivitySupportFeedbackBinding

class SupportFeedbackActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySupportFeedbackBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySupportFeedbackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.menuHelpCenter.setOnClickListener {
            Toast.makeText(this, "Help Center coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.menuContactSupport.setOnClickListener {
            Toast.makeText(this, "Contact Support coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.menuRateApp.setOnClickListener {
            Toast.makeText(this, "Rate App coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.menuReportBug.setOnClickListener {
            Toast.makeText(this, "Report Bug coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.menuSuggestFeature.setOnClickListener {
            Toast.makeText(this, "Suggest Feature coming soon", Toast.LENGTH_SHORT).show()
        }
    }
}
