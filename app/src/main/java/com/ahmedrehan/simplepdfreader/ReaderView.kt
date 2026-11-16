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
import android.view.HapticFeedbackConstants
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** الواجهة اللي بتكلّم بيها شاشة القراءة النشاط الرئيسي. */
interface ReaderHost {
    val store: DocStore?
    val mode: Int            // 0 تصفح، 1 تمييز، 2 ممحاة
    val highlightStyle: Int  // 0 تمييز الجزء المحدد، 1 فرشاة مستقيمة
    val markColor: Int
    val markOpacity: Int
    val fitWidth: Boolean
    val night: Boolean
    val grayscale: Boolean
    val rtlBook: Boolean     // اتجاه النص (عربي) وده بيأثر على تحديد الكلام
    val flipMode: Int        // 0 يسار->يمين، 1 يمين->يسار، 2 رأسي متواصل

    fun toast(msg: String)
    fun rememberUndo()
    fun marksChanged()
    fun boxesChanged()
    fun turnPage(delta: Int)
    fun pageChanged(index: Int)
    fun tapCenter()
    fun editMark(m: Mark)
    fun editBox(b: TextBox)

    /** بيرسم صفحة كاملة بدقة مناسبة للشاشة في خيط خلفي. */
    fun requestPage(page: Int, stale: () -> Boolean, done: (Bitmap) -> Unit)

    /** بيرسم جزء من الصفحة (بنسب من 0 لـ 1) بدقة عالية في خيط خلفي. */
    fun renderRegion(
        page: Int, u0: Float, v0: Float, u1: Float, v1: Float, w: Int, h: Int,
        stale: () -> Boolean, done: (Bitmap) -> Unit
    )
}

/**
 * بيعرض الكتاب في ثلاث طرق:
 * - تقليب أفقي (يسار->يمين أو يمين->يسار): صفحة واحدة في الشاشة.
 * - رأسي متواصل: كل الصفحات فوق بعض كأنها صفحة واحدة طويلة، بتمرير ناعم.
 * وفيه تكبير وتحريك، ورسم الجزء الظاهر بدقة أعلى عند الحاجة، وتمييز ذكي بدون OCR.
 */
class ReaderView(ctx: Context, private val host: ReaderHost) : View(ctx) {

    private class Hit(val page: Int, val fx: Float, val fy: Float)
    private class Tile(val bm: Bitmap, val rect: RectF, val zoom: Float, val panX: Float, val panY: Float)

    // ---------- الكتاب والتخطيط ----------
    private var pageCount = 0
    private var defaultAspect = 1.414f
    private var aspects: FloatArray? = null
    var current = 0
        private set
    private val gap = ctx.dp(10).toFloat()
    private var tops = FloatArray(1)
    private var worldH = 0f
    private var pendingGoto: Triple<Int, Boolean, Boolean>? = null

    private val continuous: Boolean get() = host.flipMode == 2

    // ---------- العرض ----------
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val scroller = OverScroller(ctx)
    private var velocity: VelocityTracker? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val whitePaint = Paint().apply { color = Color.WHITE }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity
    private val swipeDistance = ctx.dp(40).toFloat()

    // ---------- صور الصفحات ----------
    private val cache = LinkedHashMap<Int, Bitmap>(8, 0.75f, true)
    private val inflight = HashMap<Int, Long>()
    @Volatile
    private var wantLo = 0
    @Volatile
    private var wantHi = 0
    private val tiles = HashMap<Int, Tile>()
    private var hiToken = 0
    private val hiRun = Runnable { requestHiRes() }
    private var updatePosted = false
    private val updateRun = Runnable {
        updatePosted = false
        updateVisible()
    }

    // ---------- تحليل السطور ----------
    private var analysisPage = -1
    private var analysisBmp: Bitmap? = null
    private var pix: IntArray? = null
    private var rowCache: IntArray? = null

