// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.SecureRandom

/**
 * Filter guard ("self-control lock"). Anything that weakens filtering must pass:
 *  1. Typing puzzle: retype a random 40-character code (paste blocked, a mistake makes a new code)
 *  2. A multiplication question
 *  3. Optional cooling-off wait: after solving, wait N minutes, then repeat the action within 10 minutes.
 * The wait uses the time since boot, so changing the clock doesn't skip it; a reboot restarts it.
 */
object Guard {
    private const val WINDOW_MS = 10 * 60_000L
    val DELAYS = listOf(0, 15, 30, 60) // minutes

    fun enabled(c: Context) = Prefs.getFlag(c, Prefs.GUARD_ON)
    fun delayMin(c: Context) = Prefs.getInt(c, Prefs.GUARD_DELAY, 15)
    private fun delayMs(c: Context) = delayMin(c) * 60_000L

    /** Runs [onPass] directly if the guard is off; otherwise only after the challenge (and wait). */
    fun require(a: Activity, what: String, onPass: () -> Unit) {
        if (!enabled(a)) { onPass(); return }
        val now = SystemClock.elapsedRealtime()
        val start = Prefs.getLong(a, Prefs.GUARD_WAIT_START)
        val delay = delayMs(a)
        val waited = now - start
        // B-03: a waiting request belongs to ONE action. Solving the puzzle for a small action must not
        // unlock a bigger one, so a different action can't use (or replace) the pending request.
        val pending = delay > 0 && start > 0 && waited in 0..(delay + WINDOW_MS)
        val pendingWhat = Prefs.getStr(a, Prefs.GUARD_WAIT_WHAT)
        if (pending && pendingWhat != what) {
            MaterialAlertDialogBuilder(a).setTitle("Another request is waiting")
                .setMessage(
                    "You already asked to ${pendingWhat ?: "change a filter"}. Only that action can be confirmed after its wait.\n\n" +
                        "To do this instead, cancel that request and start again (puzzle and wait).",
                )
                .setPositiveButton("Keep waiting", null)
                .setNegativeButton("Cancel that request") { _, _ -> clear(a) }
                .show()
            return
        }
        when {
            // Wait finished and still inside the confirm window: final confirmation.
            delay > 0 && start > 0 && waited in delay..(delay + WINDOW_MS) -> {
                MaterialAlertDialogBuilder(a).setTitle("Confirm: $what")
                    .setMessage("The cooling-off time is over. Do you still want to do this?")
                    .setPositiveButton("Yes, do it") { _, _ -> clear(a); onPass() }
                    .setNegativeButton("No, keep my filter") { _, _ -> clear(a) }
                    .show()
            }
            // Still waiting.
            delay > 0 && start > 0 && waited in 0 until delay -> {
                val left = (delay - waited) / 1000
                MaterialAlertDialogBuilder(a).setTitle("Cooling-off time")
                    .setMessage("Come back in %d min %02d s and repeat this action to confirm.\n\nUsually the urge passes. Your filter is still on.".format(left / 60, left % 60))
                    .setPositiveButton("OK", null)
                    .setNegativeButton("Cancel request") { _, _ -> clear(a) }
                    .show()
            }
            // No request yet (or it expired, or the phone rebooted): start with the puzzle.
            else -> {
                clear(a)
                puzzle(a, what) {
                    if (delay == 0L) onPass()
                    else {
                        Prefs.setStr(a, Prefs.GUARD_WAIT_WHAT, what) // the only action this wait can confirm
                        Prefs.setLong(a, Prefs.GUARD_WAIT_START, SystemClock.elapsedRealtime())
                        MaterialAlertDialogBuilder(a).setTitle("Puzzle solved")
                            .setMessage("Now wait ${delayMin(a)} minutes. Then repeat the same action within 10 minutes to confirm. After that the request expires.")
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
        }
    }

    private fun clear(c: Context) {
        Prefs.setLong(c, Prefs.GUARD_WAIT_START, 0L)
        Prefs.setStr(c, Prefs.GUARD_WAIT_WHAT, null)
    }

    private val rnd = SecureRandom()
    private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    private fun newCode() = (1..40).map { CHARS[rnd.nextInt(CHARS.length)] }.joinToString("")

    private fun puzzle(a: Activity, what: String, onSolved: () -> Unit) {
        val code = newCode()
        val x = 12 + rnd.nextInt(88)
        val y = 12 + rnd.nextInt(88)
        val col = a.column()
        col.text("To $what, retype this code exactly (case matters, spaces don't):", 13f)
        col.text(code.chunked(5).joinToString(" "), 20f, bold = true, color = Palette.RED).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextIsSelectable(false)
            setPadding(0, a.dp(10), 0, a.dp(10))
        }
        val codeIn = noPasteField(a, "Type the code")
        col.addView(codeIn)
        col.text("And: what is $x × $y?", 14f, bold = true)
        val mathIn = noPasteField(a, "Answer").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        col.addView(mathIn)
        MaterialAlertDialogBuilder(a).setTitle("Filter guard").setView(col).setCancelable(false)
            .setPositiveButton("Submit") { _, _ ->
                val okCode = codeIn.text.toString().replace(" ", "") == code
                val okMath = mathIn.text.toString().trim() == (x * y).toString()
                if (okCode && okMath) onSolved()
                else { a.toast("Not correct. Here's a new puzzle."); puzzle(a, what, onSolved) }
            }
            .setNegativeButton("Keep my filter", null)
            .show()
    }

    /** EditText that refuses paste (menu, keyboard clipboard chips and multi-character inserts). */
    private fun noPasteField(a: Activity, hintText: String) = object : EditText(a) {
        override fun onTextContextMenuItem(id: Int): Boolean =
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) false else super.onTextContextMenuItem(id)
    }.apply {
        hint = hintText
        isSingleLine = true
        isLongClickable = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        customSelectionActionModeCallback = NoMenu
        customInsertionActionModeCallback = NoMenu
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (count > 2) { // more than a couple of characters at once = paste
                    post { setText(""); a.toast("Type it yourself. Pasting isn't allowed.") }
                }
            }
        })
    }

    private object NoMenu : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
        override fun onDestroyActionMode(mode: ActionMode?) {}
    }
}
