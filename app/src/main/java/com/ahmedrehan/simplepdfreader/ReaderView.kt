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
import kotlin.math.sqrt

/** الواجهة اللي بتكلّم بيها شاشة القراءة النشاط الرئيسي. */
interface ReaderHost {
    val store: DocStore?
    val currentPage: Int
    val mode: Int            // 0 تصفح، 1 تمييز، 2 ممحاة
    val highlightStyle: Int  // 0 تمييز الجزء المحدد، 1 فرشاة مستقيمة
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

    /** بيرسم جزء من الصفحة (بنسب من 0 لـ 1) بدقة عالية في خيط خلفي. */
    fun renderRegion(
        page: Int, u0: Float, v0: Float, u1: Float, v1: Float, w: Int, h: Int,
        stale: () -> Boolean, done: (Bitmap) -> Unit
    )
}

/**
 * بيعرض صورة الصفحة ويتحكم في التكبير والتحريك واللمس.
 * التمييز مش OCR: بيحسب البكسلات الغامقة عشان يعرف حدود كل سطر وكل كلمة،
 * فالتمييز بيطلع مستقيم على السطر، وبيغطي الجزء اللي حددته بس.
 * وعند التكبير بيرسم الجزء الظاهر من الـ PDF بدقة أعلى عشان الصورة ماتتبكسلش.
 */
class ReaderView(ctx: Context, private val host: ReaderHost) : View(ctx) {
    private var bitmap: Bitmap? = null
    private var pix: IntArray? = null
    private var rowCache: IntArray? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastCalcX = -100f
    private var lastCalcY = -100f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var multiTouch = false
    private var selecting = false
    private var previewMarks: List<Mark> = emptyList()
    private var brushDrawing = false
    private var brushStart: Pair<Float, Float>? = null
    private var brushPreview: Mark? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val swipeDistance = ctx.dp(40).toFloat()

    // نسخة عالية الدقة من الجزء الظاهر وقت التكبير
    private var hi: Bitmap? = null
    private var hiRect = RectF()
    private var hiZoom = 1f
    private var hiPanX = 0f
    private var hiPanY = 0f
    private var hiPage = -1
    private var hiToken = 0
    private val hiRun = Runnable { requestHiRes() }

