// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import com.google.android.material.card.MaterialCardView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.toast(msg: String?) = Toast.makeText(this, msg ?: "Error", Toast.LENGTH_LONG).show()

/** Runs [block], showing any exception as a toast instead of crashing. */
fun Context.act(block: () -> Unit) {
    try { block() } catch (e: Exception) { toast(e.message ?: e.javaClass.simpleName) }
}

/** Sets content with padding for system bars (target SDK 36 is always edge-to-edge). */
fun Activity.setPage(content: View) {
    val frame = FrameLayout(this)
    frame.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
    ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
        val b = insets.getInsets(types)
        v.setPadding(b.left, b.top, b.right, b.bottom)
        WindowInsetsCompat.CONSUMED
    }
    // When a screen re-renders (e.g. coming back to the dashboard) Android doesn't re-send insets
    // to the new view, so apply the current ones right away and also ask for a fresh dispatch.
    ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(types)?.let { b ->
        frame.setPadding(b.left, b.top, b.right, b.bottom)
    }
    setContentView(frame)
    ViewCompat.requestApplyInsets(frame)
}

fun Context.column() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(24))
}

private const val PAGE_SCROLL_TAG = "devguard_page_scroll"

/**
 * Builds a scrolling page. When a screen re-renders itself (after a switch or button),
 * the previous scroll position is kept so the list doesn't jump back to the top.
 */
fun Activity.scrollPage(
    title: String?,
    back: Boolean = false,
    subtitle: String? = null,
    keepScroll: Boolean = true,
    build: LinearLayout.() -> Unit,
) {
    val oldY = if (keepScroll) window.decorView.findViewWithTag<ScrollView>(PAGE_SCROLL_TAG)?.scrollY ?: 0 else 0
    val col = column()
    if (title != null) col.pageHeader(this, title, subtitle, back)
    col.build()
    val sv = ScrollView(this).apply {
        tag = PAGE_SCROLL_TAG
        // Don't auto-focus (and scroll to) the first text field when the page is rebuilt.
        descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        isFocusableInTouchMode = true
        addView(col)
    }
    setPage(sv)
    if (oldY > 0) {
        sv.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                sv.removeOnLayoutChangeListener(this)
                sv.scrollTo(0, oldY)
            }
        })
    }
}

// ---------------------------------------------------------------------------
// Modern components: page header, rounded cards, square tiles, status card
// ---------------------------------------------------------------------------

object Palette {
    const val BLUE = 0xFF3B82F6.toInt()
    const val GREEN = 0xFF10B981.toInt()
    const val PURPLE = 0xFF8B5CF6.toInt()
    const val ORANGE = 0xFFF59E0B.toInt()
    const val TEAL = 0xFF14B8A6.toInt()
    const val PINK = 0xFFEC4899.toInt()
    const val INDIGO = 0xFF6366F1.toInt()
    const val SLATE = 0xFF64748B.toInt()
    const val RED = 0xFFEF4444.toInt()
}

fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

fun Context.attrColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.roundedBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

fun LinearLayout.pageHeader(a: Activity, title: String, subtitle: String? = null, back: Boolean = false) {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(4), 0, context.dp(12))
    }
    if (back) {
        val b = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(context.attrColor(com.google.android.material.R.attr.colorOnSurface))
            background = context.roundedBg(context.attrColor(com.google.android.material.R.attr.colorSurfaceContainerHigh), 14)
            setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
            setOnClickListener { a.finish() }
            contentDescription = "Back"
        }
        row.addView(b, LinearLayout.LayoutParams(context.dp(44), context.dp(44)).apply { marginEnd = context.dp(14) })
    }
    val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(context).apply {
        text = title
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
    })
    if (subtitle != null) texts.addView(TextView(context).apply { text = subtitle; textSize = 13f; alpha = 0.65f })
    row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    addView(row)
}

/** A rounded surface card; views built inside go into it. */
fun LinearLayout.card(title: String? = null, build: LinearLayout.() -> Unit): MaterialCardView {
    if (title != null) section(title)
    val card = MaterialCardView(context).apply {
        radius = context.dp(22).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(context.attrColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
    }
    val inner = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(12))
    }
    inner.build()
    card.addView(inner)
    addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = context.dp(4); bottomMargin = context.dp(6)
    })
    return card
}

