// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context

/**
 * Lock screen & branding (Device Owner):
 *  - Organization name → "This device is managed by <name>" (lock screen, quick settings, Settings).
 *  - Lock screen message → an extra line on the lock screen (setDeviceOwnerLockScreenInfo).
 *  - Support messages → shown when a blocked setting is tapped, and in Settings → device admin info.
 * The "managed" notice itself can't be hidden: Android always shows it for a Device Owner.
 * A key that was never set uses the default; an empty value means "don't show".
 */
object Branding {
    const val ORG = "brandOrg"
    const val LOCK = "brandLock"
    const val SHORT = "brandShort"
    const val LONG = "brandLong"

    data class Field(val key: String, val title: String, val default: String, val maxLen: Int)

    val FIELDS = listOf(
        Field(ORG, "Organization name", "Sakthivel J", 60),
        Field(LOCK, "Lock screen message", "Managed by Sakthivel J", 120),
        Field(SHORT, "Message when something is blocked", "Blocked by Sakthivel J", 200),
        Field(LONG, "Device admin info (Settings)", "This phone is managed by Sakthivel J with DeviceGuard MDM.", 2000),
    )

    fun value(c: Context, f: Field): String = Prefs.getStr(c, f.key) ?: f.default

    fun set(c: Context, key: String, v: String) { Prefs.setStr(c, key, v.trim()); apply(c) }

    fun resetDefaults(c: Context) { FIELDS.forEach { Prefs.setStr(c, it.key, null) }; apply(c) }

    /** Pushes the stored (or default) texts to Android. Safe to call often. */
    fun apply(c: Context) {
        val m = Mdm(c)
        if (!m.isOwner) return
        fun v(key: String) = FIELDS.first { it.key == key }.let { value(c, it) }.ifBlank { null }
        runCatching { m.dpm.setOrganizationName(m.admin, v(ORG)) }
        runCatching { m.dpm.setDeviceOwnerLockScreenInfo(m.admin, v(LOCK)) }
        runCatching { m.dpm.setShortSupportMessage(m.admin, v(SHORT)) }
        runCatching { m.dpm.setLongSupportMessage(m.admin, v(LONG)) }
    }
}
