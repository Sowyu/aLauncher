package com.github.codeworkscreativehub.mlauncher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.github.codeworkscreativehub.mlauncher.databinding.FragmentSettingsBinding

// Placeholder while the settings screen is rebuilt (replaced in the settings redesign commit)
class SettingsFragment : BaseFragment() {
    private var _binding: FragmentSettingsBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
