// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

/** Device-wide restrictions (user restrictions, camera, screenshots). */
class RestrictionsActivity : BaseActivity() {

    override fun onResume() {
        super.onResume()
        if (!isFinishing) render()
    }

    /** Applies a switch change. (The filter guard is only for website filters, not restrictions.) */
    @Suppress("UNUSED_PARAMETER")
    private fun guardOff(what: String, on: Boolean, apply: () -> Unit): Boolean {
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
                    guardOff("turn the camera back on", on) { m.dpm.setCameraDisabled(m.admin, on) }
                }
                switchRow("Block screenshots & screen recording", null, owner && m.dpm.getScreenCaptureDisabled(m.admin), owner) { _, on ->
                    guardOff("allow screenshots", on) { m.dpm.setScreenCaptureDisabled(m.admin, on) }
                }
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
                                guardOff("turn off \"${r.title}\"", on) { m.setRestriction(r.key, on) }
                            }
                        }
                    }
                }
            }
        }
    }
}
