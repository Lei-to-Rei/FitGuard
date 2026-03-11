package com.example.fitguard.features.profile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.databinding.ActivityConnectedDevicesBinding

class ConnectedDevicesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConnectedDevicesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectedDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnAddDevice.setOnClickListener {
            Toast.makeText(this, "Add device coming soon", Toast.LENGTH_SHORT).show()
        }
    }
}
