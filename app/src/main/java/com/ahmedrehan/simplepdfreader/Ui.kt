package com.ahmedrehan.simplepdfreader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** ألوان هادية عالية التباين زي شاشة الحبر الإلكتروني. */
object Theme {
    val PAPER: Int = 0xFFF6F4EE.toInt()
    val SURFACE: Int = 0xFFFFFFFF.toInt()
    val INK: Int = 0xFF141414.toInt()
    val MUTED: Int = 0xFF666666.toInt()
    val LINE: Int = 0xFFC9C5BA.toInt()
    val PRESSED: Int = 0xFFE4E0D6.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

fun Context.box(fill: Int, stroke: Int = Color.TRANSPARENT, strokeDp: Int = 0, radiusDp: Int = 8): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(fill)
    g.cornerRadius = dp(radiusDp).toFloat()
    if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke)
    return g
}

fun Context.pressable(normal: GradientDrawable, pressed: GradientDrawable): StateListDrawable {
    val s = StateListDrawable()
    s.addState(intArrayOf(android.R.attr.state_pressed), pressed)
    s.addState(intArrayOf(), normal)
    return s
}

fun Context.styleButton(tv: TextView, filled: Boolean) {
    tv.setTextColor(if (filled) Color.WHITE else Theme.INK)
    val normal = box(if (filled) Theme.INK else Theme.SURFACE, Theme.INK, 1, 10)
    val pressed = box(if (filled) 0xFF4A4A4A.toInt() else Theme.PRESSED, Theme.INK, 1, 10)
    tv.background = pressable(normal, pressed)
}

fun Context.uiButton(label: String, filled: Boolean = false, sizeSp: Float = 15f, onClick: () -> Unit): TextView {
    val tv = TextView(this)
    tv.text = label
    tv.textSize = sizeSp
    tv.gravity = Gravity.CENTER
    tv.minHeight = dp(48)
    tv.minimumWidth = dp(48)
    tv.isClickable = true
    styleButton(tv, filled)
    tv.setPadding(dp(10), dp(8), dp(10), dp(8))
    tv.setOnClickListener { onClick() }
    return tv
}

fun Context.styleChip(tv: TextView, color: Int, selected: Boolean) {
    tv.setTextColor(if (selected) Color.WHITE else color)
    tv.background = box(if (selected) color else Theme.SURFACE, color, 2, 18)
}

fun Context.uiChip(label: String, color: Int, selected: Boolean, onClick: () -> Unit): TextView {
    val tv = TextView(this)
    tv.text = label
    tv.textSize = 14f
    tv.gravity = Gravity.CENTER
    tv.minHeight = dp(40)
    tv.isClickable = true
    styleChip(tv, color, selected)
    tv.setPadding(dp(14), dp(6), dp(14), dp(6))
    tv.setOnClickListener { onClick() }
    return tv
}

fun Context.colorDot(color: Int, selected: Boolean): View {
    val v = View(this)
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setColor(color)
    g.setStroke(dp(if (selected) 4 else 1), if (selected) Theme.INK else Theme.LINE)
    v.background = g
    return v
}

fun Context.divider(): View {
    val v = View(this)
    v.setBackgroundColor(Theme.LINE)
    return v
}

/** شارة صغيرة ملونة باسم نوع الصفحة. */
fun Context.tagBadge(tag: PageTag): TextView {
    val tv = TextView(this)
    tv.text = tag.short
    tv.textSize = 11f
    tv.setTextColor(Color.WHITE)
    tv.typeface = Typeface.DEFAULT_BOLD
    tv.background = box(tag.color, tag.color, 0, 4)
    tv.setPadding(dp(5), dp(1), dp(5), dp(1))
    return tv
}

/** سطر ملوّن فيه وسوم الصفحة واسمها، بيتعرض تحت رقم الصفحة. */
fun PageInfo.describe(): SpannableStringBuilder {
    val sb = SpannableStringBuilder()
    for (t in tags.sortedBy { it.ordinal }) {
        val start = sb.length
        sb.append(t.short)
        sb.setSpan(ForegroundColorSpan(t.color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ")
    }
    if (title.isNotBlank()) sb.append(title)
    return sb
}
