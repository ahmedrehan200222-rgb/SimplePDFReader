package com.ahmedrehan.simplepdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** الواجهة اللي بتكلّم بيها شاشة القراءة النشاط الرئيسي. */
interface ReaderHost {
    val store: DocStore?
    val currentPage: Int
    val mode: Int            // 0 تصفح، 1 تمييز، 2 ممحاة
    val highlightStyle: Int  // 0 تمييز ذكي للسطور، 1 فرشاة
    val markColor: Int
    val markOpacity: Int
    val fitWidth: Boolean
    val night: Boolean
    val grayscale: Boolean
    val rtlBook: Boolean

    fun toast(msg: String)
    fun rememberUndo()
    fun marksChanged()
    fun boxesChanged()
    fun turnPage(delta: Int)
    fun tapCenter()
    fun editMark(m: Mark)
    fun editBox(b: TextBox)
}

/**
 * بيعرض صورة الصفحة ويتحكم في التكبير والتحريك واللمس.
 * التمييز الذكي مش OCR: بيحسب البكسلات الغامقة عشان يعرف حدود كل سطر، فالتمييز بيطلع مستقيم على السطر.
 */
class ReaderView(ctx: Context, private val host: ReaderHost) : View(ctx) {
    private var bitmap: Bitmap? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var multiTouch = false
    private var selecting = false
    private var activeRect: RectF? = null
    private var brushDrawing = false
    private var brushLast: Pair<Float, Float>? = null
    private val pendingBrush = mutableListOf<Mark>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val swipeDistance = ctx.dp(40).toFloat()

