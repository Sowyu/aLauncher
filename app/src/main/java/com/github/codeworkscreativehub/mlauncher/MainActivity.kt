package com.github.codeworkscreativehub.mlauncher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import com.github.codeworkscreativehub.common.CrashHandler
import com.github.codeworkscreativehub.common.LauncherLocaleManager
import com.github.codeworkscreativehub.common.showLongToast
import com.github.codeworkscreativehub.mlauncher.data.Constants
import com.github.codeworkscreativehub.mlauncher.data.Migration
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import com.github.codeworkscreativehub.mlauncher.databinding.ActivityMainBinding
import com.github.codeworkscreativehub.mlauncher.helper.DrawerBackground
import com.github.codeworkscreativehub.mlauncher.helper.IconCacheTarget
import com.github.codeworkscreativehub.mlauncher.helper.IconPackHelper
import com.github.codeworkscreativehub.mlauncher.helper.emptyString
import com.github.codeworkscreativehub.mlauncher.helper.ismlauncherDefault
import com.github.codeworkscreativehub.mlauncher.helper.utils.AppReloader
import com.github.codeworkscreativehub.mlauncher.helper.utils.SystemBarObserver
import com.github.codeworkscreativehub.mlauncher.ui.HomeFragment
import com.github.codeworkscreativehub.mlauncher.ui.onboarding.OnboardingActivity
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var migration: Migration
    private lateinit var navController: NavController
    private lateinit var viewModel: MainViewModel
    private lateinit var binding: ActivityMainBinding

    private lateinit var performFullBackup: ActivityResultLauncher<Intent>
    private lateinit var performFullRestore: ActivityResultLauncher<Intent>

    private lateinit var performThemeBackup: ActivityResultLauncher<Intent>
    private lateinit var performThemeRestore: ActivityResultLauncher<Intent>


    private lateinit var pickCustomFont: ActivityResultLauncher<Array<String>>

    private lateinit var pickDrawerBackground: ActivityResultLauncher<Intent>
    private var onDrawerBackgroundPicked: ((Boolean) -> Unit)? = null

    private lateinit var setDefaultHomeScreenLauncher: ActivityResultLauncher<Intent>

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                val home = homeFragment() ?: return false
                home.openDrawer()
                true
            }

            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
            KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P,
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T,
            KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_Z -> {
                // Typing on a hardware keyboard at home starts a drawer search
                val home = homeFragment() ?: return false
                if (home.isDrawerFullyOpen()) return super.onKeyDown(keyCode, event)
                home.openDrawer(query = ('a' + (keyCode - KeyEvent.KEYCODE_A)).toString())
                true
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                backToHomeScreen()
                true
            }

            else -> {
                super.onKeyDown(keyCode, event)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            LauncherLocaleManager.wrapContext(newBase)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enables edge-to-edge mode
        enableEdgeToEdge()

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // At home there is nowhere to go back to; elsewhere pop one screen
                if (navController.currentDestination?.id != R.id.mainFragment) {
                    navController.popBackStack()
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)

        prefs = Prefs(this)
        migration = Migration(this)

        // Initialize com.github.codeworkscreativehub.common.CrashHandler to catch uncaught exceptions
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))

        requestedOrientation = if (prefs.lockOrientation) {
            if (prefs.lockOrientationPortrait) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        if (prefs.iconPackHome == Constants.IconPacks.Custom) {
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                IconPackHelper.preloadIcons(applicationContext, prefs.customIconPackHome, IconCacheTarget.HOME)
            }
        }

        if (prefs.iconPackAppList == Constants.IconPacks.Custom) {
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                IconPackHelper.preloadIcons(applicationContext, prefs.customIconPackAppList, IconCacheTarget.APP_LIST)
            }
        }

        if (!prefs.isOnboardingCompleted()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish() // Finish MainActivity so that user can't return to it until onboarding is completed
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        migration()

        navController = this.findNavController(R.id.nav_host_fragment)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        if (prefs.firstOpen) {
            viewModel.firstOpen(true)
            prefs.firstOpen = false
        }

        viewModel.getAppList(includeHiddenApps = true)

        window.addFlags(FLAG_LAYOUT_NO_LIMITS)

        performFullBackup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    applicationContext.contentResolver.openFileDescriptor(uri, "w")?.use { file ->
                        FileOutputStream(file.fileDescriptor).use { stream ->
                            val prefs = Prefs(applicationContext).saveToString()
                            stream.channel.truncate(0)
                            stream.write(prefs.toByteArray())
                        }
                    }
                }
            }
        }

        performFullRestore = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        applicationContext.contentResolver.openInputStream(uri).use { inputStream ->
                            val stringBuilder = StringBuilder()
                            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                                var line: String? = reader.readLine()
                                while (line != null) {
                                    stringBuilder.append(line)
                                    line = reader.readLine()
                                }
                            }

                            val string = stringBuilder.toString()
                            val prefs = Prefs(applicationContext)
                            if (prefs.loadFromString(string, clearFirst = true)) {
                                AppReloader.restartApp(applicationContext)
                            } else {
                                showLongToast("This file is not a valid backup")
                            }
                        }
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                        showLongToast("Selected file could not be opened")
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                        showLongToast("Permission denied")
                    } catch (e: Exception) {
                        e.printStackTrace()
                        showLongToast("Could not read that backup")
                    }
                }
            }
        }

        performThemeBackup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Step 1: Read the color names from theme.xml
                val colorNames = mutableListOf<String>()

                // Obtain an XmlPullParser for the theme.xml file
                applicationContext.resources.getXml(R.xml.theme).use { parser ->
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "color") {
                            val colorName = parser.getAttributeValue(null, "colorName")
                            colorNames.add(colorName)
                        }
                        parser.next()
                    }
                }

                result.data?.data?.let { uri ->
                    applicationContext.contentResolver.openFileDescriptor(uri, "w")?.use { file ->
                        FileOutputStream(file.fileDescriptor).use { stream ->
                            // Get the filtered preferences (only those in the colorNames list)
                            val prefs = Prefs(applicationContext).saveToTheme(colorNames)
                            stream.channel.truncate(0)
                            stream.write(prefs.toByteArray())
                        }
                    }
                }
            }
        }

        // Correct usage: Register in onAttach() to ensure it's done before the fragment's view is created
        pickCustomFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { handleFontSelected(it) }
        }

        pickDrawerBackground = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = onDrawerBackgroundPicked
            onDrawerBackgroundPicked = null
            val uri = result.data?.data ?: result.data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (result.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { DrawerBackground.importImage(this@MainActivity, uri) }
                showLongToast(if (ok) "Drawer background set" else "Could not use that image")
                callback?.invoke(ok)
            }
        }

        performThemeRestore = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Step 1: Read the color names from theme.xml
                val colorNames = mutableListOf<String>()

                // Obtain an XmlPullParser for the theme.xml file
                applicationContext.resources.getXml(R.xml.theme).use { parser ->
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "color") {
                            val colorName = parser.getAttributeValue(null, "colorName")
                            colorNames.add(colorName)
                        }
                        parser.next()
                    }
                }

                result.data?.data?.let { uri ->
                    try {
                        applicationContext.contentResolver.openInputStream(uri).use { inputStream ->
                            val stringBuilder = StringBuilder()
                            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                                var line: String? = reader.readLine()
                                while (line != null) {
                                    stringBuilder.append(line)
                                    line = reader.readLine()
                                }
                            }

                            val string = stringBuilder.toString()
                            val prefs = Prefs(applicationContext)
                            prefs.loadFromTheme(string)
                        }
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                        showLongToast("Selected file could not be opened")
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                        showLongToast("Permission denied")
                    }
                }
            }
        }

        setDefaultHomeScreenLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
                val isDefault = ismlauncherDefault(this) // Check again if the app is now default

                if (isDefault) {
                    viewModel.setDefaultLauncher(true)
                } else {
                    viewModel.setDefaultLauncher(false)
                }
            }

        val systemBarObserver = SystemBarObserver(prefs)
        lifecycle.addObserver(systemBarObserver)
    }


    private fun handleFontSelected(uri: Uri?) {
        if (uri == null) return

        val fileName = getFileNameFromUri(this, uri)

        if (!fileName.endsWith(".ttf", ignoreCase = true)) {
            this.showLongToast("Only .ttf fonts are supported.")
            return
        }

        this.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        deleteOldFont(this)

        val savedFile = saveFontToInternalStorage(this, uri)
        if (savedFile != null && savedFile.exists()) {
            prefs.fontFamily = Constants.FontFamily.Custom
            AppReloader.restartApp(this)
        } else {
            this.showLongToast("Could not save font.")
        }
    }


    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                cursor.moveToFirst()
                return cursor.getString(nameIndex)
            }
        }
        return emptyString()
    }

    private fun deleteOldFont(context: Context) {
        val file = File(context.filesDir, "CustomFont.ttf")
        if (file.exists()) {
            file.delete()
        }
    }

    private fun saveFontToInternalStorage(context: Context, fontUri: Uri): File? {
        val file = File(context.filesDir, "CustomFont.ttf")
        return try {
            context.contentResolver.openInputStream(fontUri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun createFullBackup() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "backup_$timeStamp.json"

        val createFileIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        expectReturn()
        performFullBackup.launch(createFileIntent)
    }

    fun restoreFullBackup() {
        val openFileIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        expectReturn()
        performFullRestore.launch(openFileIntent)
    }

    fun createThemeBackup() {
        val timeStamp = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val fileName = "theme_$timeStamp.mtheme"

        val createFileIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        expectReturn()
        performThemeBackup.launch(createFileIntent)
    }

    fun restoreThemeBackup() {
        val openFileIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }
        expectReturn()
        performThemeRestore.launch(openFileIntent)
    }

    fun pickCustomFont() {
        expectReturn()
        pickCustomFont.launch(arrayOf("*/*"))
    }

    /** Let the user pick the image the app drawer blurs behind itself. [onDone] gets true on success. */
    fun pickDrawerBackground(onDone: ((Boolean) -> Unit)? = null) {
        onDrawerBackgroundPicked = onDone
        expectReturn()
        // Plain single-select photo picker: one tap picks and returns, no "Done" step
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            pickDrawerBackground.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            pickDrawerBackground.launch(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE))
        }
    }

    fun setDefaultHomeScreen(context: Context, checkDefault: Boolean = false) {
        val isDefault = ismlauncherDefault(context)
        if (checkDefault && isDefault) {
            return // Launcher is already the default home app
        }
        expectReturn()

        if (context is Activity && !isDefault) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                setDefaultHomeScreenLauncher.launch(intent)
                return
            } else {
                // For devices below API level 29, prompt the user to set the default launcher manually
                val intent = Intent(Settings.ACTION_HOME_SETTINGS)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    // Fallback: Open general settings if HOME_SETTINGS is unavailable
                    val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                    if (fallbackIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(fallbackIntent)
                    } else {
                        showLongToast("Unable to open settings to set default launcher.")
                    }
                }
            }
        }

        val intent = Intent(Settings.ACTION_HOME_SETTINGS)
        setDefaultHomeScreenLauncher.launch(intent)

    }

    /**
     * Set while a picker or screen we opened from settings is on top, so coming back lands on
     * settings again instead of being reset to the home screen. The home button still resets.
     */
    private var expectingReturn = false

    fun expectReturn() {
        expectingReturn = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button: always go home
        expectingReturn = false
    }

    override fun onStop() {
        if (!expectingReturn) backToHomeScreen()
        super.onStop()
    }

    override fun onResume() {
        if (expectingReturn) {
            expectingReturn = false
        } else {
            // Home pressed while the drawer is up: slide it away instead of cutting
            backToHomeScreen(animateDrawer = true)
        }
        super.onResume()
    }

    override fun onUserLeaveHint() {
        if (!expectingReturn) backToHomeScreen()
        super.onUserLeaveHint()
    }

    private fun backToHomeScreen(animateDrawer: Boolean = false) {
        // Whenever home button is pressed or user leaves the launcher,
        // pop all the fragments except main, and put the drawer away
        if (navController.currentDestination?.id != R.id.mainFragment)
            navController.popBackStack(R.id.mainFragment, false)
        homeFragment()?.closeDrawer(animate = animateDrawer)
        homeFragment()?.exitEditMode(animate = animateDrawer)
    }

    /** The home screen fragment, if it is the current destination. */
    private fun homeFragment(): HomeFragment? {
        if (!::navController.isInitialized || navController.currentDestination?.id != R.id.mainFragment) return null
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        return navHost?.childFragmentManager?.primaryNavigationFragment as? HomeFragment
    }

    private fun migration() {
        migration.migratePreferencesOnVersionUpdate(prefs)
        migration.removeDeletedFeatureData(prefs)
        migration.deleteOldCacheFiles(applicationContext)
    }

}
