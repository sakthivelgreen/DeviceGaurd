// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Notification channels used by the app (one place, so names and importance stay consistent). */
enum class Channel(val id: String, val title: String, val importance: Int) {
    GUARD("guard", "Device protection", NotificationManager.IMPORTANCE_LOW),
    BLOCKED("blocked", "Blocked apps", NotificationManager.IMPORTANCE_HIGH),
    APPROVALS("approvals", "App approvals", NotificationManager.IMPORTANCE_DEFAULT),
    SCREEN_TIME("screentime", "Screen time", NotificationManager.IMPORTANCE_DEFAULT),
    NETWORK("smartdns", "Network filter", NotificationManager.IMPORTANCE_LOW),
    DNS_LOCK("dnslock", "Private DNS lock", NotificationManager.IMPORTANCE_HIGH),
    TIME_LOCK("timelock", "Date & time lock", NotificationManager.IMPORTANCE_DEFAULT),
    RESULTS("results", "Install & uninstall problems", NotificationManager.IMPORTANCE_DEFAULT),
}

/** Builds and posts notifications (D-01: replaces seven copies of channel → builder → notify). */
object Notify {
    /** Creates every channel. Called once at process start ([DeviceGuardApp]); creating an existing channel is a no-op. */
    fun createChannels(c: Context) = runCatching {
        c.getSystemService(NotificationManager::class.java)
            .createNotificationChannels(Channel.entries.map { NotificationChannel(it.id, it.title, it.importance) })
    }

    fun build(c: Context, ch: Channel, title: String, text: String, ongoing: Boolean = false): Notification {
        return Notification.Builder(c, ch.id)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    fun post(c: Context, ch: Channel, id: Int, title: String, text: String, ongoing: Boolean = false) {
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, build(c, ch, title, text, ongoing)) }
    }

    fun cancel(c: Context, id: Int) {
        runCatching { c.getSystemService(NotificationManager::class.java).cancel(id) }
    }
}

/**
 * B-06: result of a silent uninstall or app update. PackageInstaller fills in EXTRA_STATUS; on failure the
 * user gets a notification instead of nothing (before, these results were sent to a broadcast nobody received).
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val what = i.getStringExtra(EXTRA_WHAT) ?: UNINSTALL
        val pkg = i.getStringExtra(EXTRA_PKG) ?: ""
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Shouldn't happen for a Device Owner; if Android insists, show its confirmation screen.
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    runCatching { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() } ?: "status $status"
                val title = if (what == UPDATE) "DeviceGuard update failed" else "Couldn't uninstall ${Blocklist.label(c, pkg)}"
                Notify.post(c, Channel.RESULTS, ("res$what$pkg").hashCode(), title, "Android said: $msg")
            }
        }
    }

    companion object {
        const val UNINSTALL = "uninstall"
        const val UPDATE = "update"
        private const val EXTRA_WHAT = "dg_what"
        private const val EXTRA_PKG = "dg_pkg"

        /** Status receiver for PackageInstaller. Must be MUTABLE so Android can add the result extras. */
        fun pendingIntent(c: Context, what: String, pkg: String): PendingIntent = PendingIntent.getBroadcast(
            c, ("$what:$pkg").hashCode(),
            Intent(c, InstallResultReceiver::class.java).putExtra(EXTRA_WHAT, what).putExtra(EXTRA_PKG, pkg),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
