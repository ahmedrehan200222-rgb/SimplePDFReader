package com.ahmedrehan.simplepdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity(), ReaderHost {

    companion object {
        val COLORS = intArrayOf(
            0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(), 0xFF2196F3.toInt(), 0xFFE91E63.toInt(),
            0xFFFF9800.toInt(), 0xFF9C27B0.toInt(), 0xFFF44336.toInt(), 0xFF00BCD4.toInt()
        )
        val COLOR_NAMES = arrayOf("أصفر", "أخضر", "أزرق", "وردي", "برتقالي", "بنفسجي", "أحمر", "سماوي")
        const val PICK_PDF = 77
    }

    // ---------- حالة الكتاب ----------
    private var doc: PdfDoc? = null
    private var thumbs: ThumbLoader? = null
    private var docKey: String? = null
    private var docName = ""
    override var store: DocStore? = null
    var currentPage = 0
    private var renderToken = 0
    private val renderExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val undoStack = mutableListOf<List<Mark>>()
    private val redoStack = mutableListOf<List<Mark>>()
    private val hiExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val aspectExec: ExecutorService = Executors.newSingleThreadExecutor()

    // ---------- إعدادات ----------
    override var mode = 0
    override var highlightStyle = 0
    override var fitWidth = true
    override var night = false
    override var grayscale = false
    override var flipMode = 0
    private var rtlText = false
    private var orient = 0
    override val rtlBook: Boolean get() = rtlText
    override val markColor: Int get() = COLORS[chosenColor]
    override val markOpacity: Int get() = opacity
    private var chosenColor = 0
    private var opacity = 55
    private var volumeFlips = true
    private var chromeVisible = true
    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }

    // ---------- الواجهة ----------
    private lateinit var rootFrame: FrameLayout
    private lateinit var home: View
    private lateinit var recentBox: LinearLayout
    private lateinit var recentTitle: TextView
    private lateinit var readerRoot: LinearLayout
    private lateinit var reader: ReaderView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private lateinit var seek: SeekBar
    private lateinit var leftNav: TextView
    private lateinit var rightNav: TextView
    private lateinit var modeRow: HorizontalScrollView
    private lateinit var paletteRow: LinearLayout
    private lateinit var hlBtn: IconView
    private lateinit var erBtn: IconView
    private lateinit var undoBtn: IconView
    private lateinit var redoBtn: IconView
    private lateinit var nightBtn: IconView
    private lateinit var grayBtn: IconView
    private lateinit var flipBtn: IconView
    private var pagesScreen: PagesScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // قديماً كان فيه 3 اختيارات للصوت (0 تقليب). دلوقتي تقليب أو عادي بس
        volumeFlips = prefs.getBoolean("volumeFlips", prefs.getInt("soundMode", 0) == 0)
        chosenColor = prefs.getInt("color", 0).coerceIn(0, COLORS.size - 1)
        opacity = prefs.getInt("opacity", 55)
        night = prefs.getBoolean("night", false)
        orient = prefs.getInt("orient", 0).coerceIn(0, 2)
        applyOrientation()
        val oldRtl = prefs.getBoolean("rtl", false)
        rtlText = prefs.getBoolean("rtlText", oldRtl)
        flipMode = prefs.getInt("flip", if (oldRtl) 1 else 0).coerceIn(0, 2)
        fitWidth = prefs.getBoolean("fitWidth", true)
        grayscale = prefs.getBoolean("gray", false)

        rootFrame = FrameLayout(this)
        rootFrame.setBackgroundColor(Theme.PAPER)
        rootFrame.layoutDirection = View.LAYOUT_DIRECTION_RTL
        reader = ReaderView(this, this)
        home = buildHome()
        readerRoot = buildReaderUi()
        rootFrame.addView(home, FrameLayout.LayoutParams(-1, -1))
        rootFrame.addView(readerRoot, FrameLayout.LayoutParams(-1, -1))
        readerRoot.visibility = View.GONE
        setContentView(rootFrame)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyBookDirection()
        refreshHome()

        val last = prefs.getString("lastUri", null)
        if (last != null) openUri(Uri.parse(last), true)
    }

    // =====================================================================
    // الشاشة الرئيسية (المكتبة)
    // =====================================================================
    private fun buildHome(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(40), dp(24), dp(24))

        val title = TextView(this)
        title.text = "قارئ PDF"
        title.textSize = 30f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Theme.INK)
        col.addView(title, lp(-1, -2))

        val sub = TextView(this)
        sub.text = "اقرأ، ميّز السطور المهمة، وسمّ الصفحات وعلّق عليها."
        sub.textSize = 15f
        sub.setTextColor(Theme.MUTED)
        sub.setPadding(0, dp(4), 0, dp(20))
        col.addView(sub, lp(-1, -2))

        col.addView(uiButton("فتح ملف PDF", true, 18f) { choosePdf() }, lp(-1, dp(60)))

        recentTitle = TextView(this)
        recentTitle.text = "آخر الملفات"
        recentTitle.textSize = 16f
        recentTitle.typeface = Typeface.DEFAULT_BOLD
        recentTitle.setTextColor(Theme.INK)
        recentTitle.setPadding(0, dp(28), 0, dp(4))
        col.addView(recentTitle, lp(-1, -2))

        recentBox = LinearLayout(this)
        recentBox.orientation = LinearLayout.VERTICAL
        col.addView(recentBox, lp(-1, -2))

        scroll.addView(col)
        return scroll
    }

    private fun refreshHome() {
        recentBox.removeAllViews()
        val list = loadRecent()
        recentTitle.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        for ((uri, name) in list) {
            val card = TextView(this)
            card.text = name
            card.textSize = 16f
            card.setTextColor(Theme.INK)
            card.maxLines = 2
            card.ellipsize = TextUtils.TruncateAt.END
            card.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            card.minHeight = dp(56)
            card.isClickable = true
            card.background = pressable(box(Theme.SURFACE, Theme.LINE, 1, 10), box(Theme.PRESSED, Theme.LINE, 1, 10))
            card.setPadding(dp(14), dp(10), dp(14), dp(10))
            card.setOnClickListener { openUri(Uri.parse(uri), false) }
            card.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(name).setMessage("حذف الملف من القائمة؟ (الملف نفسه مش هيتمسح)")
                    .setPositiveButton("حذف من القائمة") { _, _ ->
                        removeRecent(uri)
                        refreshHome()
                    }.setNegativeButton("إلغاء", null).show()
                true
            }
            val p = lp(-1, -2)
            p.topMargin = dp(8)
            recentBox.addView(card, p)
        }
    }

    private fun loadRecent(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val a = JSONArray(prefs.getString("recent", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Pair(o.getString("u"), o.optString("n", "ملف PDF")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveRecent(list: List<Pair<String, String>>) {
        val a = JSONArray()
        for ((u, n) in list.take(10)) a.put(JSONObject().put("u", u).put("n", n))
        prefs.edit().putString("recent", a.toString()).apply()
    }

    private fun addRecent(uri: String, name: String) {
        val list = loadRecent().filter { it.first != uri }.toMutableList()
        list.add(0, Pair(uri, name))
        saveRecent(list)
    }

    private fun removeRecent(uri: String) {
        saveRecent(loadRecent().filter { it.first != uri })
    }

    private fun queryName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "ملف PDF"
    }

    // =====================================================================
    // واجهة القراءة
    // =====================================================================
    private fun buildReaderUi(): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        // الشريط العلوي
        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(Theme.SURFACE)
        topBar.setPadding(dp(8), dp(4), dp(8), dp(4))
        topBar.addView(iconButton(Icon.HOME, "المكتبة", { toast(it) }) { goHome() }, lp(dp(52), dp(48)))
        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.gravity = Gravity.CENTER
        mid.setPadding(dp(8), 0, dp(8), 0)
        titleText = TextView(this)
        titleText.textSize = 18f
        titleText.typeface = Typeface.DEFAULT_BOLD
        titleText.setTextColor(Theme.INK)
        titleText.gravity = Gravity.CENTER
        subText = TextView(this)
        subText.textSize = 13f
        subText.setTextColor(Theme.MUTED)
        subText.gravity = Gravity.CENTER
        subText.maxLines = 1
        subText.ellipsize = TextUtils.TruncateAt.END
        mid.addView(titleText, lp(-1, -2))
        mid.addView(subText, lp(-1, -2))
        topBar.addView(mid, lp(0, -2, 1f))
        val rp = lp(dp(52), dp(48))
        rp.leftMargin = dp(4)
        rp.rightMargin = dp(4)
        topBar.addView(iconButton(Icon.ROTATE, "تدوير الشاشة", { toast(it) }) { showRotateDialog() }, rp)
        flipBtn = iconButton(Icon.FLIP_LTR, "طريقة التقليب", { toast(it) }) { showFlipDialog() }
        topBar.addView(flipBtn, lp(dp(52), dp(48)))
        val fp = lp(dp(52), dp(48))
        fp.leftMargin = dp(4)
        fp.rightMargin = dp(4)
        topBar.addView(iconButton(Icon.MORE, "المزيد", { toast(it) }) { showMoreOptions() }, fp)
        col.addView(topBar, lp(-1, -2))
        col.addView(divider(), lp(-1, dp(1)))

        // منطقة القراءة
        col.addView(reader, lp(-1, 0, 1f))
        col.addView(divider(), lp(-1, dp(1)))

        // الشريط السفلي
        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.VERTICAL
        bottomBar.setBackgroundColor(Theme.SURFACE)
        bottomBar.setPadding(dp(8), dp(6), dp(8), dp(8))

        modeRow = HorizontalScrollView(this)
        modeRow.isHorizontalScrollBarEnabled = false
        paletteRow = LinearLayout(this)
        paletteRow.gravity = Gravity.CENTER_VERTICAL
        modeRow.addView(paletteRow)
        modeRow.visibility = View.GONE
        buildPalette()
        bottomBar.addView(modeRow, lp(-1, -2))

        // صف التنقل: زر - شريط الصفحات - زر
        val navRow = LinearLayout(this)
        navRow.orientation = LinearLayout.HORIZONTAL
        navRow.gravity = Gravity.CENTER_VERTICAL
        navRow.layoutDirection = View.LAYOUT_DIRECTION_LTR
        leftNav = uiButton("‹", false, 24f) { turnPage(if (flipMode == 1) 1 else -1) }
        rightNav = uiButton("›", false, 24f) { turnPage(if (flipMode == 1) -1 else 1) }
        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) titleText.text = pageLabel(progress)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                if (s != null) showPage(s.progress)
            }
        })
        navRow.addView(leftNav, lp(dp(56), dp(48)))
        navRow.addView(seek, lp(0, dp(48), 1f))
        navRow.addView(rightNav, lp(dp(56), dp(48)))
        bottomBar.addView(navRow, lp(-1, -2))

        // الأدوات: صف واحد بأيقونات (الضغط المطوّل على أي أيقونة بيظهر اسمها)
        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        val tp = lp(-1, -2)
        tp.topMargin = dp(4)
        bottomBar.addView(tools, tp)
        fun tool(icon: Icon, label: String, action: () -> Unit): IconView {
            val b = iconButton(icon, label, { toast(it) }, action)
            val p = lp(0, dp(46), 1f)
            p.setMargins(dp(2), 0, dp(2), 0)
            tools.addView(b, p)
            return b
        }
        hlBtn = tool(Icon.PEN, "تمييز") { switchMode(if (mode == 1) 0 else 1) }
        erBtn = tool(Icon.ERASER, "ممحاة") { switchMode(if (mode == 2) 0 else 2) }
        undoBtn = tool(Icon.UNDO, "تراجع") { undo() }
        redoBtn = tool(Icon.REDO, "إعادة") { redo() }
        tool(Icon.TEXT, "إضافة نص") { addTextBox() }
        tool(Icon.PAGES, "الصفحات والفهرس") { showPages(false) }
        tool(Icon.SEARCH, "بحث في الصفحات") { showPages(true) }
        tool(Icon.TAG, "وسم الصفحة وتعليق") { editPageInfo(currentPage) }
        nightBtn = tool(Icon.MOON, "الوضع الليلي") { toggleNight() }
        grayBtn = tool(Icon.CONTRAST, "أبيض وأسود") { toggleGray() }
        nightBtn.active = night
        grayBtn.active = grayscale
        updateUndoUi()

        col.addView(bottomBar, lp(-1, -2))
        return col
    }

    private fun styleHint(): String =
        if (highlightStyle == 0) {
            "اسحب على الكلام لتمييز الجزء المحدد بس. ضغطة على سطر بتميّزه كله. ولو مكبّر، حرّك الصفحة بإصبعين."
        } else {
            "اسحب لرسم خط مستقيم (بيتعدّل لأفقي أو رأسي تلقائياً لو الميل بسيط)."
        }

    private fun buildPalette() {
        paletteRow.removeAllViews()
        val styleBtn = uiButton(if (highlightStyle == 0) "النوع: جزء محدد" else "النوع: فرشاة مستقيمة", false, 13f) {
            highlightStyle = 1 - highlightStyle
            buildPalette()
            toast(styleHint())
        }
        val sp = lp(-2, dp(44))
        sp.setMargins(dp(4), dp(2), dp(8), dp(2))
        paletteRow.addView(styleBtn, sp)
        for (i in COLORS.indices) {
            val d = colorDot(COLORS[i], i == chosenColor)
            d.setOnClickListener {
                chosenColor = i
                prefs.edit().putInt("color", i).apply()
                buildPalette()
            }
            val p = lp(dp(36), dp(36))
            p.setMargins(dp(6), dp(4), dp(6), dp(4))
            paletteRow.addView(d, p)
        }
        val op = uiButton("الشفافية", false, 13f) { showOpacityDialog() }
        val opp = lp(-2, dp(44))
        opp.setMargins(dp(8), dp(2), dp(4), dp(2))
        paletteRow.addView(op, opp)
    }

    private fun applyBookDirection() {
        val rtlFlip = flipMode == 1
        seek.layoutDirection = if (rtlFlip) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        leftNav.setOnClickListener { turnPage(if (rtlFlip) 1 else -1) }
        rightNav.setOnClickListener { turnPage(if (rtlFlip) -1 else 1) }
        flipBtn.icon = when (flipMode) {
            0 -> Icon.FLIP_LTR
            1 -> Icon.FLIP_RTL
            else -> Icon.FLIP_DOWN
        }
        flipBtn.invalidate()
    }

    private fun switchMode(m: Int) {
        mode = m
        hlBtn.active = m == 1
        erBtn.active = m == 2
        modeRow.visibility = if (m == 1) View.VISIBLE else View.GONE
        if (m != 0) setChrome(true)
        when (m) {
            1 -> toast(styleHint())
            2 -> toast("الممحاة: اضغط على تمييز أو مربع نص لحذفه")
            else -> {}
        }
    }

    private fun setChrome(visible: Boolean) {
        chromeVisible = visible
        val v = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = v
        bottomBar.visibility = v
    }

    private fun pageLabel(i: Int): String = "${i + 1} / ${doc?.pageCount ?: 0}"

    private fun updateChrome() {
        titleText.text = pageLabel(currentPage)
        val info = store?.pages?.get(currentPage)
        val line = SpannableStringBuilder()
        if (info != null) {
            line.append(info.describe())
            if (info.comment.isNotBlank()) line.append("  💬")
        }
        if (line.isEmpty()) line.append(docName)
        subText.text = line
        if (seek.progress != currentPage) seek.progress = currentPage
    }

    // =====================================================================
    // فتح وإغلاق الكتب
    // =====================================================================
    private fun choosePdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, PICK_PDF)
    }

    @Deprecated("Legacy document picker callback for Android 7")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_PDF && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(u, data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            openUri(u, false)
        }
    }

    private fun openUri(uri: Uri, quiet: Boolean) {
        try {
            closeDocument()
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("ملف غير متاح")
            val d = try {
                PdfDoc(pfd)
            } catch (e: Exception) {
                try {
                    pfd.close()
                } catch (_: Exception) {
                }
                throw e
            }
            val key = uri.toString()
            doc = d
            docKey = key
            docName = queryName(uri)
            store = DocStore(prefs, key)
            thumbs = ThumbLoader(d, dp(120))
            prefs.edit().putString("lastUri", key).apply()
            addRecent(key, docName)
            showReaderUi()
            seek.max = (d.pageCount - 1).coerceAtLeast(0)
            val start = prefs.getInt("page:$key", 0).coerceIn(0, (d.pageCount - 1).coerceAtLeast(0))
            currentPage = start
            seek.progress = start
            reader.setDocument(d.pageCount, d.firstAspect())
            updateChrome()
            aspectExec.execute {
                val a = try {
                    d.computeAspects()
                } catch (e: Throwable) {
                    null
                }
                if (a != null) runOnUiThread { if (doc === d) reader.setAspects(a) }
            }
            showPage(start)
        } catch (e: Exception) {
            closeDocument()
            showHomeUi()
            if (!quiet) toast("تعذر فتح الملف: ${e.message}")
        }
    }

    private fun closeDocument() {
        closePages()
        renderToken++
        thumbs?.shutdown()
        thumbs = null
        reader.clearDocument()
        doc?.close()
        doc = null
        store = null
        docKey = null
        undoStack.clear()
        redoStack.clear()
        updateUndoUi()
        mode = 0
        modeRow.visibility = View.GONE
        hlBtn.active = false
        erBtn.active = false
    }

    private fun showReaderUi() {
        home.visibility = View.GONE
        readerRoot.visibility = View.VISIBLE
        setChrome(true)
        applyImmersive(true)
    }

    private fun showHomeUi() {
        readerRoot.visibility = View.GONE
        home.visibility = View.VISIBLE
        applyImmersive(false)
        refreshHome()
    }

    private fun goHome() {
        closeDocument()
        showHomeUi()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(on: Boolean) {
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && doc != null) applyImmersive(true)
    }

    private fun showPage(index: Int) {
        val d = doc ?: return
        if (index < 0 || index >= d.pageCount) return
        reader.goToPage(index, false, true)
    }

    /** الـ reader بيبلّغنا لما الصفحة الحالية تتغير (بالتقليب أو بالتمرير). */
    override fun pageChanged(index: Int) {
        currentPage = index
        updateChrome()
        pagesScreen?.refresh()
        val key = docKey
        if (key != null) prefs.edit().putInt("page:$key", index).apply()
    }

    override fun requestPage(page: Int, stale: () -> Boolean, done: (Bitmap) -> Unit) {
        val d = doc ?: return
        val screenW = resources.displayMetrics.widthPixels
        try {
            renderExec.execute {
                if (!stale() && doc === d) {
                    val bm = try {
                        d.renderPage(page, screenW, 1400)
                    } catch (e: Throwable) {
                        null
                    }
                    if (bm != null) {
                        runOnUiThread {
                            if (doc === d) done(bm) else bm.recycle()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // =====================================================================
    // شاشة الصفحات والفهرس
    // =====================================================================
    private fun showPages(focusSearch: Boolean) {
        val st = store ?: return
        val d = doc ?: return
        val tl = thumbs ?: return
        if (pagesScreen != null) return
        val ps = PagesScreen(
            this, st, d.pageCount, tl,
            { currentPage },
            { p ->
                closePages()
                showPage(p)
            },
            { p -> editPageInfo(p) },
            { closePages() }
        )
        pagesScreen = ps
        rootFrame.addView(ps, FrameLayout.LayoutParams(-1, -1))
        if (focusSearch) ps.openSearch()
    }

    private fun closePages() {
        val ps = pagesScreen ?: return
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(ps.windowToken, 0)
        } catch (_: Exception) {
        }
        rootFrame.removeView(ps)
        pagesScreen = null
    }

    /** نافذة تعديل الصفحة: اسم، نوع (رئيسي/جانبي/مرجعي/مهمة)، وتعليق. */
    private fun editPageInfo(page: Int) {
        val st = store ?: return
        val info = st.pages[page]
        val chosen = LinkedHashSet<PageTag>()
        if (info != null) chosen.addAll(info.tags)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.layoutDirection = View.LAYOUT_DIRECTION_RTL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))

        fun label(t: String): TextView {
            val tv = TextView(this)
            tv.text = t
            tv.textSize = 14f
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(Theme.INK)
            tv.setPadding(0, dp(12), 0, dp(4))
            return tv
        }

        box.addView(label("اسم الصفحة"))
        val name = EditText(this)
        name.hint = "مثال: تعريف العقد"
        name.setText(info?.title ?: "")
        name.isSingleLine = true
        name.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        box.addView(name, lp(-1, -2))

        box.addView(label("نوع الصفحة (اختار واحد أو أكتر)"))
        val tags = PageTag.values()
        var row: LinearLayout? = null
        for ((i, t) in tags.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                val rp = lp(-1, -2)
                rp.topMargin = dp(6)
                box.addView(row, rp)
            }
            lateinit var chip: TextView
            chip = uiChip(t.label, t.color, chosen.contains(t)) {
                if (chosen.contains(t)) chosen.remove(t) else chosen.add(t)
                styleChip(chip, t.color, chosen.contains(t))
            }
            val cp = lp(0, -2, 1f)
            cp.setMargins(dp(4), 0, dp(4), 0)
            row?.addView(chip, cp)
        }

        box.addView(label("تعليقك على الصفحة"))
        val comment = EditText(this)
        comment.hint = "اكتب ملاحظتك هنا"
        comment.setText(info?.comment ?: "")
        comment.minLines = 3
        comment.gravity = Gravity.TOP or Gravity.START
        comment.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        box.addView(comment, lp(-1, -2))

        val scroll = ScrollView(this)
        scroll.addView(box)
        AlertDialog.Builder(this)
            .setTitle("الصفحة ${page + 1}")
            .setView(scroll)
            .setPositiveButton("حفظ") { _, _ ->
                st.updatePage(page, name.text.toString(), comment.text.toString(), chosen)
                afterPageInfoChanged()
            }
            .setNeutralButton("مسح بياناتها") { _, _ ->
                st.updatePage(page, "", "", emptySet())
                afterPageInfoChanged()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun afterPageInfoChanged() {
        pagesScreen?.refresh()
        updateChrome()
        reader.invalidate()
        toast("تم الحفظ")
    }

    // =====================================================================
    // ReaderHost
    // =====================================================================
    override fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun rememberUndo() {
        val st = store ?: return
        undoStack.add(st.marks.map { it.copy() })
        if (undoStack.size > 30) undoStack.removeAt(0)
        redoStack.clear()
        updateUndoUi()
    }

    private fun updateUndoUi() {
        if (!::undoBtn.isInitialized || !::redoBtn.isInitialized) return
        undoBtn.alpha = if (undoStack.isEmpty()) 0.4f else 1f
        redoBtn.alpha = if (redoStack.isEmpty()) 0.4f else 1f
    }

    override fun marksChanged() {
        store?.saveMarks()
        reader.invalidate()
        pagesScreen?.refresh()
    }

    override fun boxesChanged() {
        store?.saveBoxes()
        reader.invalidate()
    }

    override fun renderRegion(
        page: Int, u0: Float, v0: Float, u1: Float, v1: Float, w: Int, h: Int,
        stale: () -> Boolean, done: (Bitmap) -> Unit
    ) {
        val d = doc ?: return
        try {
            hiExec.execute {
                if (!stale() && doc === d) {
                    val bm = try {
                        d.renderRegion(page, u0, v0, u1, v1, w, h)
                    } catch (e: Throwable) {
                        null
                    }
                    if (bm != null) {
                        runOnUiThread {
                            if (!stale() && doc === d) done(bm) else bm.recycle()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun turnPage(delta: Int) {
        showPage(currentPage + delta)
    }

    override fun tapCenter() {
        setChrome(!chromeVisible)
    }

    override fun editMark(m: Mark) {
        AlertDialog.Builder(this).setTitle("تعديل التمييز")
            .setItems(arrayOf("تغيير اللون", "تغيير الشفافية", "حذف التمييز")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this).setTitle("اختر اللون")
                        .setSingleChoiceItems(COLOR_NAMES, COLORS.indexOf(m.color).coerceAtLeast(0)) { d, i ->
                            rememberUndo()
                            m.color = COLORS[i]
                            marksChanged()
                            d.dismiss()
                        }.setNegativeButton("إلغاء", null).show()
                    1 -> showOpacityForMark(m)
                    2 -> {
                        rememberUndo()
                        store?.marks?.remove(m)
                        marksChanged()
                        toast("تم حذف التمييز")
                    }
                    else -> {}
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    override fun editBox(b: TextBox) {
        val input = EditText(this)
        input.setText(b.text)
        AlertDialog.Builder(this).setTitle("تعديل مربع النص").setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                b.text = input.text.toString()
                boxesChanged()
            }
            .setNeutralButton("حذف") { _, _ ->
                store?.boxes?.remove(b)
                boxesChanged()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    // =====================================================================
    // القوائم والإعدادات
    // =====================================================================
    private fun onOff(b: Boolean): String = if (b) "  ✓" else ""

    private fun showMoreOptions() {
        val items = arrayOf(
            "الانتقال إلى رقم صفحة",
            "ملاءمة الصفحة لعرض الشاشة" + onOff(fitWidth),
            "أزرار الصوت تقلّب الصفحات" + onOff(volumeFlips),
            "النص عربي (التحديد من اليمين لليسار)" + onOff(rtlText)
        )
        AlertDialog.Builder(this).setTitle("المزيد").setItems(items) { _, i ->
            when (i) {
                0 -> gotoDialog()
                1 -> {
                    fitWidth = !fitWidth
                    prefs.edit().putBoolean("fitWidth", fitWidth).apply()
                    reader.resetView()
                }
                2 -> {
                    volumeFlips = !volumeFlips
                    prefs.edit().putBoolean("volumeFlips", volumeFlips).apply()
                    toast(if (volumeFlips) "أزرار الصوت بتقلّب الصفحات" else "أزرار الصوت للتحكم في الصوت")
                }
                3 -> {
                    rtlText = !rtlText
                    prefs.edit().putBoolean("rtlText", rtlText).apply()
                    toast(if (rtlText) "النص عربي: التحديد من اليمين لليسار" else "النص من اليسار لليمين")
                }
                else -> {}
            }
        }.show()
    }

    /** 0 تلقائي (حسب إعداد الجهاز)، 1 عمودي، 2 أفقي. الاتنين اليدويين بيسمحوا بالقلب 180 درجة. */
    private fun applyOrientation() {
        requestedOrientation = when (orient) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun showRotateDialog() {
        val names = arrayOf("تلقائي (حسب وضع الجهاز)", "عمودي (طولي)", "أفقي (عرضي)")
        AlertDialog.Builder(this).setTitle("تدوير الشاشة")
            .setSingleChoiceItems(names, orient) { d, which ->
                d.dismiss()
                orient = which
                prefs.edit().putInt("orient", which).apply()
                applyOrientation()
                toast(
                    when (which) {
                        1 -> "الشاشة مثبّتة عمودي"
                        2 -> "الشاشة مثبّتة أفقي"
                        else -> "التدوير تلقائي"
                    }
                )
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showFlipDialog() {
        val names = arrayOf(
            "أفقي: من اليسار لليمين",
            "أفقي: من اليمين لليسار (كتب عربي)",
            "رأسي متواصل: الكتاب كله صفحة واحدة طويلة"
        )
        AlertDialog.Builder(this).setTitle("طريقة التقليب")
            .setSingleChoiceItems(names, flipMode) { d, which ->
                d.dismiss()
                changeFlipMode(which)
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun changeFlipMode(m: Int) {
        flipMode = m
        if (m == 1) rtlText = true else if (m == 0) rtlText = false
        prefs.edit().putInt("flip", m).putBoolean("rtlText", rtlText).apply()
        applyBookDirection()
        reader.flipChanged()
        toast(
            when (m) {
                0 -> "التقليب من اليسار لليمين"
                1 -> "التقليب من اليمين لليسار"
                else -> "وضع رأسي: مرّر لتحت، وأزرار الصوت بتنزّل جزء من الصفحة"
            }
        )
    }

    private fun undo() {
        val st = store ?: return
        if (undoStack.isEmpty()) {
            toast("مفيش حاجة للتراجع عنها")
            return
        }
        val snap = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(st.marks.map { it.copy() })
        st.marks.clear()
        st.marks.addAll(snap.map { it.copy() })
        marksChanged()
        updateUndoUi()
        toast("تم التراجع")
    }

    private fun redo() {
        val st = store ?: return
        if (redoStack.isEmpty()) {
            toast("مفيش حاجة للإعادة")
            return
        }
        val snap = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(st.marks.map { it.copy() })
        st.marks.clear()
        st.marks.addAll(snap.map { it.copy() })
        marksChanged()
        updateUndoUi()
        toast("تمت الإعادة")
    }

    private fun toggleNight() {
        night = !night
        prefs.edit().putBoolean("night", night).apply()
        nightBtn.active = night
        reader.invalidate()
    }

    private fun toggleGray() {
        grayscale = !grayscale
        prefs.edit().putBoolean("gray", grayscale).apply()
        grayBtn.active = grayscale
        reader.invalidate()
    }

    private fun addTextBox() {
        val st = store ?: return
        val input = EditText(this)
        input.hint = "اكتب النص هنا"
        input.minLines = 2
        input.gravity = Gravity.TOP or Gravity.START
        AlertDialog.Builder(this).setTitle("مربع نص جديد").setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    st.boxes.add(TextBox(currentPage, 0.12f, 0.18f, v))
                    boxesChanged()
                    toast("تمت إضافة مربع النص. اضغط عليه لتعديله.")
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun gotoDialog() {
        val d = doc ?: return
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "رقم الصفحة (1 - ${d.pageCount})"
        AlertDialog.Builder(this).setTitle("الانتقال إلى صفحة").setView(input)
            .setPositiveButton("انتقال") { _, _ ->
                val n = input.text.toString().trim().toIntOrNull()
                if (n != null) showPage((n - 1).coerceIn(0, d.pageCount - 1))
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun opacityBox(initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(12), dp(24), dp(4))
        val label = TextView(this)
        val bar = SeekBar(this)
        bar.max = 80
        bar.progress = (initial - 10).coerceIn(0, 80)
        fun update() {
            label.text = "الشفافية: ${10 + bar.progress}%  (كل ما زاد الرقم، بان الكلام تحت التمييز أكتر)"
            onChange(10 + bar.progress)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                update()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(label)
        box.addView(bar)
        update()
        return box
    }

    private fun showOpacityDialog() {
        var value = opacity
        val box = opacityBox(opacity) { value = it }
        AlertDialog.Builder(this).setTitle("شفافية التمييز").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                opacity = value
                prefs.edit().putInt("opacity", opacity).apply()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showOpacityForMark(m: Mark) {
        var value = m.alpha
        val box = opacityBox(m.alpha) { value = it }
        AlertDialog.Builder(this).setTitle("شفافية هذا التمييز").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                rememberUndo()
                m.alpha = value
                marksChanged()
            }.setNegativeButton("إلغاء", null).show()
    }

    // =====================================================================
    // أزرار الصوت والرجوع
    // =====================================================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (doc != null && volumeFlips && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
            // في الوضع الرأسي: فوق = لفوق، تحت = لتحت. وفي الأفقي: رفع الصوت = الصفحة اللي بعدها.
            reader.pageStep(if (flipMode == 2) (if (up) -1 else 1) else (if (up) 1 else -1))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Legacy back handling for Android 7")
    override fun onBackPressed() {
        when {
            pagesScreen != null -> closePages()
            doc != null -> goHome()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        closeDocument()
        renderExec.shutdownNow()
        hiExec.shutdownNow()
        aspectExec.shutdownNow()
        super.onDestroy()
    }
}
