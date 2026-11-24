// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

/** Device-wide restrictions (user restrictions, camera, screenshots). */
class RestrictionsActivity : BaseActivity() {

    override fun onResume() {
        super.onResume()
        if (!isFinishing) render()
    }

    /** Applies a switch change and redraws. (The filter guard is only for website filters, not restrictions.) */
    private fun change(apply: () -> Unit): Boolean {
        act(apply); render(); return true
    }

    private fun render() {
        val m = Mdm(this)
        val owner = m.isOwner
        val active = RESTRICTION_GROUPS.flatMap { it.second }.count { m.hasRestriction(it.key) }
        scrollPage("Restrictions", back = true, subtitle = "$active active") {
            if (!owner) card { text("⚠️ Device Owner required for these controls", 14f, bold = true, color = Palette.ORANGE) }

            card("Hardware & privacy") {
                switchRow("Disable camera", null, owner && m.dpm.getCameraDisabled(m.admin), owner) { _, on ->
                    change { m.dpm.setCameraDisabled(m.admin, on) }
                }
                switchRow("Block screenshots & screen recording", null, owner && m.dpm.getScreenCaptureDisabled(m.admin), owner) { _, on ->
                    change { m.dpm.setScreenCaptureDisabled(m.admin, on) }
                }
            }

            // ---- Wi-Fi allowlist ----
            card("Wi-Fi networks") {
                if (!Device.wifiAllowSupported()) { text("Needs Android 13 or newer.", 13f); return@card }
                val list = Prefs.wifiAllow(context).sorted()
                val on = Prefs.getFlag(context, Prefs.WIFI_ALLOW_ON)
                text("Only allow the Wi-Fi networks below. Other networks can't be joined.", 13f).alpha = 0.8f
                switchRow("Only allow these networks", if (list.isEmpty()) "Add at least one network first" else "${list.size} allowed", on, owner && (list.isNotEmpty() || on)) { _, v ->
                    val cur = Device.currentSsid(this@RestrictionsActivity)
                    if (v && cur != null && cur !in list) {
                        confirm("Disconnect from $cur?", "\"$cur\" isn't in the list, so the phone will leave it.", "Turn on") {
                            Prefs.setFlag(this@RestrictionsActivity, Prefs.WIFI_ALLOW_ON, true)
                            Device.applyWifiAllow(this@RestrictionsActivity)?.let { toast(it) }; render()
                        }
                        false
                    } else {
                        Prefs.setFlag(this@RestrictionsActivity, Prefs.WIFI_ALLOW_ON, v)
                        Device.applyWifiAllow(this@RestrictionsActivity)?.let { toast(it) }
                        render(); true
                    }
                }
                list.forEach { s ->
                    text("📶  $s", 15f).setOnClickListener {
                        confirm("Remove $s?", "The phone won't be able to join it while the allowlist is on.", "Remove") {
                            Prefs.updateSet(this@RestrictionsActivity, Prefs.K_WIFI_ALLOW) { it - s }
                            if (Prefs.wifiAllow(this@RestrictionsActivity).isEmpty()) Prefs.setFlag(this@RestrictionsActivity, Prefs.WIFI_ALLOW_ON, false)
                            Device.applyWifiAllow(this@RestrictionsActivity); render()
                        }
                    }
                }
                val cur = Device.currentSsid(context)
                if (cur != null && cur !in list) button("Add current network ($cur)", outlined = true) {
                    Prefs.updateSet(this@RestrictionsActivity, Prefs.K_WIFI_ALLOW) { it + cur }
                    Device.applyWifiAllow(this@RestrictionsActivity); render()
                }.isEnabled = owner
                if (cur == null) text("Turn on Location to see the current Wi-Fi name.", 12f).alpha = 0.6f
                button("Add network by name…", outlined = true) {
                    val e = android.widget.EditText(this@RestrictionsActivity).apply { hint = "Wi-Fi name (exact)"; isSingleLine = true }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@RestrictionsActivity).setTitle("Add Wi-Fi network")
                        .setView(padded(e))
                        .setPositiveButton("Add") { _, _ ->
                            val n = e.text.toString().trim()
                            if (n.isNotEmpty()) {
                                Prefs.updateSet(this@RestrictionsActivity, Prefs.K_WIFI_ALLOW) { it + n }
                                Device.applyWifiAllow(this@RestrictionsActivity); render()
                            }
                        }.setNegativeButton("Cancel", null).show()
                }.isEnabled = owner
            }

            // ---- System updates ----
            card("System updates") {
                val mode = runCatching { Device.updateMode(context) }.getOrDefault(0)
                text("Current: ${Device.UPDATE_OPTIONS[mode]}", 15f, bold = true)
                text("Controls when Android/vivo system updates install. Some vivo updates may still ask for confirmation.", 12f).alpha = 0.7f
                button("Change…", outlined = true) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@RestrictionsActivity).setTitle("System updates")
                        .setSingleChoiceItems(Device.UPDATE_OPTIONS.toTypedArray(), mode) { d, i ->
                            d.dismiss(); act { Device.setUpdateMode(this@RestrictionsActivity, i) }; render()
                        }.show()
                }.isEnabled = owner
            }

            RESTRICTION_GROUPS.forEach { (group, items) ->
                card(group) {
                    items.forEach { r ->
                        switchRow(r.title, r.note, m.hasRestriction(r.key), owner) { sw, on ->
                            if (on && r.danger) {
                                confirm("Are you sure?", r.note ?: "", "Turn on") {
                                    act { m.setRestriction(r.key, true); sw.setSilently(true) }
                                }
                                false
                            } else {
                                change { m.setRestriction(r.key, on) }
                            }
                        }
                    }
                }
            }
        }
    }
}
