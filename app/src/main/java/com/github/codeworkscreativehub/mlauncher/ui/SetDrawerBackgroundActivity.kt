package com.github.codeworkscreativehub.mlauncher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.github.codeworkscreativehub.mlauncher.helper.DrawerBackground
import kotlin.concurrent.thread

/**
 * Share target ("Set as drawer background"): copies a shared image into app storage and uses it
 * behind the app drawer. No UI beyond a toast. Needed because this phone won't let the launcher
 * read the wallpaper itself.
 */
class SetDrawerBackgroundActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = sharedImage(intent)
        if (uri == null) {
            Toast.makeText(applicationContext, "No image to use", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val app = applicationContext
        // Import on a worker, but keep this activity (and its URI grant) alive until it's done
        thread(name = "drawer-bg-import") {
            val ok = DrawerBackground.importImage(this, uri)
            runOnUiThread {
                Toast.makeText(
                    app,
                    if (ok) "Drawer background set" else "Could not use that image",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun sharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return intent?.data
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }
}
