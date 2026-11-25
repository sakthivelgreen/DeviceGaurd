// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context

/** Approved-apps mode: newly installed apps stay paused until the admin approves them. */
object Approval {
    private fun installed(c: Context) = Apps.packages(c).toSet()

    fun enable(c: Context) {
        // Everything already on the phone counts as approved.
        Prefs.updateSet(c, Prefs.K_APPROVED) { it + installed(c) }
        Prefs.setApprovalMode(c, true)
        GuardService.refresh(c)
    }

    fun disable(c: Context) {
        Prefs.setApprovalMode(c, false)
        GuardService.refresh(c)
        // Drops only approval's claims; apps paused by you or screen time stay paused.
        Holds.set(c, Hold.SUSPEND_APPROVAL, Prefs.autoSuspended(c), false)
    }

    fun onInstalled(c: Context, pkg: String) {
        if (!Prefs.approvalMode(c) || pkg == c.packageName || pkg in Prefs.approved(c)) return
        // Apps the admin installs while the panel is unlocked are approved automatically.
        if (Session.valid()) approve(c, pkg) else pause(c, pkg)
    }

    fun approve(c: Context, pkg: String) {
        Prefs.updateSet(c, Prefs.K_APPROVED) { it + pkg }
        if (pkg in Prefs.autoSuspended(c)) Holds.set(c, Hold.SUSPEND_APPROVAL, pkg, false)
    }

    fun unapprove(c: Context, pkg: String) {
        Prefs.updateSet(c, Prefs.K_APPROVED) { it - pkg }
        if (Prefs.approvalMode(c)) pause(c, pkg)
    }

    private fun pause(c: Context, pkg: String) {
        if (!Mdm(c).isOwner) return
        if (Holds.set(c, Hold.SUSPEND_APPROVAL, pkg, true)) notifyPaused(c, pkg)
    }

    /** Pause any non-system app that isn't approved (e.g. installed while the service wasn't running). */
    fun sweep(c: Context) {
        if (!Prefs.approvalMode(c)) return
        val approved = Prefs.approved(c)
        val claimed = Prefs.autoSuspended(c)
        Apps.list(c)
            .filter { !Apps.isSystem(it) && it.packageName != c.packageName }
            .filter { it.packageName !in approved && it.packageName !in claimed }
            .forEach { pause(c, it.packageName) }
    }

    private fun notifyPaused(c: Context, pkg: String) =
        Notify.post(c, Channel.APPROVALS, pkg.hashCode(), "${Blocklist.label(c, pkg)} is paused", "New apps need administrator approval.")
}