    private val detector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val old = zoom
            val next = (zoom * d.scaleFactor).coerceIn(1f, 4f)
            val factor = next / old
            panX = d.focusX - (d.focusX - panX) * factor
            panY = d.focusY - (d.focusY - panY) * factor
            zoom = next
            clampPan()
            invalidate()
            return true
        }
    })

    fun setPage(b: Bitmap) {
        bitmap?.recycle()
        bitmap = b
        zoom = 1f
        panX = 0f
        panY = 0f
        activeRect = null
        pendingBrush.clear()
        clampPan()
        invalidate()
    }

    fun clearPage() {
        bitmap?.recycle()
        bitmap = null
        invalidate()
    }

    fun resetView() {
        zoom = 1f
        panX = 0f
        panY = 0f
        clampPan()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }

    // ---------- الرسم ----------
    private fun imageRect(): RectF {
        val b = bitmap ?: return RectF()
        if (width == 0 || height == 0) return RectF()
        val fit = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val s = if (host.fitWidth) width.toFloat() / b.width else fit
        val w = b.width * s
        val h = b.height * s
        val left = (width - w) / 2f
        val top = if (h >= height) 0f else (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    private fun scrollable(): Boolean {
        val r = imageRect()
        return r.width() * zoom > width + 1f || r.height() * zoom > height + 1f
    }

    private fun clampPan() {
        val r = imageRect()
        if (r.width() <= 0f) {
            panX = 0f
            panY = 0f
            return
        }
        val sw = r.width() * zoom
        val sh = r.height() * zoom
        panX = if (sw <= width) {
            (width - sw) / 2f - r.left * zoom
        } else {
            panX.coerceIn(width - r.right * zoom, -r.left * zoom)
        }
        panY = if (sh <= height) {
            (height - sh) / 2f - r.top * zoom
        } else {
            panY.coerceIn(height - r.bottom * zoom, -r.top * zoom)
        }
    }

    private fun drawMark(c: Canvas, m: Mark, r: RectF) {
        paint.color = m.color
        paint.alpha = ((100 - m.alpha) * 255 / 100).coerceIn(0, 255)
        if (m.brush) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = max(7f, r.width() * 0.009f)
            c.drawLine(
                r.left + m.left * r.width(), r.top + m.top * r.height(),
                r.left + m.right * r.width(), r.top + m.bottom * r.height(), paint
            )
            paint.style = Paint.Style.FILL
        } else {
            paint.style = Paint.Style.FILL
            c.drawRect(
                r.left + m.left * r.width(), r.top + m.top * r.height(),
                r.left + m.right * r.width(), r.top + m.bottom * r.height(), paint
            )
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val b = bitmap
        if (b == null) {
            c.drawColor(if (host.night) Color.DKGRAY else Theme.PAPER)
            return
        }
        val st = host.store
        val r = imageRect()
        val page = host.currentPage
        c.drawColor(if (host.night) Color.BLACK else Theme.PAPER)
        val save = c.save()
        c.translate(panX, panY)
        c.scale(zoom, zoom)
        paint.colorFilter = when {
            host.night -> ColorMatrixColorFilter(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            host.grayscale -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            else -> null
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        c.drawBitmap(b, null, r, paint)
        paint.colorFilter = null
        if (st != null) {
            for (m in st.marks) if (m.page == page) drawMark(c, m, r)
            for (tb in st.boxes) {
                if (tb.page != page) continue
                paint.style = Paint.Style.FILL
                paint.color = tb.color
                paint.alpha = 255
                paint.textSize = tb.size
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                c.drawText(tb.text, r.left + tb.x * r.width(), r.top + tb.y * r.height(), paint)
            }
        }
        for (m in pendingBrush) drawMark(c, m, r)
        c.restoreToCount(save)
        activeRect?.let {
            paint.style = Paint.Style.FILL
            paint.color = host.markColor
            paint.alpha = ((100 - host.markOpacity) * 255 / 100).coerceIn(0, 255)
            c.drawRect(it, paint)
        }
        paint.alpha = 255
    }

    private fun toImage(x: Float, y: Float): Pair<Float, Float> {
        val r = imageRect()
        val xx = (x - panX) / zoom
        val yy = (y - panY) / zoom
        return Pair((xx - r.left) / r.width().coerceAtLeast(1f), (yy - r.top) / r.height().coerceAtLeast(1f))
    }

    // ---------- اللمس ----------
    private fun midX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun midY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                selecting = false
                multiTouch = false
                activeRect = null
                brushDrawing = host.mode == 1 && host.highlightStyle == 1 && zoom == 1f
                brushLast = if (brushDrawing) toImage(e.x, e.y) else null
                pendingBrush.clear()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                selecting = false
                brushDrawing = false
                activeRect = null
                pendingBrush.clear()
                multiTouch = true
                lastMidX = midX(e)
                lastMidY = midY(e)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val mx = midX(e)
                    val my = midY(e)
                    panX += mx - lastMidX
                    panY += my - lastMidY
                    lastMidX = mx
                    lastMidY = my
                    clampPan()
                    invalidate()
                    return true
                }
                if (multiTouch) {
                    lastX = e.x
                    lastY = e.y
                    return true
                }
                if (brushDrawing) {
                    val now = toImage(e.x, e.y)
                    val prev = brushLast
                    if (prev != null && abs(now.first - prev.first) + abs(now.second - prev.second) > 0.004f) {
                        pendingBrush.add(
                            Mark(host.currentPage, prev.first, prev.second, now.first, now.second, host.markColor, host.markOpacity, true)
                        )
                        brushLast = now
                    }
                    invalidate()
                    return true
                }
                val smart = host.mode == 1 && host.highlightStyle == 0 && zoom == 1f
                if (smart && (selecting || hypot(e.x - downX, e.y - downY) > slop)) {
                    selecting = true
                    activeRect = RectF(min(downX, e.x), min(downY, e.y), max(downX, e.x), max(downY, e.y))
                    invalidate()
                    return true
                }
                if ((host.mode != 1 || zoom > 1f) && scrollable()) {
                    panX += e.x - lastX
                    panY += e.y - lastY
                    clampPan()
                    invalidate()
                }
                lastX = e.x
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (multiTouch) {
                    multiTouch = false
                    activeRect = null
                    brushDrawing = false
                    pendingBrush.clear()
                    invalidate()
                    return true
                }
                if (brushDrawing) {
                    finishBrush(e.x, e.y)
                    brushDrawing = false
                    brushLast = null
                    invalidate()
                    return true
                }
                if (selecting) {
                    val rect = activeRect
                    activeRect = null
                    selecting = false
                    if (rect != null && rect.width() > 8f && rect.height() > 5f) addSmartMarks(rect)
                    invalidate()
                    return true
                }
                val dx = e.x - downX
                val dy = e.y - downY
                if (host.mode == 0 && zoom == 1f && abs(dx) > swipeDistance && abs(dx) > abs(dy) * 1.25f) {
                    val forward = dx < 0f
                    host.turnPage(if (forward != host.rtlBook) 1 else -1)
                    return true
                }
                if (hypot(dx, dy) > slop) return true
                handleTap(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeRect = null
                brushDrawing = false
                brushLast = null
                selecting = false
                multiTouch = false
                pendingBrush.clear()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val st = host.store ?: return
        val p = toImage(x, y)
        val page = host.currentPage
        val found = st.marks.lastOrNull {
            it.page == page && p.first >= it.left && p.first <= it.right && p.second >= it.top && p.second <= it.bottom
        }
        val tb = st.boxes.lastOrNull {
            it.page == page && p.first >= it.x - 0.03f && p.first <= it.x + 0.8f && p.second >= it.y - 0.08f && p.second <= it.y + 0.08f
        }
        when (host.mode) {
            1 -> {
                if (found == null) addLineAt(x, y)
            }
            2 -> {
                if (found != null) {
                    host.rememberUndo()
                    st.marks.remove(found)
                    host.marksChanged()
                    host.toast("تم مسح التمييز")
                } else if (tb != null) {
                    st.boxes.remove(tb)
                    host.boxesChanged()
                    host.toast("تم مسح مربع النص")
                } else {
                    host.toast("مفيش عنصر تحت الممحاة")
                }
            }
            else -> {
                if (found != null) {
                    host.editMark(found)
                } else if (tb != null) {
                    host.editBox(tb)
                } else if (zoom == 1f && x < width * 0.2f) {
                    host.turnPage(if (host.rtlBook) 1 else -1)
                } else if (zoom == 1f && x > width * 0.8f) {
                    host.turnPage(if (host.rtlBook) -1 else 1)
                } else {
                    host.tapCenter()
                }
            }
        }
    }

    private fun finishBrush(x: Float, y: Float) {
        val st = host.store ?: return
        val end = toImage(x, y)
        val prev = brushLast
        if (prev != null && abs(end.first - prev.first) + abs(end.second - prev.second) > 0.001f) {
            pendingBrush.add(
                Mark(host.currentPage, prev.first, prev.second, end.first, end.second, host.markColor, host.markOpacity, true)
            )
        }
        if (pendingBrush.isNotEmpty()) {
            host.rememberUndo()
            st.marks.addAll(pendingBrush)
            host.marksChanged()
            host.toast("تم حفظ الفرشاة")
        }
        pendingBrush.clear()
    }

    private fun addLineAt(x: Float, y: Float) {
        val b = bitmap ?: return
        val p = toImage(x, y)
        if (p.first !in 0f..1f || p.second !in 0f..1f) return
        val r = imageRect()
        val imageY = (p.second * b.height).toInt().coerceIn(0, b.height - 1)
        val radius = max(5, (b.height * 0.012f).toInt())
        val top = (imageY - radius).coerceAtLeast(0)
        val bottom = (imageY + radius).coerceAtMost(b.height)
        // مستطيل بإحداثيات الشاشة (قبل التكبير) يغطي عرض الصفحة كلها حوالين النقطة اللي لمستها
        val sy1 = panY + (r.top + top.toFloat() / b.height * r.height()) * zoom
        val sy2 = panY + (r.top + bottom.toFloat() / b.height * r.height()) * zoom
        val sx1 = panX + r.left * zoom
        val sx2 = panX + r.right * zoom
        addSmartMarks(RectF(sx1, sy1, sx2, sy2))
    }

    /**
     * بيدوّر على صفوف الكلام جوه المستطيل اللي اتحدد من البكسلات الغامقة (من غير OCR)،
     * وبيرسم تمييز مستقيم على كل سطر بحدوده الحقيقية.
     */
    private fun addSmartMarks(r: RectF) {
        val b = bitmap ?: return
        val st = host.store ?: return
        val bw = b.width
        val bh = b.height
        val a = toImage(r.left, r.top)
        val z = toImage(r.right, r.bottom)
        val sx1 = (min(a.first, z.first) * bw).toInt().coerceIn(0, bw - 1)
        val sx2 = (max(a.first, z.first) * bw).toInt().coerceIn(sx1 + 1, bw)
        var sy1 = (min(a.second, z.second) * bh).toInt().coerceIn(0, bh - 1)
        var sy2 = (max(a.second, z.second) * bh).toInt().coerceIn(sy1 + 1, bh)

        val px = IntArray(bw * bh)
        b.getPixels(px, 0, bw, 0, 0, bw, bh)
        fun dark(x: Int, y: Int): Boolean {
            val p = px[y * bw + x]
            val lum = (((p shr 16) and 255) * 30 + ((p shr 8) and 255) * 59 + (p and 255) * 11) / 100
            return lum < 178
        }

        val rows = ArrayList<Int>()
        val rowThreshold = max(1, ((sx2 - sx1) * 0.0015f).toInt())
        for (y in sy1 until sy2) {
            var count = 0
            var x = sx1
            while (x < sx2) {
                if (dark(x, y)) count++
                x += 2
            }
            if (count >= rowThreshold) rows.add(y)
        }
        // لو التحديد صغير، بنروح لأقرب سطر مطبوع
        if (rows.isEmpty()) {
            val cy = ((sy1 + sy2) / 2).coerceIn(0, bh - 1)
            var best = -1
            var bestCount = 0
            for (y in max(0, cy - 28)..min(bh - 1, cy + 28)) {
                var count = 0
                var x = sx1
                while (x < sx2) {
                    if (dark(x, y)) count++
                    x += 2
                }
                if (count > bestCount) {
                    bestCount = count
                    best = y
                }
            }
            if (best >= 0 && bestCount > 0) {
                sy1 = max(0, best - 12)
                sy2 = min(bh, best + 13)
                for (y in sy1 until sy2) {
                    var count = 0
                    var x = sx1
                    while (x < sx2) {
                        if (dark(x, y)) count++
                        x += 2
                    }
                    if (count >= rowThreshold) rows.add(y)
                }
            }
        }
        if (rows.isEmpty()) {
            host.toast("قرّب التحديد من الكلام وحاول تاني")
            return
        }
        val lines = mutableListOf<Pair<Int, Int>>()
        var lineStart = rows.first()
        var previous = rows.first()
        for (y in rows.drop(1)) {
            if (y - previous > 5) {
                lines.add(Pair(lineStart, previous + 1))
                lineStart = y
            }
            previous = y
        }
        lines.add(Pair(lineStart, previous + 1))

        val detected = mutableListOf<Mark>()
        for ((top0, bottom0) in lines) {
            val top = max(0, top0 - 2)
            val bottom = min(bh, bottom0 + 3)
            val columnThreshold = max(1, ((bottom - top) * 0.035f).toInt())
            var first = -1
            var last = -1
            for (x in 0 until bw) {
                var count = 0
                var y = top
                while (y < bottom) {
                    if (dark(x, y)) count++
                    y += 2
                }
                if (count >= columnThreshold) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            val lineLeft = max(0, first - 2)
            val lineRight = min(bw, last + 3)
            if (lineRight > sx1 && lineLeft < sx2) {
                val l = lineLeft.toFloat() / bw
                val rr = lineRight.toFloat() / bw
                val t = top.toFloat() / bh
                val bb = bottom.toFloat() / bh
                if (rr > l && bb > t) {
                    detected.add(Mark(host.currentPage, l, t, rr, bb, host.markColor, host.markOpacity))
                }
            }
        }
        if (detected.isEmpty()) {
            host.toast("مش قادر أحدد سطر واضح في المنطقة دي")
            return
        }
        host.rememberUndo()
        st.marks.addAll(detected)
        host.marksChanged()
        host.toast("تم تمييز " + detected.size + " جزء من النص")
    }
}
