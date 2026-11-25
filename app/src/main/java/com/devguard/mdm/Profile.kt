// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export / import of the whole policy profile as JSON, e.g. to restore everything after a factory reset.
 * Secrets (PIN, recovery code, authenticator secret, tokens) are never exported.
 */
object Profile {
    /** Defined in Prefs, next to the keys (D-06). */
    private val SECRET_KEYS get() = Prefs.NOT_EXPORTED

    /** The profile as JSON. Slow (one system call per installed package): call it off the main thread. */
    fun export(c: Context): String {
        val m = Mdm(c)
        val prefs = JSONObject()
        Prefs.all(c).forEach { (k, v) ->
            if (k in SECRET_KEYS || (v as? String)?.startsWith("enc1:") == true) return@forEach // never a secret
            prefs.put(k, if (v is Set<*>) JSONObject().put("set", JSONArray(v.toList())) else JSONObject().put(typeOf(v), v))
        }
        val apps = Apps.packages(c)
        val dpm = JSONObject()
            .put("restrictions", m.restrictions().let { r -> JSONArray(RESTRICTION_GROUPS.flatMap { it.second }.filter { r.getBoolean(it.key) }.map { it.key }) })
            .put("camera", runCatching { m.dpm.getCameraDisabled(m.admin) }.getOrDefault(false))
            .put("screenCapture", runCatching { m.dpm.getScreenCaptureDisabled(m.admin) }.getOrDefault(false))
            // Only YOUR hides/pauses; feature-owned ones (screen time, approval, Chrome-only, blocklist) are rebuilt.
            .put("hidden", JSONArray(Holds.get(c, Hold.HIDE_MANUAL).toList()))
            .put("suspended", JSONArray(Holds.get(c, Hold.SUSPEND_MANUAL).toList()))
            .put("uninstallBlocked", JSONArray(apps.filter { it != c.packageName && m.isUninstallBlocked(it) }))
            .put("systemUpdate", Device.updateMode(c))
        return JSONObject().put("app", "DeviceGuard MDM").put("version", 1).put("created", System.currentTimeMillis())
            .put("prefs", prefs).put("device", dpm).toString(2)
    }

    private fun typeOf(v: Any?) = when (v) { is Boolean -> "bool"; is Int -> "int"; is Long -> "long"; else -> "str" }

    /** Applies an exported profile. Blocking (network for Private DNS). Returns a summary. */
    fun import(c: Context, json: String): String {
        val root = JSONObject(json)
        if (root.optString("app") != "DeviceGuard MDM") throw IllegalArgumentException("Not a DeviceGuard profile")
        val m = Mdm(c)
        val prefs = root.getJSONObject("prefs")
        prefs.keys().forEach { k ->
            if (k in SECRET_KEYS) return@forEach
            val o = prefs.getJSONObject(k)
            val v: Any? = when {
                o.has("set") -> o.getJSONArray("set").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                o.has("bool") -> o.getBoolean("bool")
                o.has("int") -> o.getInt("int")
                o.has("long") -> o.getLong("long")
                else -> o.optString("str")
            }
            Prefs.putAny(c, k, v)
        }
        val d = root.optJSONObject("device") ?: JSONObject()
        var applied = 0
        fun list(name: String) = d.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val wanted = list("restrictions").toSet()
        RESTRICTION_GROUPS.flatMap { it.second }.forEach { r ->
            runCatching { m.setRestriction(r.key, r.key in wanted); if (r.key in wanted) applied++ }
        }
        runCatching { m.dpm.setCameraDisabled(m.admin, d.optBoolean("camera")) }
        runCatching { m.dpm.setScreenCaptureDisabled(m.admin, d.optBoolean("screenCapture")) }
        list("hidden").let { applied += it.size - Holds.set(c, Hold.HIDE_MANUAL, it, true).size }
        Holds.set(c, Hold.SUSPEND_MANUAL, list("suspended"), true)
        list("uninstallBlocked").forEach { runCatching { m.setUninstallBlocked(it, true) } }
        runCatching { Device.setUpdateMode(c, d.optInt("systemUpdate", 0)) }

        // Re-apply features from the imported settings.
        runCatching { ChromePolicy.apply(c) }
        Prefs.privateDnsHost(c)?.let { runCatching { PrivateDns.apply(c, it) } }
        runCatching { Device.applyWifiAllow(c) }
        DnsVpnService.reload(c)
        GuardService.refresh(c)
        Branding.apply(c)
        runCatching { ScreenTime.evaluate(c) }
        return "Profile imported: $applied restrictions/hidden apps applied, settings restored."
    }
}