data class Tile(
    val label: String,
    val icon: Int = 0,
    val color: Int = Palette.BLUE,
    val sub: String? = null,
    val enabled: Boolean = true,
    val image: Drawable? = null,
    val onClick: () -> Unit,
)

/** Card that is always as tall as it is wide. */
class SquareCard(c: Context) : MaterialCardView(c) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}

fun Context.tileView(t: Tile): View {
    val card = SquareCard(this).apply {
        radius = dp(24).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(withAlpha(t.color, 0x2A))
        isClickable = true
        isFocusable = true
        isEnabled = t.enabled
        alpha = if (t.enabled) 1f else 0.45f
        setOnClickListener { t.onClick() }
        contentDescription = t.label
    }
    val inner = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    if (t.image != null) {
        inner.addView(ImageView(this).apply { setImageDrawable(t.image) }, LinearLayout.LayoutParams(dp(48), dp(48)))
    } else {
        val box = FrameLayout(this).apply { background = roundedBg(t.color, 16) }
        box.addView(ImageView(this).apply { setImageResource(t.icon); setColorFilter(Color.WHITE) },
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        inner.addView(box, LinearLayout.LayoutParams(dp(48), dp(48)))
    }
    inner.addView(TextView(this).apply {
        text = t.label
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 2
        setPadding(0, dp(8), 0, 0)
    })
    if (t.sub != null) inner.addView(TextView(this).apply {
        text = t.sub
        textSize = 11f
        alpha = 0.7f
        gravity = Gravity.CENTER
        maxLines = 1
    })
    card.addView(inner, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    return card
}

/** Grid of rounded square tiles, [columns] per row. */
fun LinearLayout.tileGrid(tiles: List<Tile>, columns: Int = 3) {
    val c = context
    tiles.chunked(columns).forEach { rowTiles ->
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until columns) {
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(c.dp(5), c.dp(5), c.dp(5), c.dp(5)) }
            row.addView(if (i < rowTiles.size) c.tileView(rowTiles[i]) else View(c), lp)
        }
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            marginStart = -c.dp(5); marginEnd = -c.dp(5)
        })
    }
}

/** Compact rounded tab: icon on the left, label + sub on the right. Selected tabs get a colored border. */
fun Context.tabView(t: Tile, selected: Boolean): View {
    val card = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        strokeWidth = if (selected) dp(2) else 0
        strokeColor = t.color
        setCardBackgroundColor(withAlpha(t.color, if (selected) 0x40 else 0x1A))
        isClickable = true
        isFocusable = true
        setOnClickListener { t.onClick() }
        contentDescription = t.label
    }
    val inner = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    val box = FrameLayout(this).apply { background = roundedBg(t.color, 12) }
    box.addView(ImageView(this).apply { setImageResource(t.icon); setColorFilter(Color.WHITE) },
        FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    inner.addView(box, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(this).apply {
        text = t.label
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    if (t.sub != null) texts.addView(TextView(this).apply {
        text = t.sub
        textSize = 12f
        alpha = 0.75f
        maxLines = 1
    })
    inner.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    card.addView(inner)
    return card
}

/** Grid of compact tabs ([columns] per row); [selected] is the index of the active tab. */
fun LinearLayout.tabGrid(tabs: List<Tile>, selected: Int, columns: Int = 2) {
    val c = context
    tabs.withIndex().chunked(columns).forEach { rowTabs ->
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until columns) {
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(c.dp(5), c.dp(5), c.dp(5), c.dp(5)) }
            val t = rowTabs.getOrNull(i)
            row.addView(if (t != null) c.tabView(t.value, t.index == selected) else View(c), lp)
        }
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            marginStart = -c.dp(5); marginEnd = -c.dp(5)
        })
    }
}

