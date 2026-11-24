// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Force stop + "no background running".
 *
 * Android has no Device Owner API to force-stop another app, so [forceStop] hides the package and
 * immediately unhides it: hiding makes the system kill the app completely (same "stopped" state as
 * Settings → Force stop). Android's real background restriction ("Restricted" battery mode) can only be
 * set over ADB, so the app builds those commands ([adbRestrict]) and can read the state if the
 * one-time GET_APP_OPS_STATS grant exists ([osRestricted]).
 */
object AppControl {
    /** Packages being force-stopped right now (their unhide "package added" broadcast must be ignored). */
    private val ignoring = ConcurrentHashMap<String, Long>()

    const val ADB_GRANT_OPS = "adb shell pm grant com.devguard.mdm android.permission.GET_APP_OPS_STATS"

    fun isIgnored(pkg: String): Boolean {
        val until = ignoring[pkg] ?: return false
        if (SystemClock.elapsedRealtime() > until) { ignoring.remove(pkg); return false }
        return true
    }

    /** Apps that must never be force-stopped (would break the phone or DeviceGuard). Returns a reason, or null. */
    fun protectedReason(c: Context, pkg: String): String? {
        if (pkg == c.packageName) return "DeviceGuard can't stop itself"
        if (Bloat.classify(c, pkg).cat == BloatCat.KEEP) return "This is a core system app"
        val pm = c.packageManager
        val launcher = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        if (pkg == launcher) return "This is the home screen app"
        val ime = runCatching {
            android.provider.Settings.Secure.getString(c.contentResolver, "default_input_method")?.substringBefore('/')
        }.getOrNull()
        if (pkg == ime) return "This is the keyboard"
        if (pkg == runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(c) }.getOrNull()) return "This is the SMS app (OTPs)"
        if (pkg == runCatching { c.getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull())
            return "This is the phone app"
        return null
    }

    fun isStopped(c: Context, pkg: String): Boolean = runCatching {
        (c.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED) != 0
    }.getOrDefault(false)

    /** Force-stops [pkg]. Blocking (~0.5 s): call off the main thread when stopping many. Returns null on success or an error. */
    fun forceStop(c: Context, pkg: String): String? {
        val m = Mdm(c)
        if (!m.isOwner) return "Device Owner required"
        protectedReason(c, pkg)?.let { return it }
        if (m.isHidden(pkg)) return null // hidden apps can't run anyway
        ignoring[pkg] = SystemClock.elapsedRealtime() + 10_000
        return try {
            if (!m.setHidden(pkg, true)) return "Android didn't allow stopping this app"
            Thread.sleep(300)
            m.setHidden(pkg, false)
            null
        } catch (e: Exception) {
            runCatching { m.setHidden(pkg, false) }
            e.message ?: "Couldn't stop the app"
        }
    }

    /** Stops every app on the no-background list that isn't already stopped. Records the result for the UI. */
    fun stopListed(c: Context): Int {
        var n = 0
        Prefs.noBackgroundPkgs(c).forEach { pkg ->
            if (!isStopped(c, pkg) && forceStop(c, pkg) == null) n++
        }
        Prefs.setLong(c, Prefs.BG_LAST_RUN, System.currentTimeMillis())
        Prefs.setInt(c, Prefs.BG_LAST_COUNT, n)
        return n
    }

    fun setNoBackground(c: Context, pkg: String, on: Boolean) {
        Prefs.updateSet(c, Prefs.K_NO_BG) { if (on) it + pkg else it - pkg }
        GuardService.refresh(c)
    }

    /** Silent uninstall (Device Owner). Failures show a notification (InstallResultReceiver). */
    fun uninstall(c: Context, pkg: String) {
        val m = Mdm(c)
        runCatching { if (m.isUninstallBlocked(pkg)) m.setUninstallBlocked(pkg, false) }
        c.packageManager.packageInstaller.uninstall(
            pkg, InstallResultReceiver.pendingIntent(c, InstallResultReceiver.UNINSTALL, pkg).intentSender,
        )
    }

    // ---- Android "Restricted" background mode (ADB only) ----

    fun adbRestrict(pkgs: Collection<String>) = pkgs.sorted().joinToString("\n") {
        "adb shell cmd appops set $it RUN_ANY_IN_BACKGROUND ignore; adb shell am set-standby-bucket $it restricted"
    }

    fun adbUnrestrict(pkgs: Collection<String>) = pkgs.sorted().joinToString("\n") {
        "adb shell cmd appops set $it RUN_ANY_IN_BACKGROUND allow; adb shell am set-standby-bucket $it active"
    }

    fun canReadOps(c: Context) =
        c.checkSelfPermission("android.permission.GET_APP_OPS_STATS") == PackageManager.PERMISSION_GRANTED

    /** True/false if Android's Restricted background mode is on for [pkg]; null if unknown (no grant). */
    fun osRestricted(c: Context, pkg: String): Boolean? {
        if (!canReadOps(c)) return null
        return runCatching {
            val uid = c.packageManager.getApplicationInfo(pkg, 0).uid
            val ops = c.getSystemService(AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow("android:run_any_in_background", uid, pkg) == AppOpsManager.MODE_IGNORED
        }.getOrNull()
    }
}
