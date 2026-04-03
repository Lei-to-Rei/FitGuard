package com.example.fitguard.features.sleep

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import com.example.fitguard.data.health.HealthConnectManager
import com.example.fitguard.databinding.ActivityHealthConnectOnboardingBinding

class HealthConnectOnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHealthConnectOnboardingBinding

    companion object {
        private const val TAG = "HCOnboarding"
    }

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Log.d(TAG, "Permissions granted: $granted")
        setResult(if (granted.containsAll(HealthConnectManager.PERMISSIONS)) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHealthConnectOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAllow.setOnClickListener {
            requestPermissions.launch(HealthConnectManager.PERMISSIONS)
        }

        binding.btnNotNow.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }
}
