package com.example.fitguard.presentation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var buttonContainer: LinearLayout

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "WatchSensors"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusText = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            text = "PPG Data Collection\nStarting..."
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        scrollView.addView(buttonContainer)
        mainContainer.addView(statusText)
        mainContainer.addView(scrollView)
        setContentView(mainContainer)

        if (!hasRequiredPermissions()) {
            requestPermissions()
        } else {
            createControlButtons()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val bodySensors = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED

        val activityRecognition = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED

        val wakeLock = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WAKE_LOCK
        ) == PackageManager.PERMISSION_GRANTED

        return bodySensors && activityRecognition && wakeLock
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.WAKE_LOCK
            ),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                createControlButtons()
            } else {
                statusText.text = "Permissions denied.\nGo to Settings to enable."
                addSettingsButton()
            }
        }
    }

    private fun addSettingsButton() {
        val settingsButton = Button(this).apply {
            text = "Open Settings"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
        }
        buttonContainer.addView(settingsButton)
    }

    private fun createControlButtons() {
        buttonContainer.removeAllViews()

        // Start PPG Service Button
        val startButton = Button(this).apply {
            text = "▶ START PPG COLLECTION"
            setBackgroundColor(Color.GREEN)
            setTextColor(Color.BLACK)
            setOnClickListener {
                startPPGService()
            }
        }
        buttonContainer.addView(startButton)

        // Stop PPG Service Button
        val stopButton = Button(this).apply {
            text = "⏹ STOP PPG COLLECTION"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopPPGService()
            }
        }
        buttonContainer.addView(stopButton)

        // Status info
        val infoText = TextView(this).apply {
            text = "\n📊 PPG Data Collection Info:\n\n" +
                    "• Collects Green, IR, Red wavelengths\n" +
                    "• 30-second batching window\n" +
                    "• Runs in background\n" +
                    "• Auto-sends to phone\n\n" +
                    "Press START to begin collection"
            textSize = 10f
            setTextColor(Color.LTGRAY)
            setPadding(8, 16, 8, 8)
        }
        buttonContainer.addView(infoText)

        statusText.text = "Ready to start PPG collection"
    }

    private fun startPPGService() {
        val intent = Intent(this, PPGBackgroundService::class.java)
        startForegroundService(intent)
        statusText.text = "✓ PPG Service Started\nCollecting data in background..."
        Log.d(TAG, "PPG Service started")
    }

    private fun stopPPGService() {
        val intent = Intent(this, PPGBackgroundService::class.java)
        stopService(intent)
        statusText.text = "PPG Service Stopped"
        Log.d(TAG, "PPG Service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}