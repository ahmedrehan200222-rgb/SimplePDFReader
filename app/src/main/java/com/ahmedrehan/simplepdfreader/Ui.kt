package com.ahmedrehan.simplepdfreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import kotlin.math.min

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


/** أيقونات الأدوات. بتترسم بالكود، فمش بتعتمد على خطوط أو إيموجي الجهاز. */
enum class Icon { PEN, ERASER, UNDO, REDO, TEXT, PAGES, SEARCH, TAG, MOON, CONTRAST, MORE, HOME, FLIP_LTR, FLIP_RTL, FLIP_DOWN, ROTATE }

class IconView(ctx: Context, var icon: Icon, private val label: String) : View(ctx) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** لما تكون الأداة شغالة (مثلاً وضع التمييز) بتتلوّن بالأسود. */
    var active: Boolean = false
        set(v) {
            field = v
            updateBackground()
            invalidate()
        }

    init {
        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        fill.style = Paint.Style.FILL
        isClickable = true
        contentDescription = label
        minimumHeight = ctx.dp(48)
        updateBackground()
    }

    private fun updateBackground() {
        val c = context
        background = if (active) {
            c.pressable(c.box(Theme.INK, Theme.INK, 1, 10), c.box(0xFF4A4A4A.toInt(), Theme.INK, 1, 10))
        } else {
            c.pressable(c.box(Theme.SURFACE, Theme.INK, 1, 10), c.box(Theme.PRESSED, Theme.INK, 1, 10))
        }
    }

    fun setOnClickAndHint(action: () -> Unit, hint: (String) -> Unit) {
        setOnClickListener { action() }
        setOnLongClickListener {
            hint(label)
            true
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = min(width, height) / 24f * 0.62f
        val col = if (active) Color.WHITE else Theme.INK
        stroke.color = col
        fill.color = col
        stroke.strokeWidth = 2f
        c.save()
        c.translate(width / 2f, height / 2f)
        c.scale(s, s)
        c.translate(-12f, -12f)
        when (icon) {
            Icon.PEN -> {
                c.save()
                c.rotate(45f, 12f, 12f)
                c.drawRoundRect(RectF(9f, 2.5f, 15f, 14f), 1.5f, 1.5f, stroke)
                val tip = Path()
                tip.moveTo(9f, 14f)
                tip.lineTo(15f, 14f)
                tip.lineTo(12f, 19.5f)
                tip.close()
                c.drawPath(tip, stroke)
                c.restore()
                c.drawLine(3f, 22f, 10f, 22f, stroke)
            }
            Icon.ERASER -> {
                c.save()
                c.rotate(-35f, 12f, 12f)
                c.drawRoundRect(RectF(4f, 8f, 20f, 16f), 2f, 2f, stroke)
                c.drawLine(11f, 8f, 11f, 16f, stroke)
                c.restore()
                c.drawLine(4f, 21f, 20f, 21f, stroke)
            }
            Icon.UNDO, Icon.REDO -> {
                c.save()
                if (icon == Icon.REDO) c.scale(-1f, 1f, 12f, 12f)
                val p = Path()
                p.moveTo(18f, 18f)
                p.cubicTo(18f, 12f, 14f, 8.5f, 7f, 8.5f)
                c.drawPath(p, stroke)
                c.drawLine(7f, 8.5f, 11.5f, 4f, stroke)
                c.drawLine(7f, 8.5f, 11.5f, 13f, stroke)
                c.restore()
            }
            Icon.TEXT -> {
                c.drawLine(4f, 6f, 16f, 6f, stroke)
                c.drawLine(10f, 6f, 10f, 19f, stroke)
                c.drawLine(15f, 16f, 22f, 16f, stroke)
                c.drawLine(18.5f, 12.5f, 18.5f, 19.5f, stroke)
            }
            Icon.PAGES -> {
                c.drawRoundRect(RectF(4f, 4f, 10.5f, 10.5f), 1.2f, 1.2f, stroke)
                c.drawRoundRect(RectF(13.5f, 4f, 20f, 10.5f), 1.2f, 1.2f, stroke)
                c.drawRoundRect(RectF(4f, 13.5f, 10.5f, 20f), 1.2f, 1.2f, stroke)
                c.drawRoundRect(RectF(13.5f, 13.5f, 20f, 20f), 1.2f, 1.2f, stroke)
            }
            Icon.SEARCH -> {
                c.drawCircle(10f, 10f, 6f, stroke)
                c.drawLine(14.5f, 14.5f, 20.5f, 20.5f, stroke)
            }
            Icon.TAG -> {
                c.drawRoundRect(RectF(3.5f, 4.5f, 20.5f, 16.5f), 2.5f, 2.5f, stroke)
                val tail = Path()
                tail.moveTo(8f, 16.5f)
                tail.lineTo(8f, 21f)
                tail.lineTo(12.5f, 16.5f)
                c.drawPath(tail, stroke)
                c.drawLine(7.5f, 9f, 16.5f, 9f, stroke)
                c.drawLine(7.5f, 12.5f, 13.5f, 12.5f, stroke)
            }
            Icon.MOON -> {
                val a = Path()
                a.addCircle(12f, 12f, 8f, Path.Direction.CW)
                val b = Path()
                b.addCircle(16.5f, 8.5f, 7f, Path.Direction.CW)
                a.op(b, Path.Op.DIFFERENCE)
                c.drawPath(a, fill)
            }
            Icon.CONTRAST -> {
                c.drawCircle(12f, 12f, 8f, stroke)
                val half = Path()
                half.arcTo(RectF(4f, 4f, 20f, 20f), 90f, 180f)
                half.close()
                c.drawPath(half, fill)
            }
            Icon.MORE -> {
                c.drawCircle(5.5f, 12f, 1.9f, fill)
                c.drawCircle(12f, 12f, 1.9f, fill)
                c.drawCircle(18.5f, 12f, 1.9f, fill)
            }
            Icon.HOME -> {
                val roof = Path()
                roof.moveTo(3.5f, 11.5f)
                roof.lineTo(12f, 4.5f)
                roof.lineTo(20.5f, 11.5f)
                c.drawPath(roof, stroke)
                c.drawRect(RectF(6f, 10.5f, 18f, 20f), stroke)
                val door = Path()
                door.moveTo(10.5f, 20f)
                door.lineTo(10.5f, 14.5f)
                door.lineTo(13.5f, 14.5f)
                door.lineTo(13.5f, 20f)
                c.drawPath(door, stroke)
            }
            Icon.ROTATE -> {
                c.drawRoundRect(RectF(9.5f, 8.5f, 14.5f, 15.5f), 1f, 1f, stroke)
                val arc = Path()
                arc.arcTo(RectF(4.5f, 4.5f, 19.5f, 19.5f), -40f, 220f)
                c.drawPath(arc, stroke)
                c.drawLine(4.5f, 12f, 2f, 15.5f, stroke)
                c.drawLine(4.5f, 12f, 8f, 15f, stroke)
            }
            Icon.FLIP_LTR, Icon.FLIP_RTL -> {
                c.save()
                if (icon == Icon.FLIP_RTL) c.scale(-1f, 1f, 12f, 12f)
                c.drawLine(4f, 12f, 20f, 12f, stroke)
                c.drawLine(14f, 6f, 20f, 12f, stroke)
                c.drawLine(14f, 18f, 20f, 12f, stroke)
                c.restore()
            }
            Icon.FLIP_DOWN -> {
                c.drawLine(12f, 3f, 12f, 21f, stroke)
                c.drawLine(6f, 15f, 12f, 21f, stroke)
                c.drawLine(18f, 15f, 12f, 21f, stroke)
                c.drawLine(5f, 7f, 19f, 7f, stroke)
            }
        }
        c.restore()
    }
}

fun Context.iconButton(icon: Icon, label: String, hint: (String) -> Unit, onClick: () -> Unit): IconView {
    val v = IconView(this, icon, label)
    v.setOnClickAndHint(onClick, hint)
    return v
}
