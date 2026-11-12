package com.ahmedrehan.simplepdfreader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * غلاف حوالين PdfRenderer. الرندر ممنوع يشتغل على صفحتين مع بعض، فكل الوصول بيمرّ من قفل واحد
 * عشان نقدر نرسم الصفحة الأساسية والمصغّرات من خيوط مختلفة من غير تعارض.
 */
class PdfDoc(private val pfd: ParcelFileDescriptor) {
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()
    @Volatile
    private var closed = false
    val pageCount: Int = renderer.pageCount

    fun renderPage(index: Int, viewWidth: Int, maxSide: Int): Bitmap? {
        var result: Bitmap? = null
        synchronized(lock) {
            if (!closed && index in 0 until pageCount) {
                val p = renderer.openPage(index)
                try {
                    val s = min(
                        min(viewWidth.toFloat() / p.width, maxSide.toFloat() / p.width),
                        maxSide.toFloat() / p.height
                    )
                    val w = max(1, (p.width * s).toInt())
                    val h = max(1, (p.height * s).toInt())
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    result = b
                } finally {
                    p.close()
                }
            }
        }
        return result
    }

    fun renderThumb(index: Int, widthPx: Int): Bitmap? {
        var result: Bitmap? = null
        synchronized(lock) {
            if (!closed && index in 0 until pageCount) {
                val p = renderer.openPage(index)
                try {
                    val w = max(1, widthPx)
                    val h = max(1, (p.height.toFloat() / p.width * w).toInt())
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    result = b
                } finally {
                    p.close()
                }
            }
        }
        return result
    }

    fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                try {
                    renderer.close()
                } catch (_: Exception) {
                }
                try {
                    pfd.close()
                } catch (_: Exception) {
                }
            }
        }
    }
}

/** بيرسم المصغّرات في خيط خلفي وبيحتفظ بآخر مجموعة منها في الذاكرة (٨ ميجا بالكتير). */
class ThumbLoader(private val doc: PdfDoc, private val widthPx: Int) {
    private val cache = object : LruCache<Int, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var dead = false

    /** الصفحات خارج النطاق ده مش بتترسم (لما المستخدم يسكرول بسرعة). */
    @Volatile
    var wantedFrom: Int = Int.MIN_VALUE

    @Volatile
    var wantedTo: Int = Int.MAX_VALUE

    fun cached(page: Int): Bitmap? = cache.get(page)

    fun load(page: Int, done: (Bitmap) -> Unit) {
        val hit = cache.get(page)
        if (hit != null) {
            done(hit)
            return
        }
        if (dead) return
        try {
            exec.execute {
                if (!dead && page >= wantedFrom && page <= wantedTo) {
                    var bm: Bitmap? = cache.get(page)
                    if (bm == null) {
                        bm = try {
                            doc.renderThumb(page, widthPx)
                        } catch (e: Throwable) {
                            null
                        }
                        if (bm != null) cache.put(page, bm)
                    }
                    val out = bm
                    if (out != null) {
                        main.post { if (!dead) done(out) }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        dead = true
        exec.shutdownNow()
    }
}
