package com.github.codeworkscreativehub.mlauncher.ui.adapter

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.ui.onboarding.OnboardingPageFragment

class OnboardingAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    // Return the number of pages
    // Single page: welcome + optional "set as default launcher". No permission demands.
    override fun getItemCount(): Int = 1

    // Return the corresponding fragment for each page
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> OnboardingPageFragment.Companion.newInstance(R.layout.fragment_onboarding_page_one)
            else -> throw IllegalArgumentException("Invalid page position: $position")
        }
    }
}