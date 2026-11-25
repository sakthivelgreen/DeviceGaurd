// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Kiosk home screen: the approved kiosk apps as rounded tiles. */
class KioskActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* back is disabled in kiosk */ }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.kioskOn(this)) { finish(); return }
        if (Mdm(this).isOwner) {
            val am = getSystemService(ActivityManager::class.java)
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) runCatching { startLockTask() }
        }
        render()
    }

    private fun render() = scrollPage(null) {
        val title = text("Apps", 28f, bold = true)
        title.setOnLongClickListener { askPin { adminMenu() }; true }
        text("This phone is in kiosk mode.", 13f).alpha = 0.6f

        val pm = packageManager
        val apps = Prefs.kioskPkgs(context).mapNotNull { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val label = Apps.label(context, pkg)
            Triple(pkg, label, launch)
        }.sortedBy { it.second.lowercase() }

        if (apps.isEmpty()) card { text("No kiosk apps available.", 16f) }
        tileGrid(apps.map { (pkg, label, launch) ->
            Tile(label, color = Palette.BLUE, image = Apps.icon(context, pkg)) {
                act { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        })
    }

    private fun adminMenu() {
        MaterialAlertDialogBuilder(this).setTitle("Administrator")
            .setItems(arrayOf("Exit kiosk mode", "Open admin panel")) { _, i ->
                when (i) {
                    0 -> {
                        Kiosk.stop(this)
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                    1 -> startActivity(Intent(this, MainActivity::class.java))
                }
            }.show()
    }
}
