// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Application

/** Process start (A-03): one-time setup shared by every screen, service and receiver. */
class DeviceGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notify.createChannels(this) // once per process start, instead of on every notification
    }
}
