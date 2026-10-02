package com.github.codeworkscreativehub.mlauncher.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.databinding.FragmentOnboardingBinding
import com.github.codeworkscreativehub.mlauncher.ui.adapter.OnboardingAdapter

class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)

        val view = binding.root

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val viewPager: ViewPager2 = view.findViewById(R.id.viewPager)
        val adapter = OnboardingAdapter(this)  // Pass the Fragment (not Activity) to the adapter
        viewPager.adapter = adapter
        // Background comes from the page layout (ui_background, light or night)
    }
}
