package com.example.fitguard.features.activitytracking

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.R
import com.example.fitguard.data.processing.RpeState
import com.example.fitguard.data.processing.SequenceProcessor
import com.example.fitguard.databinding.ActivityRpePromptBinding
import com.example.fitguard.services.WearableDataListenerService
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class RpePromptActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PhoneRpePrompt"
        const val EXTRA_LAST_RPE = "last_rpe"
        const val EXTRA_IS_END_OF_SESSION = "is_end_of_session"
        const val EXTRA_SESSION_ID = "session_id"
        const val ACTION_PHONE_RPE_RESPONSE = "com.example.fitguard.PHONE_RPE_RESPONSE"
        private const val AUTO_DISMISS_MS = 15_000L
        private const val NOTIFICATION_ID = 2002
    }

    private lateinit var binding: ActivityRpePromptBinding
    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null
    private var isEndOfSession = false
    private var sessionId = ""
    private var hasResponded = false

    private val rpeDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WearableDataListenerService.ACTION_RPE_RECEIVED) {
                val receivedSession = intent.getStringExtra("session_id") ?: ""
                if (receivedSession == sessionId || sessionId.isEmpty()) {
                    Log.d(TAG, "Watch answered RPE first, dismissing phone prompt")
                    cancelNotification()
                    finishQuietly()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityRpePromptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Haptic buzz
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))

        val lastRpe = intent.getIntExtra(EXTRA_LAST_RPE, -1)
        isEndOfSession = intent.getBooleanExtra(EXTRA_IS_END_OF_SESSION, false)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""

        setupUI(lastRpe)
        registerDismissReceiver()

        // Auto-dismiss after 15s for periodic prompts only
        if (!isEndOfSession) {
            autoDismissRunnable = Runnable { submitResponse(-1) }
            autoDismissHandler.postDelayed(autoDismissRunnable!!, AUTO_DISMISS_MS)
        }
    }

    private fun setupUI(lastRpe: Int) {
        binding.tvTitle.text = if (isEndOfSession) "Session RPE?" else "How hard?"

        if (lastRpe in 0..10) {
            binding.tvLastRpe.visibility = View.VISIBLE
            binding.tvLastRpe.text = "Last: $lastRpe"
        }

        val defaultValue = if (lastRpe in 0..10) lastRpe else 0
        binding.seekRpe.progress = defaultValue
        updateRpeDisplay(defaultValue)

        binding.seekRpe.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateRpeDisplay(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnConfirm.setOnClickListener {
            submitResponse(binding.seekRpe.progress)
        }

        if (isEndOfSession) {
            binding.btnSkip.visibility = View.GONE
        } else {
            binding.btnSkip.setOnClickListener {
                submitResponse(-1)
            }
        }
    }

    private fun updateRpeDisplay(value: Int) {
        binding.tvRpeNumber.text = "$value"
        binding.tvRpeLabel.text = getRpeLabel(value)

        val color = getRpeColor(value)
        binding.rootLayout.setBackgroundColor(Color.argb(200, Color.red(color), Color.green(color), Color.blue(color)))
    }

    private fun submitResponse(rpeValue: Int) {
        if (hasResponded) return
        hasResponded = true

        autoDismissRunnable?.let { autoDismissHandler.removeCallbacks(it) }

        Log.d(TAG, "Phone RPE response: $rpeValue for session $sessionId")

        // Process RPE locally
        if (rpeValue >= 0) {
            RpeState.update(rpeValue)
        }
        SequenceProcessor(this).onRpeReceived(rpeValue)

        // Send dismiss to watch
        sendDismissToWatch(rpeValue)

        // Broadcast so ActivityTrackingActivity can update ViewModel
        sendBroadcast(Intent(ACTION_PHONE_RPE_RESPONSE).apply {
            setPackage(packageName)
            putExtra("session_id", sessionId)
            putExtra("rpe_value", rpeValue)
        })

        cancelNotification()
        finish()
    }

    private fun sendDismissToWatch(rpeValue: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Wearable.getNodeClient(this@RpePromptActivity).connectedNodes.await()
                val payload = JSONObject().apply {
                    put("session_id", sessionId)
                    put("rpe_value", rpeValue)
                }.toString().toByteArray(Charsets.UTF_8)

                for (node in nodes) {
                    Wearable.getMessageClient(this@RpePromptActivity)
                        .sendMessage(node.id, "/fitguard/activity/rpe_dismiss", payload).await()
                    Log.d(TAG, "Sent rpe_dismiss to watch ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send rpe_dismiss to watch: ${e.message}", e)
            }
        }
    }

    private fun cancelNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
    }

    private fun finishQuietly() {
        if (hasResponded) return
        hasResponded = true
        autoDismissRunnable?.let { autoDismissHandler.removeCallbacks(it) }
        finish()
    }

    private fun registerDismissReceiver() {
        val filter = IntentFilter(WearableDataListenerService.ACTION_RPE_RECEIVED)
        registerReceiver(rpeDismissReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoDismissRunnable?.let { autoDismissHandler.removeCallbacks(it) }
        try { unregisterReceiver(rpeDismissReceiver) } catch (_: Exception) {}
    }

    private fun getRpeLabel(value: Int): String = when (value) {
        0 -> "Nothing at all"
        1 -> "Very light"
        2 -> "Light"
        3 -> "Moderate"
        4 -> "Somewhat hard"
        5 -> "Hard"
        6 -> "Harder"
        7 -> "Very hard"
        8 -> "Very hard+"
        9 -> "Extreme"
        10 -> "Max effort"
        else -> ""
    }

    private fun getRpeColor(value: Int): Int {
        val green = Color.parseColor("#4CAF50")
        val yellow = Color.parseColor("#FFEB3B")
        val orange = Color.parseColor("#FF9800")
        val red = Color.parseColor("#B71C1C")

        val fraction = value / 10f
        return when {
            fraction <= 0.33f -> blendColors(green, yellow, fraction / 0.33f)
            fraction <= 0.66f -> blendColors(yellow, orange, (fraction - 0.33f) / 0.33f)
            else -> blendColors(orange, red, (fraction - 0.66f) / 0.34f)
        }
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * r).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * r).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * r).toInt()
        )
    }
}
