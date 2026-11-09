// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.Manifest
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Mdm(context).ensureBaseline()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Disabling DeviceGuard MDM removes all protections on this device."
}

data class Restriction(val key: String, val title: String, val note: String? = null, val danger: Boolean = false)

val RESTRICTION_GROUPS: List<Pair<String, List<Restriction>>> = listOf(
    "Protection" to listOf(
        Restriction(UserManager.DISALLOW_FACTORY_RESET, "Block factory reset", "Reset from Settings is disabled"),
        Restriction(UserManager.DISALLOW_SAFE_BOOT, "Block safe mode", "Stops booting into safe mode to get around controls"),
        Restriction(UserManager.DISALLOW_ADD_USER, "Block adding users / guest"),
        Restriction(UserManager.DISALLOW_MODIFY_ACCOUNTS, "Block adding or removing accounts", "Sign in to your Google account BEFORE turning this on"),
        Restriction(UserManager.DISALLOW_APPS_CONTROL, "Block app settings changes", "Users can't clear data, force stop or disable apps"),
        Restriction(
            UserManager.DISALLOW_DEBUGGING_FEATURES, "Block Developer options & USB debugging",
            "You will lose ADB access to this phone. Only this admin panel can undo it.", danger = true,
        ),
    ),
    "Apps" to listOf(
        Restriction(UserManager.DISALLOW_INSTALL_APPS, "Block all app installs", "Includes Play Store and ADB installs"),
        Restriction(UserManager.DISALLOW_UNINSTALL_APPS, "Block all app uninstalls"),
        Restriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, "Block APK sideloading", "Only Play Store installs allowed"),
    ),
    "Network" to listOf(
        Restriction(UserManager.DISALLOW_CONFIG_WIFI, "Lock Wi-Fi settings"),
        Restriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, "Lock mobile network settings"),
        Restriction(UserManager.DISALLOW_CONFIG_TETHERING, "Block hotspot & tethering"),
        Restriction(UserManager.DISALLOW_CONFIG_BLUETOOTH, "Lock Bluetooth settings"),
        Restriction(UserManager.DISALLOW_CONFIG_VPN, "Lock VPN settings", "Recommended with website blocking"),
        Restriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, "Lock Private DNS", "Recommended with website blocking"),
    ),
    "Device" to listOf(
        Restriction(UserManager.DISALLOW_USB_FILE_TRANSFER, "Block USB file transfer"),
        Restriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, "Block SD card / USB storage"),
        Restriction(UserManager.DISALLOW_CONFIG_DATE_TIME, "Lock date & time"),
        Restriction(UserManager.DISALLOW_CONFIG_LOCATION, "Lock location settings"),
        Restriction(UserManager.DISALLOW_OUTGOING_CALLS, "Block outgoing calls"),
        Restriction(UserManager.DISALLOW_SMS, "Block SMS"),
    ),
)

/** Thin wrapper over DevicePolicyManager for this app's admin component. */
class Mdm(private val c: Context) {
    val dpm: DevicePolicyManager = c.getSystemService(DevicePolicyManager::class.java)
    val admin = ComponentName(c, AdminReceiver::class.java)
    val pkg: String = c.packageName

    val isOwner: Boolean get() = dpm.isDeviceOwnerApp(pkg)

    fun hasRestriction(key: String) = isOwner && dpm.getUserRestrictions(admin).getBoolean(key)
    fun setRestriction(key: String, on: Boolean) {
        if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
    }

    fun isHidden(p: String) = isOwner && runCatching { dpm.isApplicationHidden(admin, p) }.getOrDefault(false)
    fun setHidden(p: String, on: Boolean): Boolean = dpm.setApplicationHidden(admin, p, on)

    fun isSuspended(p: String) = isOwner && runCatching { dpm.isPackageSuspended(admin, p) }.getOrDefault(false)
    /** Returns the packages that could NOT be changed. */
    fun setSuspended(p: Collection<String>, on: Boolean): List<String> =
        dpm.setPackagesSuspended(admin, p.toTypedArray(), on).toList()

    fun isUninstallBlocked(p: String) = isOwner && runCatching { dpm.isUninstallBlocked(admin, p) }.getOrDefault(false)
    fun setUninstallBlocked(p: String, on: Boolean) = dpm.setUninstallBlocked(admin, p, on)

    /** Self-protection applied whenever we are device owner. */
    fun ensureBaseline() {
        if (!isOwner) return
        runCatching { dpm.setUninstallBlocked(admin, pkg, true) }
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            dpm.setPermissionGrantState(
                admin, pkg, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
    }

    /** Undo every policy and give up device ownership. */
    fun releaseAll(a: Activity) {
        Kiosk.stop(a)
        WebFilter.disable(a)
        Approval.disable(a)
        ChromePolicy.clear(a)
        runCatching { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
        RESTRICTION_GROUPS.flatMap { it.second }.forEach { r -> runCatching { dpm.clearUserRestriction(admin, r.key) } }
        runCatching { dpm.setCameraDisabled(admin, false) }
        runCatching { dpm.setScreenCaptureDisabled(admin, false) }
        a.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES).forEach {
            val p = it.packageName
            if (isHidden(p)) runCatching { setHidden(p, false) }
            if (isSuspended(p)) runCatching { setSuspended(listOf(p), false) }
            if (isUninstallBlocked(p)) runCatching { setUninstallBlocked(p, false) }
        }
        runCatching { dpm.setUninstallBlocked(admin, pkg, false) }
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(pkg)
    }

    companion object {
        const val PLAY_STORE = "com.android.vending"
    }
}
