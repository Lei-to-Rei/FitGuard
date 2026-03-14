package com.example.fitguard.onboarding

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.fitguard.R
import com.example.fitguard.auth.LoginActivity

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var indicatorLayout: LinearLayout
    private lateinit var btnNext: com.google.android.material.button.MaterialButton

    private val pages = listOf(
        "Welcome to FitGuard" to "Track workouts, monitor recovery, and train safer every day",
        "AI Fatigue Prediction" to "Advanced machine learning predicts your fatigue levels in real-time",
        "Recovery Recommendation" to "Smart recovery suggestions based on your fatigue levels in real-time"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.viewPager)
        indicatorLayout = findViewById(R.id.indicatorLayout)
        btnNext = findViewById(R.id.btnNext)

        val adapter = OnboardingPagerAdapter(this, pages)
        viewPager.adapter = adapter

        setupIndicators()
        updateIndicators(0)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicators(position)
                btnNext.text = if (position == pages.size - 1) "Get Started" else "Next"
            }
        })

        btnNext.setOnClickListener {
            if (viewPager.currentItem < pages.size - 1) {
                viewPager.currentItem += 1
            } else {
                completeOnboarding()
            }
        }
    }

    private fun setupIndicators() {
        indicatorLayout.removeAllViews()
        for (i in pages.indices) {
            val dot = ImageView(this).apply {
                setImageDrawable(
                    ContextCompat.getDrawable(
                        this@OnboardingActivity,
                        R.drawable.bg_onboarding_indicator_inactive
                    )
                )
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8.dpToPx() }
                layoutParams = params
            }
            indicatorLayout.addView(dot)
        }
    }

    private fun updateIndicators(position: Int) {
        for (i in 0 until indicatorLayout.childCount) {
            val dot = indicatorLayout.getChildAt(i) as ImageView
            dot.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    if (i == position) R.drawable.bg_onboarding_indicator_active
                    else R.drawable.bg_onboarding_indicator_inactive
                )
            )
        }
    }

    private fun completeOnboarding() {
        getSharedPreferences("fitguard_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_complete", true)
            .apply()

        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}
