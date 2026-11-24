// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Admin dialogs shared by the lock screen, dashboard and Settings. */

/** Shows a newly created recovery code once. */
fun Activity.showRecoveryCodeDialog(code: String, then: () -> Unit) {
    val col = column()
    col.text("Write this down and keep it somewhere safe (not on this phone). It resets a forgotten PIN (and the authenticator lock) on the phone itself.", 14f)
    col.text(code, 26f, bold = true, color = Palette.BLUE).apply {
        gravity = Gravity.CENTER
        setTextIsSelectable(true)
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, dp(16), 0, dp(16))
    }
    col.text("A new code replaces the old one each time the PIN is set.", 12f).alpha = 0.6f
    MaterialAlertDialogBuilder(this).setTitle("Your recovery code").setView(col).setCancelable(false)
        .setNeutralButton("Copy") { _, _ -> Bloat.copy(this, code); then() }
        .setPositiveButton("I've saved it") { _, _ -> then() }
        .show()
}

fun Activity.pcResetHelpDialog() {
    val col = column()
    col.text("Connect the phone to your PC (USB debugging on) and run:", 14f)
    col.text(PinResetReceiver.ADB_COMMAND, 12f, bold = true).setTextIsSelectable(true)
    col.text("It clears the PIN and the authenticator lock. Then open DeviceGuard again and create a new PIN. All protections stay on.", 13f).alpha = 0.7f
    MaterialAlertDialogBuilder(this).setTitle("Reset PIN from the PC").setView(col)
        .setPositiveButton("OK", null).show()
}

fun Activity.changePinDialog() {
    val col = column()
    val p1 = pinField("New PIN (${Prefs.PIN_MIN}–${Prefs.PIN_MAX} digits)")
    val p2 = pinField("Confirm PIN")
    col.addView(p1); col.addView(p2)
    MaterialAlertDialogBuilder(this).setTitle("Change admin PIN").setView(col)
        .setPositiveButton("Save") { _, _ ->
            val a = p1.text.toString()
            val problem = Prefs.pinProblem(a, p2.text.toString())
            if (problem != null) toast(problem) else { Prefs.setPin(this, a); toast("PIN changed") }
        }
        .setNegativeButton("Cancel", null).show()
}

/**
 * The one admin unlock flow (D-04): PIN with lockout, then the two-factor code when it's on, then [onOk].
 * Shows errors itself. Returns false if the PIN was wrong (so the caller can clear the field).
 */
fun Activity.unlockWithPin(pin: String, onOk: () -> Unit): Boolean {
    val err = Session.tryUnlock(this, pin)
    if (err != null) { toast(err); return false }
    if (Totp.enabled(this)) {
        Session.lock() // the PIN alone isn't enough
        totpPrompt { Session.unlockNow(); onOk() }
    } else onOk()
    return true
}

/** Second unlock step when two-factor is on: asks for the 6-digit authenticator code. */
fun Activity.totpPrompt(onOk: () -> Unit) {
    val e = EditText(this).apply {
        hint = "6-digit code"
        inputType = InputType.TYPE_CLASS_NUMBER
        textSize = 22f
        gravity = Gravity.CENTER
    }
    MaterialAlertDialogBuilder(this).setTitle("Authenticator code")
        .setMessage("Open Zoho OneAuth / Google Authenticator and enter the DeviceGuard code.")
        .setView(padded(e))
        .setPositiveButton("Unlock") { _, _ ->
            val err = Session.tryTotp(this, e.text.toString()) // same lockout as the PIN
            if (err == null) onOk() else toast(err)
        }
        .setNegativeButton("Cancel", null).show()
}

/** Enables two-factor: shows the QR to scan, then checks one code before turning it on. */
fun Activity.setupTotpDialog(then: () -> Unit) {
    val secret = Totp.newSecret()
    val col = column()
    col.text("1. In Zoho OneAuth or Google Authenticator, add an account by scanning this QR code:", 13f)
    col.addView(ImageView(this).apply { setImageBitmap(Qr.bitmap(Totp.uri(secret))) },
        LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    col.text("Or enter this key manually:", 12f).alpha = 0.7f
    col.text(secret.chunked(4).joinToString(" "), 14f, bold = true).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
    col.text("2. Enter the 6-digit code it shows:", 13f)
    val e = EditText(this).apply { hint = "123456"; inputType = InputType.TYPE_CLASS_NUMBER }
    col.addView(e)
    MaterialAlertDialogBuilder(this).setTitle("Two-factor unlock")
        .setView(android.widget.ScrollView(this).apply { addView(col) })
        .setPositiveButton("Turn on") { _, _ ->
            if (Totp.verify(secret, e.text.toString())) { Totp.enable(this, secret); toast("Two-factor unlock is on"); then() }
            else toast("That code didn't match. Try again.")
        }
        .setNegativeButton("Cancel", null).show()
}
