// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager

/** Kiosk mode: lock the phone to a set of approved apps using lock task mode. */
object Kiosk {
    private const val ALIAS = "com.devguard.mdm.KioskHome"

    private fun homeFilter() = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    /** Returns null on success or an error message. */
    fun start(a: Activity): String? {
        val m = Mdm(a)
        if (!m.isOwner) return "Device Owner required for kiosk mode"
        val apps = Prefs.kioskPkgs(a)
        if (apps.isEmpty()) return "Choose kiosk apps first (Apps & Play Store → tap an app → Allowed in kiosk)"
        m.dpm.setLockTaskPackages(m.admin, (apps + a.packageName).toTypedArray())
        m.dpm.setLockTaskFeatures(
            m.admin,
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO,
        )
        val alias = ComponentName(a, ALIAS)
        a.packageManager.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        m.dpm.addPersistentPreferredActivity(m.admin, homeFilter(), alias)
        Prefs.setKioskOn(a, true)
        a.startActivity(Intent(a, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        return null
    }

    fun stop(a: Activity) {
        val m = Mdm(a)
        Prefs.setKioskOn(a, false)
        if (m.isOwner) runCatching { m.dpm.clearPackagePersistentPreferredActivities(m.admin, a.packageName) }
        runCatching {
            a.packageManager.setComponentEnabledSetting(
                ComponentName(a, ALIAS), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
        }
        runCatching { a.stopLockTask() }
        if (m.isOwner) runCatching { m.dpm.setLockTaskPackages(m.admin, arrayOf()) }
    }
}
