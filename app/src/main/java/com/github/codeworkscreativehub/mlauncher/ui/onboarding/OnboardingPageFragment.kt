package com.github.codeworkscreativehub.mlauncher.ui.onboarding

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding
import com.github.codeworkscreativehub.common.getLocalizedString
import com.github.codeworkscreativehub.common.showLongToast
import com.github.codeworkscreativehub.mlauncher.MainActivity
import com.github.codeworkscreativehub.mlauncher.MainViewModel
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import com.github.codeworkscreativehub.mlauncher.databinding.FragmentOnboardingPageOneBinding
import com.github.codeworkscreativehub.mlauncher.helper.ismlauncherDefault

class OnboardingPageFragment : Fragment() {

    private var _binding: ViewBinding? = null
    private val binding get() = _binding!!

    private lateinit var roleRequestLauncher: ActivityResultLauncher<Intent>
    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel

    private var layoutResId: Int = 0
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        fun newInstance(layoutResId: Int): OnboardingPageFragment {
            val fragment = OnboardingPageFragment()
            fragment.arguments = Bundle().apply {
                putInt("layoutResId", layoutResId)
            }
            return fragment
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        roleRequestLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* handle result if needed */ }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        prefs = Prefs(requireContext())
        layoutResId = arguments?.getInt("layoutResId") ?: 0

        _binding = FragmentOnboardingPageOneBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        viewModel.ismlauncherDefault()

        when (val binding = binding) {
            is FragmentOnboardingPageOneBinding -> {
                val appName = getString(R.string.app_name)
                binding.title.text = getLocalizedString(R.string.welcome_to_launcher, appName)

                val privacyPolicyText = getLocalizedString(
                    R.string.continue_by_you_agree,
                    getString(R.string.privacy_policy)
                )
                val clickableText = getString(R.string.privacy_policy)

                val spannable = SpannableString(privacyPolicyText)
                val start = privacyPolicyText.indexOf(clickableText)
                val end = start + clickableText.length

                if (start >= 0) {
                    val span = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            val intent = Intent(Intent.ACTION_VIEW, getString(R.string.privacy_policy_url).toUri())
                            context?.startActivity(intent)
                        }
                    }
                    spannable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                binding.description.text = spannable
                binding.description.movementMethod = LinkMovementMethod.getInstance()

                handler.post(launcherDefaultCheckRunnable)

                binding.permissionButton.text = getLocalizedString(R.string.advanced_settings_set_as_default_launcher)
                binding.permissionButton.setOnClickListener { setDefaultHomeScreen() }

                // Setting the default launcher is offered, never required
                binding.nextButton.text = getLocalizedString(R.string.start)
                binding.nextButton.setOnClickListener { finishOnboarding() }
            }

            else -> {}
        }
    }

    private fun finishOnboarding() {
        startActivity(Intent(requireActivity(), MainActivity::class.java))
        prefs.setOnboardingCompleted(true)
        requireActivity().finish()
    }

    private val launcherDefaultCheckRunnable = object : Runnable {
        override fun run() {
            (binding as? FragmentOnboardingPageOneBinding)?.apply {
                permissionButton.isEnabled = !ismlauncherDefault(requireContext())
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(launcherDefaultCheckRunnable)
        _binding = null
    }

    private fun setDefaultHomeScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = requireContext().getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME) && !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                roleRequestLauncher.launch(intent)
            }
        } else {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            } else {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                if (fallbackIntent.resolveActivity(requireContext().packageManager) != null) {
                    startActivity(fallbackIntent)
                } else {
                    showLongToast("Unable to open settings to set default launcher.")
                }
            }
        }
    }
}
