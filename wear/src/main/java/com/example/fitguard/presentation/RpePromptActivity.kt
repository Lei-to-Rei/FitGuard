package com.example.fitguard.presentation

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.Text
import com.example.fitguard.presentation.theme.FitGuardTheme

class RpePromptActivity : ComponentActivity() {

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

        setContent {
            FitGuardTheme {
                RpeSliderScreen(
                    lastRpe = lastRpe,
                    isEndOfSession = isEndOfSession,
                    onSubmit = { sendResponse(it) },
                    onSkip = { sendResponse(-1) }
                )
            }
        }

        // Auto-dismiss after 15s (skip) - only for periodic prompts
        if (!isEndOfSession) {
            autoDismissRunnable = Runnable { sendResponse(-1) }
            autoDismissHandler.postDelayed(autoDismissRunnable!!, AUTO_DISMISS_MS)
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

@Composable
private fun RpeSliderScreen(
    lastRpe: Int,
    isEndOfSession: Boolean,
    onSubmit: (Int) -> Unit,
    onSkip: () -> Unit
) {
    val defaultValue = if (lastRpe in 0..10) lastRpe else 0
    var rpeValue by remember { mutableIntStateOf(defaultValue) }
    val focusRequester = remember { FocusRequester() }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }

    val backgroundColor = getRpeComposeColor(rpeValue)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor.copy(alpha = 0.3f))
            .onRotaryScrollEvent { event ->
                rotaryAccumulator += event.verticalScrollPixels
                val threshold = 50f
                while (rotaryAccumulator >= threshold) {
                    if (rpeValue < 10) rpeValue++
                    rotaryAccumulator -= threshold
                }
                while (rotaryAccumulator <= -threshold) {
                    if (rpeValue > 0) rpeValue--
                    rotaryAccumulator += threshold
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            // Title
            Text(
                text = if (isEndOfSession) "Session RPE?" else "How hard?",
                fontSize = 14.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            // Last RPE subtitle
            if (lastRpe in 0..10) {
                Text(
                    text = "Last: $lastRpe",
                    fontSize = 10.sp,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Large RPE number
            Text(
                text = "$rpeValue",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            // Descriptive label
            Text(
                text = getRpeLabel(rpeValue),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            // InlineSlider
            InlineSlider(
                value = rpeValue,
                onValueChange = { rpeValue = it },
                valueProgression = 0..10,
                decreaseIcon = { Icon(InlineSliderDefaults.Decrease, "Decrease") },
                increaseIcon = { Icon(InlineSliderDefaults.Increase, "Increase") },
                segmented = true,
                colors = InlineSliderDefaults.colors(),
                modifier = Modifier.padding(horizontal = 10.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Confirm button
            CompactChip(
                onClick = { onSubmit(rpeValue) },
                label = { Text("Confirm", fontSize = 20.sp) },
                colors = ChipDefaults.primaryChipColors()
            )

            // Skip button (periodic prompts only)
            if (!isEndOfSession) {
                Spacer(modifier = Modifier.height(2.dp))
                CompactChip(
                    onClick = onSkip,
                    label = { Text("Skip", fontSize = 11.sp) },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

private fun getRpeComposeColor(value: Int): Color {
    val green = Color(0xFF4CAF50)
    val yellow = Color(0xFFFFEB3B)
    val orange = Color(0xFFFF9800)
    val red = Color(0xFFB71C1C)

    val fraction = value / 10f
    return when {
        fraction <= 0.33f -> lerp(green, yellow, fraction / 0.33f)
        fraction <= 0.66f -> lerp(yellow, orange, (fraction - 0.33f) / 0.33f)
        else -> lerp(orange, red, (fraction - 0.66f) / 0.34f)
    }
}

private fun getRpeLabel(value: Int): String {
    return when (value) {
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
}