    // ---------- اللمس ----------
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
    private var panned = false
    private var previewMarks: List<Mark> = emptyList()
    private var brushDrawing = false
    private var brushPage = -1
    private var brushStart: Pair<Float, Float>? = null
    private var brushPreview: Mark? = null

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
            scheduleUpdate()
            invalidate()
            return true
        }
    })

    // =====================================================================
    // إدارة الكتاب
    // =====================================================================
    fun setDocument(count: Int, firstAspect: Float) {
        clearDocument()
        pageCount = count
        defaultAspect = firstAspect.coerceIn(0.2f, 5f)
        aspects = null
        current = 0
        relayout()
    }

    fun clearDocument() {
        scroller.forceFinished(true)
        removeCallbacks(updateRun)
        updatePosted = false
        dropHi()
        for (b in cache.values) b.recycle()
        cache.clear()
        inflight.clear()
        analysisPage = -1
        analysisBmp = null
        pix = null
        rowCache = null
        pageCount = 0
        aspects = null
        current = 0
        pendingGoto = null
        resetGesture()
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    /** نسب الصفحات الحقيقية (بتوصل متأخر بعد فتح الكتاب)، بنحافظ على مكانك في الوضع الرأسي. */
    fun setAspects(a: FloatArray) {
        if (a.size != pageCount) return
        var off = 0f
        val anchored = continuous && tops.size == pageCount + 1 && current in 0 until pageCount && zoom > 0f
        if (anchored) off = (-panY) / zoom - tops[current]
        aspects = a
        relayout()
        if (anchored) panY = -(tops[current] + off) * zoom
        clampPan()
        dropHi()
        scheduleUpdate()
        invalidate()
    }

    /** بعد تغيير طريقة التقليب. */
    fun flipChanged() {
        relayout()
        goToPage(current, false, true)
    }

    fun resetView() {
        goToPage(current, false, true)
    }

    private fun asp(i: Int): Float {
        val a = aspects
        return if (a != null && i in a.indices && a[i] > 0.05f) a[i] else defaultAspect
    }

    private fun relayout() {
        if (pageCount == 0 || width == 0) return
        if (continuous) {
            val t = FloatArray(pageCount + 1)
            var acc = 0f
            for (i in 0 until pageCount) {
                t[i] = acc
                acc += width * asp(i) + gap
            }
            t[pageCount] = acc
            tops = t
            worldH = max(1f, acc - gap)
        }
    }

    private fun layoutReady(): Boolean =
        pageCount > 0 && width > 0 && height > 0 && (!continuous || tops.size == pageCount + 1)

    private fun pageRect(i: Int): RectF {
        if (continuous) {
            val t = tops[i]
            return RectF(0f, t, width.toFloat(), t + width * asp(i))
        }
        val ar = asp(i)
        val w: Float
        val h: Float
        if (host.fitWidth) {
            w = width.toFloat()
            h = w * ar
        } else {
            w = min(width.toFloat(), height / ar)
            h = w * ar
        }
        val left = (width - w) / 2f
        val top = if (h >= height) 0f else (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    private fun contentBounds(): RectF =
        if (continuous) RectF(0f, 0f, width.toFloat(), worldH) else pageRect(current)

    private fun findPage(wy: Float): Int {
        var l = 0
        var r = pageCount - 1
        while (l < r) {
            val m = (l + r + 1) / 2
            if (tops[m] <= wy) l = m else r = m - 1
        }
        return l
    }

    private fun visibleRange(): IntRange? {
        if (!layoutReady()) return null
        if (!continuous) return current..current
        val y0 = (-panY) / zoom
        val y1 = (height - panY) / zoom
        return findPage(y0)..findPage(y1)
    }

    private fun hitPage(x: Float, y: Float): Hit? {
        if (!layoutReady()) return null
        val wx = (x - panX) / zoom
        val wy = (y - panY) / zoom
        val p = if (continuous) findPage(wy) else current
        val r = pageRect(p)
        return Hit(p, (wx - r.left) / max(1f, r.width()), (wy - r.top) / max(1f, r.height()))
    }

    private fun fractionsIn(page: Int, x: Float, y: Float): Pair<Float, Float> {
        val r = pageRect(page)
        val wx = (x - panX) / zoom
        val wy = (y - panY) / zoom
        return Pair((wx - r.left) / max(1f, r.width()), (wy - r.top) / max(1f, r.height()))
    }

    /** حدود التحريك المسموحة: minX, maxX, minY, maxY. */
    private fun bounds(): FloatArray {
        val cb = contentBounds()
        val sw = cb.width() * zoom
        val sh = cb.height() * zoom
        val minX: Float
        val maxX: Float
        if (sw <= width) {
            val c = (width - sw) / 2f - cb.left * zoom
            minX = c
            maxX = c
        } else {
            minX = width - cb.right * zoom
            maxX = -cb.left * zoom
        }
        val minY: Float
        val maxY: Float
        if (sh <= height) {
            val c = (height - sh) / 2f - cb.top * zoom
            minY = c
            maxY = c
        } else {
            minY = height - cb.bottom * zoom
            maxY = -cb.top * zoom
        }
        return floatArrayOf(minX, maxX, minY, maxY)
    }

    private fun clampPan() {
        if (!layoutReady()) {
            panX = 0f
            panY = 0f
            return
        }
        val b = bounds()
        panX = panX.coerceIn(b[0], b[1])
        panY = panY.coerceIn(b[2], b[3])
    }

    fun goToPage(i: Int, keepZoom: Boolean = false, toTop: Boolean = true) {
        if (pageCount == 0) return
        val t = i.coerceIn(0, pageCount - 1)
        if (width == 0 || height == 0) {
            pendingGoto = Triple(t, keepZoom, toTop)
            current = t
            host.pageChanged(t)
            return
        }
        scroller.forceFinished(true)
        current = t
        if (!keepZoom) zoom = 1f
        dropHi()
        relayout()
        resetGesture()
        val b = bounds()
        if (!keepZoom) panX = (b[0] + b[1]) / 2f
        if (continuous) {
            panY = -tops[t] * zoom
        } else {
            panY = if (toTop) b[3] else b[2]
        }
        clampPan()
        host.pageChanged(t)
        scheduleUpdate()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pageCount == 0 || w == 0 || h == 0) return
        val pg = pendingGoto
        pendingGoto = null
        // نأجّل إعادة التموضع لحد ما التخطيط يخلص عشان ما نغيّر الواجهة وسط عملية القياس
        post {
            if (pageCount > 0 && width > 0 && height > 0) {
                if (pg != null) goToPage(pg.first, pg.second, pg.third) else goToPage(current, false, true)
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hiRun)
        removeCallbacks(updateRun)
        super.onDetachedFromWindow()
    }

    // =====================================================================
    // التحميل والتحديث
    // =====================================================================
    private fun scheduleUpdate() {
        if (!updatePosted) {
            updatePosted = true
            postOnAnimation(updateRun)
        }
    }

    private fun updateVisible() {
        val vr = visibleRange() ?: return
        if (continuous) {
            var best = vr.first
            var bestOv = -1f
            for (p in vr) {
                val r = pageRect(p)
                val top = panY + r.top * zoom
                val bot = panY + r.bottom * zoom
                val ov = min(bot, height.toFloat()) - max(top, 0f)
                if (ov > bestOv) {
                    bestOv = ov
                    best = p
                }
            }
            if (best != current) {
                current = best
                host.pageChanged(best)
            }
        }
        wantLo = max(0, vr.first - 1)
        wantHi = min(pageCount - 1, vr.last + 1)
        val iter = inflight.keys.iterator()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k < wantLo || k > wantHi) iter.remove()
        }
        val order = ArrayList<Int>()
        for (p in vr) order.add(p)
        if (wantLo < vr.first) order.add(wantLo)
        if (wantHi > vr.last) order.add(wantHi)
        val now = System.currentTimeMillis()
        for (p in order) {
            if (cache.containsKey(p)) continue
            val t = inflight[p]
            if (t != null && now - t < 1500) continue
            inflight[p] = now
            requestBase(p)
        }
        scheduleHiRes()
    }

    private fun requestBase(p: Int) {
        host.requestPage(p, { p < wantLo || p > wantHi }) { bm ->
            inflight.remove(p)
            if (pageCount == 0 || p >= pageCount) {
                bm.recycle()
            } else {
                val old = cache.put(p, bm)
                if (old != null && old !== bm) old.recycle()
                trimCache()
                invalidate()
                scheduleHiRes()
            }
        }
    }

    private fun trimCache() {
        while (cache.size > 5) {
            var victim = -1
            for (k in cache.keys) {
                if (k < wantLo || k > wantHi) {
                    victim = k
                    break
                }
            }
            if (victim < 0) break
            cache.remove(victim)?.recycle()
        }
    }

    // ---------- الدقة العالية ----------
    private fun dropHi() {
        removeCallbacks(hiRun)
        hiToken++
        for (t in tiles.values) t.bm.recycle()
        tiles.clear()
    }

    private fun scheduleHiRes() {
        removeCallbacks(hiRun)
        postDelayed(hiRun, 180)
    }

    private fun requestHiRes() {
        val vr = visibleRange() ?: return
        val token = ++hiToken
        val gone = ArrayList<Int>()
        for (k in tiles.keys) if (k !in vr) gone.add(k)
        for (k in gone) tiles.remove(k)?.bm?.recycle()
        for (p in vr) {
            val bm = cache[p]
            if (bm == null || bm.isRecycled) continue
            val r = pageRect(p)
            val pxPerWorld = bm.width / max(1f, r.width())
            if (zoom <= pxPerWorld * 1.08f) {
                tiles.remove(p)?.bm?.recycle()
                continue
            }
            val pl = panX + r.left * zoom
            val pt = panY + r.top * zoom
            val pw = r.width() * zoom
            val ph = r.height() * zoom
            val vis = RectF(max(0f, pl), max(0f, pt), min(width.toFloat(), pl + pw), min(height.toFloat(), pt + ph))
            if (vis.width() < 8f || vis.height() < 8f) continue
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
            val rect = RectF(vis)
            val z = zoom
            val px = panX
            val py = panY
            host.renderRegion(p, u0, v0, u1, v1, w, h, { token != hiToken }) { out ->
                if (token != hiToken || pageCount == 0) {
                    out.recycle()
                } else {
                    tiles.put(p, Tile(out, rect, z, px, py))?.bm?.recycle()
                    invalidate()
                }
            }
        }
    }

    // =====================================================================
    // الرسم
    // =====================================================================
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
        c.drawColor(if (host.night) Color.BLACK else Theme.PAPER)
        val vr = visibleRange() ?: return
        val first = vr.first
        val rects = Array(vr.last - first + 1) { pageRect(first + it) }

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
        for (p in vr) {
            val r = rects[p - first]
            val bm = cache[p]
            if (bm != null && !bm.isRecycled) {
                c.drawBitmap(bm, null, r, paint)
            } else {
                whitePaint.colorFilter = paint.colorFilter
                c.drawRect(r, whitePaint)
            }
        }
        c.restoreToCount(save)

        // الأجزاء عالية الدقة فوق الأساسية. بتتحرك مع الصفحة وإنت بتسحب.
        for ((p, t) in tiles) {
            if (p !in vr || abs(t.zoom - zoom) > 0.001f || t.bm.isRecycled) continue
            val dx = panX - t.panX
            val dy = panY - t.panY
            val dst = RectF(t.rect.left + dx, t.rect.top + dy, t.rect.right + dx, t.rect.bottom + dy)
            c.drawBitmap(t.bm, null, dst, paint)
        }
        paint.colorFilter = null

        val st = host.store
        save = c.save()
        c.translate(panX, panY)
        c.scale(zoom, zoom)
        if (st != null) {
            for (m in st.marks) {
                if (m.page in vr) drawMark(c, m, rects[m.page - first])
            }
            for (tb in st.boxes) {
                if (tb.page !in vr) continue
                val r = rects[tb.page - first]
                paint.style = Paint.Style.FILL
                paint.color = tb.color
                paint.alpha = 255
                paint.textSize = tb.size
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                c.drawText(tb.text, r.left + tb.x * r.width(), r.top + tb.y * r.height(), paint)
            }
        }
        for (m in previewMarks) if (m.page in vr) drawMark(c, m, rects[m.page - first])
        brushPreview?.let { if (it.page in vr) drawMark(c, it, rects[it.page - first]) }
        c.restoreToCount(save)
        paint.alpha = 255
    }

    // =====================================================================
    // التمرير
    // =====================================================================
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.currX.toFloat()
            panY = scroller.currY.toFloat()
            clampPan()
            scheduleUpdate()
            postInvalidateOnAnimation()
        }
    }

    /** بينزّل جزء من الشاشة (٨٥٪) في الاتجاه المطلوب. بيرجع false لو مفيش مكان يتحرك فيه. */
    fun scrollScreen(dir: Int): Boolean {
        if (!layoutReady()) return false
        val b = bounds()
        val target = (panY - dir * height * 0.85f).coerceIn(b[2], b[3])
        if (abs(target - panY) < 2f) return false
        scroller.forceFinished(true)
        scroller.startScroll(panX.toInt(), panY.toInt(), 0, (target - panY).toInt(), 260)
        postInvalidateOnAnimation()
        return true
    }

    /**
     * زر الصوت: بينزّل جزء من الصفحة بدل ما يقفز صفحة كاملة.
     * لما الصفحة تخلص (في الأوضاع الأفقية) بينتقل للصفحة اللي بعدها.
     */
    fun pageStep(dir: Int) {
        if (!layoutReady()) return
        if (scrollScreen(dir)) return
        if (continuous) return
        val t = current + dir
        if (t < 0 || t >= pageCount) return
        goToPage(t, zoom > 1.01f, dir > 0)
    }

    // =====================================================================
    // اللمس
    // =====================================================================
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

    /**
     * في وضع التمييز: السحب العادي تمرير، والتمييز بيبدأ بضغطة مطوّلة (من غير تحريك) وبعدها اسحب.
     */
    private val holdRun = Runnable {
        if (host.mode != 1 || multiTouch || panned || brushDrawing || selecting) return@Runnable
        val h = hitPage(downX, downY) ?: return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (host.highlightStyle == 1) {
            brushDrawing = true
            brushPage = h.page
            brushStart = Pair(h.fx, h.fy)
        } else {
            selecting = true
            previewMarks = emptyList()
        }
        invalidate()
    }

    private fun resetGesture() {
        removeCallbacks(holdRun)
        selecting = false
        brushDrawing = false
        brushStart = null
        brushPage = -1
        brushPreview = null
        previewMarks = emptyList()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                velocity?.addMovement(e)
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                lastCalcX = -100f
                lastCalcY = -100f
                multiTouch = false
                panned = false
                resetGesture()
                if (host.mode == 1) postDelayed(holdRun, 350L)
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
                    scheduleUpdate()
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
                if (selecting) {
                    if (hypot(e.x - lastCalcX, e.y - lastCalcY) > 3f) {
                        lastCalcX = e.x
                        lastCalcY = e.y
                        previewMarks = buildMarks(downX, downY, e.x, e.y, false)
                    }
                    invalidate()
                    return true
                }
                if (!panned && hypot(e.x - downX, e.y - downY) > slop) {
                    panned = true
                    removeCallbacks(holdRun)
                }
                panX += e.x - lastX
                panY += e.y - lastY
                clampPan()
                scheduleUpdate()
                invalidate()
                lastX = e.x
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRun)
                parent?.requestDisallowInterceptTouchEvent(false)
                scheduleUpdate()
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
                    val far = hypot(e.x - downX, e.y - downY) > slop
                    val marks = if (far) buildMarks(downX, downY, e.x, e.y, false) else emptyList()
                    resetGesture()
                    if (far) commitMarks(marks)
                    invalidate()
                    return true
                }
                val dx = e.x - downX
                val dy = e.y - downY
                if (panned) {
                    val b = bounds()
                    val fixedX = b[0] >= b[1] - 1f
                    if ((host.mode == 0 || host.mode == 1) && !continuous && fixedX && abs(dx) > swipeDistance && abs(dx) > abs(dy) * 1.25f) {
                        val forward = dx < 0f
                        host.turnPage(if (forward != (host.flipMode == 1)) 1 else -1)
                        return true
                    }
                    val vt = velocity
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000, maxFling.toFloat())
                        val vx = vt.xVelocity
                        val vy = vt.yVelocity
                        if (abs(vx) > minFling || abs(vy) > minFling) {
                            scroller.fling(
                                panX.toInt(), panY.toInt(), vx.toInt(), vy.toInt(),
                                b[0].toInt(), b[1].toInt(), b[2].toInt(), b[3].toInt()
                            )
                            postInvalidateOnAnimation()
                        }
                    }
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
                scheduleUpdate()
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
        val h = hitPage(x, y) ?: return
        val r = pageRect(h.page)
        val found = st.marks.lastOrNull { it.page == h.page && hitMark(it, h.fx, h.fy, r) }
        val tb = st.boxes.lastOrNull {
            it.page == h.page && h.fx >= it.x - 0.03f && h.fx <= it.x + 0.8f && h.fy >= it.y - 0.08f && h.fy <= it.y + 0.08f
        }
        when (host.mode) {
            1 -> {
                // ضغطة على تمييز: تعديله. ضغطة في أي مكان تاني: إخفاء/إظهار الأيقونات
                if (found != null) host.editMark(found) else host.tapCenter()
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
                } else if (continuous) {
                    when {
                        y < height * 0.18f -> scrollScreen(-1)
                        y > height * 0.82f -> scrollScreen(1)
                        else -> host.tapCenter()
                    }
                } else {
                    val rtlFlip = host.flipMode == 1
                    if (zoom <= 1.01f && x < width * 0.2f) {
                        host.turnPage(if (rtlFlip) 1 else -1)
                    } else if (zoom <= 1.01f && x > width * 0.8f) {
                        host.turnPage(if (rtlFlip) -1 else 1)
                    } else {
                        host.tapCenter()
                    }
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

    // =====================================================================
    // الفرشاة: خط مستقيم
    // =====================================================================
    /** خط مستقيم من نقطة البداية للنقطة الحالية، ويتصحح لأفقي أو رأسي تمامًا لو الميل بسيط. */
    private fun brushSegment(x: Float, y: Float): Mark? {
        val s = brushStart ?: return null
        if (brushPage !in 0 until pageCount) return null
        val e = fractionsIn(brushPage, x, y)
        val r = pageRect(brushPage)
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
        return Mark(brushPage, s.first.coerceIn(0f, 1f), s.second.coerceIn(0f, 1f), ex, ey, host.markColor, host.markOpacity, true)
    }

    private fun finishBrush(x: Float, y: Float) {
        val st = host.store ?: return
        val seg = brushSegment(x, y) ?: return
        host.rememberUndo()
        st.marks.add(seg)
        host.marksChanged()
    }

    // =====================================================================
    // التمييز الذكي
    // =====================================================================
    private fun pixelsFor(page: Int, b: Bitmap): IntArray {
        var p = pix
        if (analysisPage != page || analysisBmp !== b || p == null || p.size != b.width * b.height) {
            p = IntArray(b.width * b.height)
            b.getPixels(p, 0, b.width, 0, 0, b.width, b.height)
            pix = p
            rowCache = null
            analysisPage = page
            analysisBmp = b
        }
        return p
    }

    /**
     * بيحوّل لمستك لتمييز على السطور من غير OCR:
     * - whole = true (ضغطة): يميّز السطر كله في العمود اللي ضغطت عليه.
     * - whole = false (سحب): زي تحديد النص. السطر الأول من نقطة البداية لآخره، السطور اللي في النص كاملة،
     *   وآخر سطر من أوله لنقطة النهاية. ولو السحب على سطر واحد يتميّز الجزء اللي بين نقطتيك بس.
     *   الحدود بتتقرّب لأقرب كلمة كاملة عشان الشكل يبقى نضيف.
     * التمييز بيتم على الصفحة اللي بدأت منها السحب.
     */
    private fun buildMarks(x1: Float, y1: Float, x2: Float, y2: Float, whole: Boolean): List<Mark> {
        val h1 = hitPage(x1, y1) ?: return emptyList()
        val page = h1.page
        val b = cache[page]
        if (b == null || b.isRecycled) return emptyList()
        val p = pixelsFor(page, b)
        val bw = b.width
        val bh = b.height
        val rtl = host.rtlBook
        val f2 = fractionsIn(page, x2, y2)
        val ax = (h1.fx * bw).toInt().coerceIn(0, bw - 1)
        val ay = (h1.fy * bh).toInt().coerceIn(0, bh - 1)
        val zx = (f2.first * bw).toInt().coerceIn(0, bw - 1)
        val zy = (f2.second * bh).toInt().coerceIn(0, bh - 1)

        fun dark(x: Int, y: Int): Boolean {
            val c = p[y * bw + x]
            val lum = (((c shr 16) and 255) * 30 + ((c shr 8) and 255) * 59 + (c and 255) * 11) / 100
            return lum < 178
        }

        var cacheRows = rowCache
        if (cacheRows == null || cacheRows.size != bh) {
            cacheRows = IntArray(bh) { -1 }
            rowCache = cacheRows
        }
        val rc = cacheRows
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
                    page,
                    max(0, xa - 2).toFloat() / bw, top.toFloat() / bh,
                    min(bw, xb + 3).toFloat() / bw, bot.toFloat() / bh,
                    host.markColor, host.markOpacity
                )
            )
        }
        return out
    }
}
