// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * PC backup for a forgotten PIN. Only senders holding android.permission.DUMP can reach it —
 * that's the ADB shell (and the system), so it needs physical access + an authorized PC:
 *
 *   adb shell am broadcast -a com.devguard.mdm.RESET_PIN -n com.devguard.mdm/.PinResetReceiver
 *
 * The PIN is removed; opening the app then asks to create a new PIN (all policies stay in place).
 */
class PinResetReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != ACTION) return
        // PIN + authenticator lock (the authenticator phone may be lost) removed, wrong-attempt lockout cleared.
        Session.resetCredentials(c)
        resultData = "DeviceGuard PIN cleared (and two-factor turned off). Open the app on the phone to create a new PIN."
    }

    companion object {
        const val ACTION = "com.devguard.mdm.RESET_PIN"
        const val ADB_COMMAND = "adb shell am broadcast -a $ACTION -n ${BuildConfig.APPLICATION_ID}/.PinResetReceiver"
    }
}
