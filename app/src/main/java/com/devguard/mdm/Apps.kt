// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Installed apps, their names and icons, in one place (D-02).
 *
 * Listing apps is a system call, and loading a name or icon reads that app's resources, so call [list],
 * [userApps] and [packages] off the main thread (`inBackground`). Names are cached for the life of the
 * process (a renamed app shows its new name after DeviceGuard restarts). Icons load in the background with
 * [loadIcon], so rows appear at once and icons follow.
 */
object Apps {
    /** With [list]: also hidden apps and system apps removed for this user. */
    const val INCLUDE_REMOVED = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS

    fun list(c: Context, flags: Int = 0): List<ApplicationInfo> = c.packageManager.getInstalledApplications(flags)

    /** Every package name, hidden apps included: what policy loops (export, release, migration) need. */
    fun packages(c: Context): List<String> = list(c, PackageManager.MATCH_UNINSTALLED_PACKAGES).map { it.packageName }

    fun isSystem(info: ApplicationInfo) = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    /** Apps the user installed (no system apps, not DeviceGuard) with their names, sorted by name. */
    fun userApps(c: Context): List<Pair<ApplicationInfo, String>> =
        list(c).filter { !isSystem(it) && it.packageName != c.packageName }
            .map { it to label(c, it) }
            .sortedBy { it.second.lowercase() }

    private val labels = ConcurrentHashMap<String, String>()

    fun label(c: Context, info: ApplicationInfo): String = labels.getOrPut(info.packageName) {
        runCatching { c.packageManager.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
    }

    /** The app's name, or [pkg] itself if it isn't installed. */
    fun label(c: Context, pkg: String): String = labels[pkg]
        ?: runCatching { label(c, c.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)) }
            .getOrDefault(pkg)

    fun icon(c: Context, info: ApplicationInfo): Drawable? = runCatching { c.packageManager.getApplicationIcon(info) }.getOrNull()
    fun icon(c: Context, pkg: String): Drawable? = runCatching { c.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** Two threads: a page with hundreds of rows queues its icons instead of starting hundreds of threads. */
    private val iconWork = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Loads [pkg]'s icon into [view] in the background. Safe for recycled rows (the view's tag is checked). */
    fun loadIcon(view: ImageView, pkg: String, info: ApplicationInfo? = null) {
        view.tag = pkg
        view.setImageDrawable(null)
        val c = view.context.applicationContext
        iconWork.execute {
            val d = if (info != null) icon(c, info) else icon(c, pkg)
            main.post { if (view.tag == pkg) view.setImageDrawable(d) }
        }
    }
}
