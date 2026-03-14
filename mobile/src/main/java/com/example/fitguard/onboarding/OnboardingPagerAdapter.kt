package com.example.fitguard.onboarding

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class OnboardingPagerAdapter(
    activity: FragmentActivity,
    private val pages: List<Pair<String, String>>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = pages.size

    override fun createFragment(position: Int): Fragment {
        val (title, subtitle) = pages[position]
        return OnboardingPageFragment.newInstance(title, subtitle)
    }
}
