// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

enum class BloatCat(val label: String, val color: Int, val help: String) {
    SAFE("Safe", Palette.GREEN, "Safe to disable or remove. Nothing important depends on these."),
    CAUTION("Caution", Palette.ORANGE, "Removable, but you lose a feature. Read the note first."),
    KEEP("Keep", Palette.RED, "Needed by the system. Disabling these can break calls, SMS, setup or boot."),
    UNKNOWN("Unknown", Palette.SLATE, "Not in the list. Leave these alone unless you know what they do."),
}

data class BloatEntry(val pkg: String, val cat: BloatCat, val name: String, val note: String)

enum class AppState(val label: String) { ACTIVE("Active"), DISABLED("Disabled"), HIDDEN("Disabled (MDM)"), REMOVED("Removed") }

/** Bloatware catalogue (assets/bloat.csv, shared with tools/debloat.ps1) and actions. */
object Bloat {
    @Volatile private var db: Map<String, BloatEntry>? = null

    fun db(c: Context): Map<String, BloatEntry> = db ?: c.assets.open("bloat.csv").bufferedReader().readLines()
        .drop(1)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val p = line.split(",", limit = 4)
            if (p.size < 4) null
            else runCatching { BloatEntry(p[0].trim(), BloatCat.valueOf(p[1].trim()), p[2].trim(), p[3].trim()) }.getOrNull()
        }
        .associateBy { it.pkg }
        .also { db = it }

    fun classify(c: Context, pkg: String): BloatEntry = db(c)[pkg] ?: when {
        pkg == "android" || "overlay" in pkg || "resources" in pkg ||
            pkg.startsWith("com.android.providers") || pkg.startsWith("com.android.internal") ||
            pkg.startsWith("com.google.mainline") || pkg.startsWith("com.android.cts") ||
            pkg.startsWith("com.android.networkstack") || pkg.startsWith("com.google.android.overlay") ->
            BloatEntry(pkg, BloatCat.KEEP, pkg, "Core system component")
        else -> BloatEntry(pkg, BloatCat.UNKNOWN, pkg, "Not in the list. Leave it alone unless you know what it is.")
    }

    /** All system packages on the phone, including ones removed for this user. */
    fun systemApps(c: Context): List<ApplicationInfo> =
        c.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && it.packageName != c.packageName }

    fun state(m: Mdm, info: ApplicationInfo): AppState = when {
        (info.flags and ApplicationInfo.FLAG_INSTALLED) == 0 -> AppState.REMOVED
        m.isHidden(info.packageName) -> AppState.HIDDEN
        !info.enabled -> AppState.DISABLED
        else -> AppState.ACTIVE
    }

    fun adbDisable(pkg: String) = "adb shell pm disable-user --user 0 $pkg"
    fun adbRemove(pkg: String) = "adb shell pm uninstall -k --user 0 $pkg"
    fun adbRestore(pkg: String) = "adb shell cmd package install-existing $pkg"
    fun adbEnable(pkg: String) = "adb shell pm enable $pkg"

    fun copy(c: Context, text: String) {
        c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", text))
        c.toast("Copied")
    }
}
