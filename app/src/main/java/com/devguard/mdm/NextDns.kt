// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * NextDNS category blocking through its API (https://api.nextdns.io). Needs the API key (nextdns.io →
 * Account → API) and the profile ID (e.g. abc123). The profile is then used as the phone's Private DNS
 * ("abc123.dns.nextdns.io"), so categories apply to every app, Private Space included.
 * All calls are blocking — run them off the main thread.
 */
object NextDns {
    val CATEGORIES = listOf(
        "porn" to "Adult content",
        "gambling" to "Gambling",
        "dating" to "Dating",
        "piracy" to "Piracy & illegal downloads",
        "social-networks" to "Social networks",
        "gaming" to "Online gaming",
        "video-streaming" to "Video streaming",
    )

    data class State(val categories: Map<String, Boolean>, val safeSearch: Boolean, val youtube: Boolean, val blockBypass: Boolean)

    fun key(c: Context) = Secrets.get(c, Prefs.NEXTDNS_KEY)?.trim()?.takeIf { it.isNotEmpty() } // encrypted (S-03)
    fun id(c: Context) = Prefs.getStr(c, Prefs.NEXTDNS_ID)?.trim()?.takeIf { it.isNotEmpty() }
    fun configured(c: Context) = key(c) != null && id(c) != null
    fun host(c: Context) = "${id(c)}.dns.nextdns.io"

    private fun call(c: Context, method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val conn = (URL("https://api.nextdns.io/profiles/${id(c)}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("X-Api-Key", key(c))
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        val code = conn.responseCode
        val text = runCatching { (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: "" }.getOrDefault("")
        return code to text
    }

    private fun check(r: Pair<Int, String>, what: String) {
        if (r.first == 401 || r.first == 403) throw IllegalStateException("NextDNS rejected the API key")
        if (r.first == 404) throw IllegalStateException("NextDNS profile not found")
        if (r.first >= 400) throw IllegalStateException("$what failed (HTTP ${r.first})")
    }

    fun load(c: Context): State {
        val r = call(c, "GET", "/parentalControl")
        check(r, "Loading settings")
        val d = JSONObject(r.second).optJSONObject("data") ?: JSONObject()
        val cats = d.optJSONArray("categories")
        val map = HashMap<String, Boolean>()
        if (cats != null) for (i in 0 until cats.length()) {
            val o = cats.getJSONObject(i)
            map[o.getString("id")] = o.optBoolean("active", true)
        }
        return State(map, d.optBoolean("safeSearch"), d.optBoolean("youtubeRestrictedMode"), d.optBoolean("blockBypass"))
    }

    fun setCategory(c: Context, cat: String, on: Boolean) {
        if (on) {
            val r = call(c, "PATCH", "/parentalControl/categories/$cat", JSONObject().put("active", true))
            if (r.first == 404) check(call(c, "POST", "/parentalControl/categories", JSONObject().put("id", cat).put("active", true)), "Adding category")
            else check(r, "Updating category")
        } else {
            val r = call(c, "DELETE", "/parentalControl/categories/$cat")
            if (r.first != 404) check(r, "Removing category")
        }
    }

    fun setFlag(c: Context, field: String, on: Boolean) =
        check(call(c, "PATCH", "/parentalControl", JSONObject().put(field, on)), "Updating setting")

    /** Adds every domain from DeviceGuard's blocked-site list to the NextDNS denylist. Returns how many were added. */
    fun syncDenylist(c: Context): Int {
        val r = call(c, "GET", "/denylist")
        check(r, "Loading denylist")
        val arr = JSONObject(r.second).optJSONArray("data")
        val existing = HashSet<String>()
        if (arr != null) for (i in 0 until arr.length()) existing += arr.getJSONObject(i).optString("id")
        var n = 0
        (Prefs.domains(c) - existing).forEach { d ->
            if (call(c, "POST", "/denylist", JSONObject().put("id", d).put("active", true)).first < 400) n++
        }
        return n
    }
}
