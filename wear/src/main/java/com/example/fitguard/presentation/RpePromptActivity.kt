package com.example.fitguard.presentation

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class RpePromptActivity : Activity() {

    companion object {
        const val ACTION_RPE_RESPONSE = "com.example.fitguard.wear.RPE_RESPONSE"
        const val EXTRA_RPE_VALUE = "rpe_value"
        const val EXTRA_LAST_RPE = "last_rpe"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_IS_END_OF_SESSION = "is_end_of_session"
        private const val AUTO_DISMISS_MS = 15_000L
    }

    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Haptic buzz
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))

        val lastRpe = intent.getIntExtra(EXTRA_LAST_RPE, -1)
        val isEndOfSession = intent.getBooleanExtra(EXTRA_IS_END_OF_SESSION, false)

        buildUI(lastRpe, isEndOfSession)

        // Auto-dismiss after 15s (skip) - only for periodic prompts
        if (!isEndOfSession) {
            autoDismissRunnable = Runnable { sendResponse(-1) }
            autoDismissHandler.postDelayed(autoDismissRunnable!!, AUTO_DISMISS_MS)
        }
    }

    private fun buildUI(lastRpe: Int, isEndOfSession: Boolean) {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(8, 16, 8, 16)
        }

        // Title
        val title = TextView(this).apply {
            text = if (isEndOfSession) "Session RPE?" else "How hard?"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 4)
        }
        container.addView(title)

        // Last RPE subtitle
        if (lastRpe >= 0) {
            val subtitle = TextView(this).apply {
                text = "Last: $lastRpe"
                textSize = 10f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 4)
            }
            container.addView(subtitle)
        }

        // Anchor labels row
        val anchors = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4, 2, 4, 6)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val anchorData = listOf("0:Nothing", "3:Moderate", "5:Hard", "10:Max")
        for (anchor in anchorData) {
            val (num, label) = anchor.split(":")
            val tv = TextView(this).apply {
                text = "$num\n$label"
                textSize = 8f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            anchors.addView(tv)
        }
        container.addView(anchors)

        // Buttons row (0-10) inside HorizontalScrollView
        val scrollRow = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(2, 4, 2, 8)
        }

        for (i in 0..10) {
            val color = getRpeColor(i)
            val btn = Button(this).apply {
                text = "$i"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(color)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(6, 8, 6, 8)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(2, 0, 2, 0) }
                setOnClickListener { sendResponse(i) }
            }
            buttonRow.addView(btn)
        }

        scrollRow.addView(buttonRow)
        container.addView(scrollRow)

        // Skip button (not shown for end-of-session)
        if (!isEndOfSession) {
            val skipBtn = Button(this).apply {
                text = "Skip"
                textSize = 11f
                setTextColor(Color.LTGRAY)
                setBackgroundColor(Color.parseColor("#333333"))
                setPadding(16, 8, 16, 8)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                setOnClickListener { sendResponse(-1) }
            }
            container.addView(skipBtn)
        }

        root.addView(container)
        setContentView(root)
    }

    private fun getRpeColor(value: Int): Int {
        return when (value) {
            0 -> Color.parseColor("#4CAF50")     // Green
            1 -> Color.parseColor("#66BB6A")
            2 -> Color.parseColor("#8BC34A")
            3 -> Color.parseColor("#CDDC39")     // Yellow-green
            4 -> Color.parseColor("#FFEB3B")     // Yellow
            5 -> Color.parseColor("#FFC107")     // Amber
            6 -> Color.parseColor("#FF9800")     // Orange
            7 -> Color.parseColor("#FF5722")     // Deep orange
            8 -> Color.parseColor("#F44336")     // Red
            9 -> Color.parseColor("#D32F2F")     // Dark red
            10 -> Color.parseColor("#B71C1C")    // Very dark red
            else -> Color.GRAY
        }
    }

    private fun sendResponse(rpeValue: Int) {
        autoDismissRunnable?.let { autoDismissHandler.removeCallbacks(it) }
        sendBroadcast(Intent(ACTION_RPE_RESPONSE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RPE_VALUE, rpeValue)
        })
        // Dismiss the notification if it's showing
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(RpeNotificationHelper.NOTIFICATION_ID)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        autoDismissRunnable?.let { autoDismissHandler.removeCallbacks(it) }
    }
}