    private val detector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val old = zoom
            val next = (zoom * d.scaleFactor).coerceIn(1f, 5f)
            val factor = next / old
            panX = d.focusX - (d.focusX - panX) * factor
            panY = d.focusY - (d.focusY - panY) * factor
            zoom = next
            dropHi()
            clampPan()
            invalidate()
            return true
        }
    })

    fun setPage(b: Bitmap) {
        bitmap?.recycle()
        bitmap = b
        pix = null
        rowCache = null
        zoom = 1f
        panX = 0f
        panY = 0f
        previewMarks = emptyList()
        brushPreview = null
        dropHi()
        clampPan()
        invalidate()
    }

    fun clearPage() {
        dropHi()
        bitmap?.recycle()
        bitmap = null
        pix = null
        rowCache = null
        invalidate()
    }

    fun resetView() {
        zoom = 1f
        panX = 0f
        panY = 0f
        dropHi()
        clampPan()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
        scheduleHiRes()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hiRun)
        super.onDetachedFromWindow()
    }

    // ---------- الدقة العالية عند التكبير ----------
    private fun dropHi() {
        removeCallbacks(hiRun)
        hiToken++
        hi?.recycle()
        hi = null
    }

    private fun scheduleHiRes() {
        removeCallbacks(hiRun)
        if (bitmap == null || zoom <= 1.05f) {
            if (hi != null) dropHi()
            return
        }
        postDelayed(hiRun, 180)
    }

    private fun requestHiRes() {
        if (bitmap == null || zoom <= 1.05f || width == 0 || height == 0) return
        val r = imageRect()
        if (r.width() <= 0f) return
        val pl = panX + r.left * zoom
        val pt = panY + r.top * zoom
        val pw = r.width() * zoom
        val ph = r.height() * zoom
        val vis = RectF(max(0f, pl), max(0f, pt), min(width.toFloat(), pl + pw), min(height.toFloat(), pt + ph))
        if (vis.width() < 8f || vis.height() < 8f) return
        var w = vis.width().toInt().coerceAtLeast(1)
        var h = vis.height().toInt().coerceAtLeast(1)
        val maxPixels = 2_300_000f
        val total = w.toFloat() * h
        if (total > maxPixels) {
            val k = sqrt(maxPixels / total)
            w = (w * k).toInt().coerceAtLeast(1)
            h = (h * k).toInt().coerceAtLeast(1)
        }
        val u0 = (vis.left - pl) / pw
        val v0 = (vis.top - pt) / ph
        val u1 = (vis.right - pl) / pw
        val v1 = (vis.bottom - pt) / ph
        val token = ++hiToken
        val page = host.currentPage
        val rect = RectF(vis)
        val z = zoom
        val px = panX
        val py = panY
        host.renderRegion(page, u0, v0, u1, v1, w, h, { token != hiToken || page != host.currentPage }) { bm ->
            if (token != hiToken || page != host.currentPage || bitmap == null) {
                bm.recycle()
            } else {
                hi?.recycle()
                hi = bm
                hiRect = rect
                hiZoom = z
                hiPanX = px
                hiPanY = py
                hiPage = page
                invalidate()
            }
        }
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

        var save = c.save()
        c.translate(panX, panY)
        c.scale(zoom, zoom)
        c.drawBitmap(b, null, r, paint)
        c.restoreToCount(save)

        // النسخة عالية الدقة فوق الأساسية (بتتحرك مع الصفحة وإنت بتسحب)
        val h = hi
        if (h != null && hiPage == page && abs(hiZoom - zoom) < 0.001f) {
            val dx = panX - hiPanX
            val dy = panY - hiPanY
            val dst = RectF(hiRect.left + dx, hiRect.top + dy, hiRect.right + dx, hiRect.bottom + dy)
            c.drawBitmap(h, null, dst, paint)
        }
        paint.colorFilter = null

        save = c.save()
        c.translate(panX, panY)
        c.scale(zoom, zoom)
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
        for (m in previewMarks) drawMark(c, m, r)
        brushPreview?.let { drawMark(c, it, r) }
        c.restoreToCount(save)
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

    private fun resetGesture() {
        selecting = false
        brushDrawing = false
        brushStart = null
        brushPreview = null
        previewMarks = emptyList()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                lastCalcX = -100f
                lastCalcY = -100f
                multiTouch = false
                resetGesture()
                if (host.mode == 1 && host.highlightStyle == 1) {
                    brushDrawing = true
                    brushStart = toImage(e.x, e.y)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                resetGesture()
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
                    brushPreview = brushSegment(e.x, e.y)
                    invalidate()
                    return true
                }
                if (host.mode == 1) {
                    if (selecting || hypot(e.x - downX, e.y - downY) > slop) {
                        selecting = true
                        if (hypot(e.x - lastCalcX, e.y - lastCalcY) > 3f) {
                            lastCalcX = e.x
                            lastCalcY = e.y
                            previewMarks = buildMarks(downX, downY, e.x, e.y, false)
                        }
                        invalidate()
                    }
                    return true
                }
                if (scrollable()) {
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
                scheduleHiRes()
                if (multiTouch) {
                    multiTouch = false
                    resetGesture()
                    invalidate()
                    return true
                }
                if (brushDrawing) {
                    finishBrush(e.x, e.y)
                    resetGesture()
                    invalidate()
                    return true
                }
                if (selecting) {
                    val marks = buildMarks(downX, downY, e.x, e.y, false)
                    resetGesture()
                    commitMarks(marks)
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
                resetGesture()
                multiTouch = false
                parent?.requestDisallowInterceptTouchEvent(false)
                scheduleHiRes()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun hitMark(m: Mark, px: Float, py: Float, r: RectF): Boolean {
        if (!m.brush) return px >= m.left && px <= m.right && py >= m.top && py <= m.bottom
        val w = r.width()
        val h = r.height()
        val x1 = m.left * w
        val y1 = m.top * h
        val x2 = m.right * w
        val y2 = m.bottom * h
        val x = px * w
        val y = py * h
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((x - x1) * dx + (y - y1) * dy) / len2).coerceIn(0f, 1f)
        val cx = x1 + t * dx
        val cy = y1 + t * dy
        val tol = max(18f / zoom, max(7f, w * 0.009f) / 2f + 4f)
        return hypot(x - cx, y - cy) <= tol
    }

    private fun handleTap(x: Float, y: Float) {
        val st = host.store ?: return
        val p = toImage(x, y)
        val r = imageRect()
        val page = host.currentPage
        val found = st.marks.lastOrNull { it.page == page && hitMark(it, p.first, p.second, r) }
        val tb = st.boxes.lastOrNull {
            it.page == page && p.first >= it.x - 0.03f && p.first <= it.x + 0.8f && p.second >= it.y - 0.08f && p.second <= it.y + 0.08f
        }
        when (host.mode) {
            1 -> {
                if (found != null) {
                    host.editMark(found)
                } else if (host.highlightStyle == 0) {
                    commitMarks(buildMarks(x, y, x, y, true))
                }
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

    private fun commitMarks(marks: List<Mark>) {
        val st = host.store ?: return
        if (marks.isEmpty()) {
            host.toast("مش قادر أحدد سطر واضح هنا. قرّب من الكلام وحاول تاني")
            return
        }
        host.rememberUndo()
        st.marks.addAll(marks)
        host.marksChanged()
    }

    // ---------- الفرشاة: خط مستقيم ----------
    /** خط مستقيم من نقطة البداية للنقطة الحالية، ويتصحح لأفقي أو رأسي تمامًا لو الميل بسيط. */
    private fun brushSegment(x: Float, y: Float): Mark? {
        val s = brushStart ?: return null
        val e = toImage(x, y)
        val r = imageRect()
        var ex = e.first.coerceIn(0f, 1f)
        var ey = e.second.coerceIn(0f, 1f)
        val dx = (ex - s.first) * r.width()
        val dy = (ey - s.second) * r.height()
        if (hypot(dx, dy) * zoom < slop * 2f) return null
        if (abs(dy) <= abs(dx) * 0.27f) {
            ey = s.second
        } else if (abs(dx) <= abs(dy) * 0.27f) {
            ex = s.first
        }
        return Mark(host.currentPage, s.first, s.second, ex, ey, host.markColor, host.markOpacity, true)
    }

    private fun finishBrush(x: Float, y: Float) {
        val st = host.store ?: return
        val seg = brushSegment(x, y) ?: return
        host.rememberUndo()
        st.marks.add(seg)
        host.marksChanged()
    }

    // ---------- التمييز الذكي ----------
    private fun pixels(): IntArray? {
        val b = bitmap ?: return null
        var p = pix
        if (p == null || p.size != b.width * b.height) {
            p = IntArray(b.width * b.height)
            b.getPixels(p, 0, b.width, 0, 0, b.width, b.height)
            pix = p
        }
        return p
    }

    /**
     * بيحوّل لمستك لتمييز على السطور من غير OCR:
     * - whole = true (ضغطة): يميّز السطر كله في العمود اللي ضغطت عليه.
     * - whole = false (سحب): زي تحديد النص. السطر الأول من نقطة البداية لآخره، السطور اللي في النص كاملة،
     *   وآخر سطر من أوله لنقطة النهاية. ولو السحب على سطر واحد يتميّز الجزء اللي بين نقطتيك بس.
     *   الحدود بتتقرّب لأقرب كلمة كاملة عشان الشكل يبقى نضيف.
     */
    private fun buildMarks(x1: Float, y1: Float, x2: Float, y2: Float, whole: Boolean): List<Mark> {
        val b = bitmap ?: return emptyList()
        val p = pixels() ?: return emptyList()
        val bw = b.width
        val bh = b.height
        val rtl = host.rtlBook
        val a = toImage(x1, y1)
        val z = toImage(x2, y2)
        val ax = (a.first * bw).toInt().coerceIn(0, bw - 1)
        val ay = (a.second * bh).toInt().coerceIn(0, bh - 1)
        val zx = (z.first * bw).toInt().coerceIn(0, bw - 1)
        val zy = (z.second * bh).toInt().coerceIn(0, bh - 1)

        fun dark(x: Int, y: Int): Boolean {
            val c = p[y * bw + x]
            val lum = (((c shr 16) and 255) * 30 + ((c shr 8) and 255) * 59 + (c and 255) * 11) / 100
            return lum < 178
        }

        var cache = rowCache
        if (cache == null || cache.size != bh) {
            cache = IntArray(bh) { -1 }
            rowCache = cache
        }
        val rc = cache
        fun rowCount(y: Int): Int {
            val v = rc[y]
            if (v >= 0) return v
            var n = 0
            var x = 0
            while (x < bw) {
                if (dark(x, y)) n++
                x += 2
            }
            rc[y] = n
            return n
        }

        val rowThr = max(2, (bw * 0.002f).toInt())
        val yTop = min(ay, zy)
        val yBot = (max(ay, zy) + 1).coerceIn(yTop + 1, bh)

        val seeds = ArrayList<Int>()
        for (y in yTop until yBot) if (rowCount(y) >= rowThr) seeds.add(y)
        if (seeds.isEmpty()) {
            val cy = (yTop + yBot) / 2
            var best = -1
            var bestCount = 0
            for (y in max(0, cy - 28)..min(bh - 1, cy + 28)) {
                val c = rowCount(y)
                if (c > bestCount) {
                    bestCount = c
                    best = y
                }
            }
            if (best >= 0 && bestCount >= rowThr) seeds.add(best)
        }
        if (seeds.isEmpty()) return emptyList()

        // نجمّع الصفوف في سطور ونوسّع كل سطر لحدوده الكاملة
        val bands = ArrayList<IntArray>()
        var bandStart = seeds[0]
        var prev = seeds[0]
        for (i in 1 until seeds.size) {
            val y = seeds[i]
            if (y - prev > 5) {
                bands.add(intArrayOf(bandStart, prev + 1))
                bandStart = y
            }
            prev = y
        }
        bands.add(intArrayOf(bandStart, prev + 1))
        for (bd in bands) {
            var t = bd[0]
            var bt = bd[1]
            var n = 0
            while (t > 0 && n < 24 && rowCount(t - 1) >= rowThr) {
                t--
                n++
            }
            n = 0
            while (bt < bh && n < 24 && rowCount(bt) >= rowThr) {
                bt++
                n++
            }
            bd[0] = t
            bd[1] = bt
        }
        bands.sortBy { it[0] }
        val merged = ArrayList<IntArray>()
        for (bd in bands) {
            val l = merged.lastOrNull()
            if (l != null && bd[0] <= l[1]) l[1] = max(l[1], bd[1]) else merged.add(bd)
        }
        var use: List<IntArray> = merged
        if (!whole && merged.size > 1) {
            // نتجاهل السطر اللي إصبعك لمسه من طرفه بس
            val kept = merged.filter {
                val ov = min(it[1], yBot) - max(it[0], yTop)
                ov.toFloat() / (it[1] - it[0]) >= 0.4f
            }
            use = if (kept.isEmpty()) {
                listOf(merged.maxByOrNull { min(it[1], yBot) - max(it[0], yTop) } ?: merged[0])
            } else kept
        }

        // نقطة البداية هي الأعلى (ولو في نفس السطر: حسب اتجاه القراءة)
        val startIsFirst = ay < zy || (ay == zy && (if (rtl) ax >= zx else ax <= zx))
        val sx = if (startIsFirst) ax else zx
        val ex = if (startIsFirst) zx else ax

        val colGap = max(24, (bw * 0.04f).toInt())
        var refL = if (whole || use.size == 1) min(ax, zx) else sx
        var refR = if (whole || use.size == 1) max(ax, zx) else sx

        val out = ArrayList<Mark>()
        for ((i, bd) in use.withIndex()) {
            val top = max(0, bd[0] - 2)
            val bot = min(bh, bd[1] + 3)

            // أي أعمدة فيها حبر في السطر ده
            val colThr = max(1, ((bot - top) * 0.03f).toInt())
            val occ = BooleanArray(bw)
            for (x in 0 until bw) {
                var n = 0
                var y = top
                while (y < bot) {
                    if (dark(x, y)) n++
                    y += 2
                }
                occ[x] = n >= colThr
            }

            fun runs(from: Int, to: Int, gap: Int): List<IntArray> {
                val res = ArrayList<IntArray>()
                var s = -1
                var last = -1
                for (x in from until to) {
                    if (occ[x]) {
                        if (s < 0) {
                            s = x
                        } else if (x - last > gap) {
                            res.add(intArrayOf(s, last))
                            s = x
                        }
                        last = x
                    }
                }
                if (s >= 0) res.add(intArrayOf(s, last))
                return res
            }

            // نختار العمود اللي إحنا فيه (مهم للصفحات اللي فيها عمودين)
            var col: IntArray? = null
            var bestScore = Int.MIN_VALUE
            for (c in runs(0, bw, colGap)) {
                val ov = min(c[1], refR) - max(c[0], refL)
                val score = if (ov >= 0) 100000 + ov else -max(c[0] - refR, refL - c[1])
                if (score > bestScore) {
                    bestScore = score
                    col = c
                }
            }
            val cc = col ?: continue

            var xa = cc[0]
            var xb = cc[1]
            if (!whole) {
                if (use.size == 1) {
                    xa = max(cc[0], min(ax, zx))
                    xb = min(cc[1], max(ax, zx))
                } else if (i == 0) {
                    if (rtl) xb = min(cc[1], sx) else xa = max(cc[0], sx)
                } else if (i == use.size - 1) {
                    if (rtl) xa = max(cc[0], ex) else xb = min(cc[1], ex)
                }
                // نقرّب الحدود لأقرب كلمة كاملة
                val wordGap = max(3, ((bot - top) * 0.22f).toInt())
                for (w in runs(cc[0], cc[1] + 1, wordGap)) {
                    if (xa >= w[0] && xa <= w[1]) xa = w[0]
                    if (xb >= w[0] && xb <= w[1]) xb = w[1]
                }
            }
            refL = cc[0]
            refR = cc[1]
            if (xb - xa < 3) continue
            out.add(
                Mark(
                    host.currentPage,
                    max(0, xa - 2).toFloat() / bw, top.toFloat() / bh,
                    min(bw, xb + 3).toFloat() / bw, bot.toFloat() / bh,
                    host.markColor, host.markOpacity
                )
            )
        }
        return out
    }
}
