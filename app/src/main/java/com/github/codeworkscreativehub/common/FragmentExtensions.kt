package com.github.codeworkscreativehub.common

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.helper.FontManager

fun Fragment.showLongToast(message: String) {
    showCustomToast(this, message, iconRes = R.drawable.ic_toast, delayMillis = 3000L)
}

fun Fragment.showShortToast(message: String) {
    showCustomToast(this, message, iconRes = R.drawable.ic_toast, delayMillis = 2000L)
}

fun Fragment.showInstantToast(message: String) {
    showCustomToast(this, message, iconRes = R.drawable.ic_toast)
}

/** Floating pill above the bottom edge: elevated plum surface, mauve icon, theme text. */
fun showCustomToast(
    fragment: Fragment,
    message: String,
    @DrawableRes iconRes: Int? = null,
    delayMillis: Long = 500L // Optional delay time before hiding
) {
    val context = fragment.requireContext()
    val rootView = fragment.requireActivity().window.decorView as ViewGroup

    fun Int.dp(): Int = (this * fragment.resources.displayMetrics.density).toInt()

    val overlay = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 52.dp()
        background = GradientDrawable().apply {
            cornerRadius = 26.dp().toFloat()
            setColor(ContextCompat.getColor(context, R.color.ui_elevated))
        }
        setPadding(if (iconRes != null) 16.dp() else 22.dp(), 12.dp(), 22.dp(), 12.dp())
        alpha = 0f
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = 120.dp()
            leftMargin = 24.dp()
            rightMargin = 24.dp()
        }
    }

    iconRes?.let {
        overlay.addView(AppCompatImageView(context).apply {
            setImageResource(it)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ui_accent))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(20.dp(), 20.dp()).apply { marginEnd = 12.dp() }
        })
    }

    overlay.addView(TextView(context).apply {
        text = message
        setTextColor(ContextCompat.getColor(context, R.color.ui_text))
        textSize = 15f
        FontManager.getTypeface(context)?.let { typeface = it }
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    })

    rootView.addView(overlay)
    overlay.animate()
        .alpha(1f)
        .setDuration(150)
        .withEndAction {
            overlay.animate()
                .alpha(0f)
                .setDuration(250)
                .setStartDelay(delayMillis)
                .withEndAction { rootView.removeView(overlay) }
                .start()
        }
        .start()
}


@SuppressLint("ServiceCast")
fun Fragment.hideKeyboard() {
    val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val view = activity?.currentFocus ?: View(requireContext()) // Use current focus or a new view
    if (inputMethodManager.isActive) { // Check if IME is active before hiding
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
