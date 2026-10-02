package com.github.codeworkscreativehub.mlauncher.ui.iconpack


import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.util.TypedValue
import android.view.View
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.github.codeworkscreativehub.common.getLocalizedString
import com.github.codeworkscreativehub.mlauncher.MainViewModel
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.Constants
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import com.github.codeworkscreativehub.mlauncher.helper.IconCacheTarget
import com.github.codeworkscreativehub.mlauncher.helper.IconPackHelper
import com.github.codeworkscreativehub.mlauncher.helper.emptyString
import com.github.codeworkscreativehub.mlauncher.helper.iconPackActions
import com.github.codeworkscreativehub.mlauncher.helper.iconPackBlacklist
import com.github.codeworkscreativehub.mlauncher.helper.utils.AppReloader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class CustomIconSelectionActivity : androidx.appcompat.app.AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    val defaultPackage = "default"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val installedIconPacks = findInstalledIconPacks()
        val iconCacheTarget = intent.getStringExtra("IconCacheTarget")
        val currentSelected = when (iconCacheTarget) {
            IconCacheTarget.HOME.name -> prefs.customIconPackHome
            IconCacheTarget.APP_LIST.name -> prefs.customIconPackAppList
            else -> throw IllegalArgumentException("Invalid IconCacheTarget")
        }

        showIconPackSelectionDialog(
            context = this,
            iconPacks = installedIconPacks,
            currentPackage = currentSelected
        ) { selectedPack ->
            val isDefault = selectedPack.packageName == defaultPackage

            val iconPackType =
                if (isDefault) Constants.IconPacks.System else Constants.IconPacks.Custom
            val customIconPackType =
                if (iconPackType == Constants.IconPacks.Custom) selectedPack.packageName else emptyString()

            if (iconPackType == Constants.IconPacks.Custom) {
                val executor = Executors.newSingleThreadExecutor()
                executor.execute {
                    IconPackHelper.preloadIcons(this, customIconPackType, IconCacheTarget.HOME)
                    IconPackHelper.preloadIcons(this, customIconPackType, IconCacheTarget.APP_LIST)
                }
            }

            when (iconCacheTarget) {
                IconCacheTarget.HOME.name -> {
                    prefs.iconPackHome = iconPackType
                    viewModel.iconPackHome.value = iconPackType
                    prefs.customIconPackHome = customIconPackType
                    viewModel.customIconPackHome.value = customIconPackType
                }

                IconCacheTarget.APP_LIST.name -> {
                    prefs.iconPackAppList = iconPackType
                    viewModel.iconPackAppList.value = iconPackType
                    prefs.customIconPackAppList = customIconPackType
                    viewModel.customIconPackAppList.value = customIconPackType
                }
            }

            AppReloader.restartApp(this)
        }
    }

    fun showIconPackSelectionDialog(
        context: Context,
        iconPacks: List<IconPackInfo>,
        currentPackage: String?,
        onSelected: (IconPackInfo) -> Unit
    ) {
        var selectedIndex =
            iconPacks.indexOfFirst { it.packageName == currentPackage }.coerceAtLeast(0)

        // Views use the dialog's themed context so the radio buttons get the mauve tint
        val builder = MaterialAlertDialogBuilder(context)
        val ctx = builder.context
        val dp = context.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4.dp(), 0, 0)
        }

        val radios = mutableListOf<RadioButton>()
        fun select(index: Int) {
            selectedIndex = index
            radios.forEachIndexed { i, radio -> radio.isChecked = i == index }
        }

        // One 64dp row per pack: icon, name, radio at the end. The whole row is the target.
        iconPacks.forEachIndexed { index, iconPack ->
            val itemLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 64.dp()
                setPadding(24.dp(), 0, 16.dp(), 0)
                isClickable = true
                isFocusable = true
                val ripple = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { select(index) }
            }

            val icon = ImageView(ctx).apply {
                setImageDrawable(iconPack.icon)
                layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp()).apply { marginEnd = 16.dp() }
            }

            val label = TextView(ctx).apply {
                text = iconPack.name
                textSize = 17f
                setTextColor(ContextCompat.getColor(ctx, R.color.ui_text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val radio = RadioButton(ctx).apply {
                isChecked = index == selectedIndex
                // The row handles taps; the radio only shows state
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            radios.add(radio)

            itemLayout.addView(icon)
            itemLayout.addView(label)
            itemLayout.addView(radio)
            container.addView(itemLayout)
        }

        val statusTextView = TextView(ctx).apply {
            text = getLocalizedString(R.string.applying_icon_pack)
            isVisible = false
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.ui_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(24.dp(), 12.dp(), 24.dp(), 0)
            }
        }
        container.addView(statusTextView)

        val dialog = builder
            .setTitle(getLocalizedString(R.string.choose_icon_pack))
            .setView(ScrollView(ctx).apply { addView(container) })
            .setPositiveButton(getLocalizedString(R.string.apply), null) // We override this below
            .setNegativeButton(getLocalizedString(R.string.cancel)) { _, _ ->
                finish()
            }
            // Back or a tap outside must not leave this transparent activity behind
            .setOnCancelListener { finish() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                // Make the status visible immediately
                statusTextView.isVisible = true

                // Delay heavy logic to let UI update first
                statusTextView.post {
                    onSelected(iconPacks[selectedIndex])
                }
            }
        }

        dialog.show()
    }

    private fun findInstalledIconPacks(): List<IconPackInfo> {
        val iconPacks = mutableListOf<IconPackInfo>()
        val tempIconPacks = mutableListOf<IconPackInfo>()
        val packageManager = packageManager
        val uniquePackages = mutableSetOf<String>()

        for (action in iconPackActions) {
            val intent = Intent(action)
            val resolveInfos =
                packageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            for (resolveInfo in resolveInfos) {
                val packageName = resolveInfo.activityInfo.packageName

                if (packageName in iconPackBlacklist) continue

                if (uniquePackages.add(packageName)) {
                    val appName = resolveInfo.loadLabel(packageManager).toString()
                    val icon = resolveInfo.loadIcon(packageManager)
                    tempIconPacks.add(IconPackInfo(packageName, appName, icon))
                }
            }
        }

        // Sort alphabetically before adding
        iconPacks.addAll(tempIconPacks.sortedBy { it.name.lowercase() })

        // Optionally add system default at top
        val defaultLabel = "System Default"
        val defaultIcon = ContextCompat.getDrawable(this, android.R.drawable.sym_def_app_icon)
        if (defaultIcon != null) {
            iconPacks.add(0, IconPackInfo(defaultPackage, defaultLabel, defaultIcon))
        }

        return iconPacks
    }


    data class IconPackInfo(val packageName: String, val name: String, val icon: Drawable)
}