package com.github.codeworkscreativehub.mlauncher.ui.iconpack

import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.github.codeworkscreativehub.common.getLocalizedString
import com.github.codeworkscreativehub.mlauncher.MainViewModel
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.Constants
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import com.github.codeworkscreativehub.mlauncher.helper.IconCacheTarget
import com.github.codeworkscreativehub.mlauncher.helper.IconPackHelper
import com.github.codeworkscreativehub.mlauncher.helper.utils.AppReloader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class ApplyIconPackActivity : androidx.appcompat.app.AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val packageName = intent.getStringExtra("packageName").toString()
        val packageClass = intent.getStringExtra("packageClass").toString()
        if (packageClass.isNotEmpty()) {
            // Views use the dialog's themed context so the checkboxes get the mauve tint
            val builder = MaterialAlertDialogBuilder(this)
            val ctx = builder.context
            val dp = resources.displayMetrics.density
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
            }

            fun option(label: Int) = CheckBox(ctx).apply {
                text = getLocalizedString(label)
                isChecked = true // default value
                minHeight = (56 * dp).toInt()
                textSize = 17f
                setTextColor(ContextCompat.getColor(ctx, R.color.ui_text))
                setPaddingRelative((12 * dp).toInt(), 0, 0, 0)
            }

            val checkBoxHome = option(R.string.apply_to_home)
            val checkBoxAppList = option(R.string.apply_to_app_list)

            // Add the CheckBoxes to the layout
            layout.addView(checkBoxHome)
            layout.addView(checkBoxAppList)

            builder
                .setTitle(getLocalizedString(R.string.apply_icon_pack))
                .setMessage(getLocalizedString(R.string.apply_icon_pack_are_you_sure, packageName))
                .setView(layout)
                .setPositiveButton(getLocalizedString(R.string.apply)) { _, _ ->

                    val iconPackType = Constants.IconPacks.Custom
                    val customIconPackType = packageClass

                    if (checkBoxHome.isChecked) {
                        val executor = Executors.newSingleThreadExecutor()
                        executor.execute {
                            IconPackHelper.preloadIcons(this, customIconPackType, IconCacheTarget.HOME)
                        }
                        prefs.iconPackHome = iconPackType
                        viewModel.iconPackHome.value = iconPackType
                        prefs.customIconPackHome = customIconPackType
                        viewModel.customIconPackHome.value = customIconPackType
                    }

                    if (checkBoxAppList.isChecked) {
                        val executor = Executors.newSingleThreadExecutor()
                        executor.execute {
                            IconPackHelper.preloadIcons(this, customIconPackType, IconCacheTarget.APP_LIST)
                        }
                        prefs.iconPackAppList = iconPackType
                        viewModel.iconPackAppList.value = iconPackType
                        prefs.customIconPackAppList = customIconPackType
                        viewModel.customIconPackAppList.value = customIconPackType
                    }

                    AppReloader.restartApp(this)
                }
                .setNegativeButton(getLocalizedString(R.string.cancel)) { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()

        } else {
            finish()
        }
    }
}