/** Big gradient card with an icon, headline and status pills. */
fun LinearLayout.statusCard(ok: Boolean, headline: String, detail: String, pills: List<String>) {
    val c = context
    val card = MaterialCardView(c).apply { radius = c.dp(28).toFloat(); cardElevation = 0f; strokeWidth = 0 }
    val colors = if (ok) intArrayOf(0xFF2563EB.toInt(), 0xFF7C3AED.toInt()) else intArrayOf(0xFFF59E0B.toInt(), 0xFFEF4444.toInt())
    val inner = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        setPadding(c.dp(20), c.dp(20), c.dp(20), c.dp(18))
    }
    val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val box = FrameLayout(c).apply { background = c.roundedBg(0x33FFFFFF, 16) }
    box.addView(ImageView(c).apply { setImageResource(if (ok) R.drawable.ic_shield else R.drawable.ic_warning); setColorFilter(Color.WHITE) },
        FrameLayout.LayoutParams(c.dp(28), c.dp(28), Gravity.CENTER))
    top.addView(box, LinearLayout.LayoutParams(c.dp(52), c.dp(52)).apply { marginEnd = c.dp(14) })
    val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(c).apply { text = headline; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
    texts.addView(TextView(c).apply { text = detail; textSize = 13f; setTextColor(0xDDFFFFFF.toInt()) })
    top.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    inner.addView(top)
    val pillRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, c.dp(14), 0, 0) }
    pills.forEach { p ->
        pillRow.addView(TextView(c).apply {
            text = p
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = c.roundedBg(0x33FFFFFF, 50)
            setPadding(c.dp(10), c.dp(5), c.dp(10), c.dp(5))
            maxLines = 1
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = c.dp(6) })
    }
    inner.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(pillRow) })
    card.addView(inner)
    addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = c.dp(8) })
}

fun LinearLayout.text(s: CharSequence, size: Float = 14f, bold: Boolean = false, color: Int? = null): TextView {
    val t = TextView(context).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        color?.let { setTextColor(it) }
        setPadding(0, context.dp(4), 0, context.dp(4))
    }
    addView(t)
    return t
}

fun LinearLayout.section(s: String): TextView =
    text(s, 15f, bold = true).apply {
        setPadding(context.dp(4), context.dp(18), 0, context.dp(6))
    }

fun Context.materialButton(label: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
    val b = if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    else MaterialButton(this)
    b.text = label
    b.isAllCaps = false
    b.setOnClickListener { onClick() }
    return b
}

fun LinearLayout.button(label: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
    val b = context.materialButton(label, outlined, onClick)
    addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    return b
}

/**
 * A title/subtitle row with a switch. [onChange] returns true if the change was applied;
 * returning false (or throwing) flips the switch back.
 */
fun LinearLayout.switchRow(
    title: String,
    sub: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (MaterialSwitch, Boolean) -> Boolean,
): MaterialSwitch {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, context.dp(6), 0, context.dp(6))
    }
    val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    texts.text(title, 16f)
    if (!sub.isNullOrBlank()) texts.text(sub, 12f).alpha = 0.7f
    row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    val sw = MaterialSwitch(context)
    sw.isChecked = checked
    sw.isEnabled = enabled
    val listener = object : CompoundButton.OnCheckedChangeListener {
        override fun onCheckedChanged(b: CompoundButton, v: Boolean) {
            val ok = try { onChange(sw, v) } catch (e: Exception) { context.toast(e.message); false }
            if (!ok) { sw.setOnCheckedChangeListener(null); sw.isChecked = !v; sw.setOnCheckedChangeListener(this) }
        }
    }
    sw.setOnCheckedChangeListener(listener)
    sw.tag = listener
    row.addView(sw)
    addView(row)
    return sw
}

fun MaterialSwitch.setSilently(v: Boolean) {
    val l = tag as? CompoundButton.OnCheckedChangeListener
    setOnCheckedChangeListener(null)
    isChecked = v
    setOnCheckedChangeListener(l)
}

fun Context.pinField(hintText: String = "PIN") = EditText(this).apply {
    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    hint = hintText
}

fun Context.padded(v: View) = FrameLayout(this).apply {
    setPadding(dp(24), dp(8), dp(24), 0)
    addView(v)
}

fun Activity.confirm(title: String, msg: String, yes: String = "Continue", onYes: () -> Unit) {
    MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg)
        .setPositiveButton(yes) { _, _ -> onYes() }
        .setNegativeButton("Cancel", null).show()
}

fun Activity.askPin(onOk: () -> Unit) {
    val e = pinField()
    MaterialAlertDialogBuilder(this).setTitle("Admin PIN").setView(padded(e))
        .setPositiveButton("OK") { _, _ ->
            val err = Session.tryUnlock(this, e.text.toString())
            if (err == null) onOk() else toast(err)
        }
        .setNegativeButton("Cancel", null).show()
}

/** Screens that need an unlocked admin session. */
open class BaseActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        if (!Session.valid()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            Session.touch()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        Session.touch()
    }
}
