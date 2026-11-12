package com.ahmedrehan.simplepdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
    override var currentPage = 0
    private var targetPage = 0
    private var renderToken = 0
    private val renderExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val undoStack = mutableListOf<List<Mark>>()

    // ---------- إعدادات ----------
    override var mode = 0
    override var highlightStyle = 0
    override var fitWidth = true
    override var night = false
    override var grayscale = false
    override var rtlBook = false
    override val markColor: Int get() = COLORS[chosenColor]
    override val markOpacity: Int get() = opacity
    private var chosenColor = 0
    private var opacity = 55
    private var soundMode = 1
    private var keepAwake = true
    private var brightness = 0.5f
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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
    private lateinit var hlBtn: TextView
    private lateinit var erBtn: TextView
    private var pagesScreen: PagesScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalBrightness = window.attributes.screenBrightness
        soundMode = prefs.getInt("soundMode", 1)
        chosenColor = prefs.getInt("color", 0).coerceIn(0, COLORS.size - 1)
        opacity = prefs.getInt("opacity", 55)
        night = prefs.getBoolean("night", false)
        keepAwake = prefs.getBoolean("awake", true)
        rtlBook = prefs.getBoolean("rtl", false)
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
        if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        topBar.addView(uiButton("المكتبة", false, 14f) { goHome() }, lp(-2, -2))
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
        topBar.addView(uiButton("⋮ المزيد", false, 14f) { showMoreOptions() }, lp(-2, -2))
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
        leftNav = uiButton("‹", false, 24f) { turnPage(if (rtlBook) 1 else -1) }
        rightNav = uiButton("›", false, 24f) { turnPage(if (rtlBook) -1 else 1) }
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

        // الأدوات
        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        fun tool(label: String, action: () -> Unit): TextView {
            val b = uiButton(label, false, 13f, action)
            val p = lp(0, -2, 1f)
            p.setMargins(dp(3), 0, dp(3), 0)
            tools.addView(b, p)
            return b
        }
        tool("الصفحات") { showPages(false) }
        tool("بحث") { showPages(true) }
        hlBtn = tool("تمييز") { switchMode(if (mode == 1) 0 else 1) }
        erBtn = tool("ممحاة") { switchMode(if (mode == 2) 0 else 2) }
        tool("وسم وتعليق") { editPageInfo(currentPage) }
        val tp = lp(-1, -2)
        tp.topMargin = dp(4)
        bottomBar.addView(tools, tp)

        col.addView(bottomBar, lp(-1, -2))
        return col
    }

    private fun buildPalette() {
        paletteRow.removeAllViews()
        val styleBtn = uiButton(if (highlightStyle == 0) "جملة كاملة" else "فرشاة", false, 13f) {
            highlightStyle = 1 - highlightStyle
            buildPalette()
            toast(if (highlightStyle == 0) "اسحب فوق الكلام أو اضغط على سطر لتمييزه كاملاً" else "اسحب بإصبعك لرسم خط مستقيم")
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
    }

    private fun applyBookDirection() {
        seek.layoutDirection = if (rtlBook) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        leftNav.setOnClickListener { turnPage(if (rtlBook) 1 else -1) }
        rightNav.setOnClickListener { turnPage(if (rtlBook) -1 else 1) }
    }

    private fun switchMode(m: Int) {
        mode = m
        styleButton(hlBtn, m == 1)
        styleButton(erBtn, m == 2)
        modeRow.visibility = if (m == 1) View.VISIBLE else View.GONE
        if (m != 0) setChrome(true)
        when (m) {
            1 -> toast(if (highlightStyle == 0) "وضع التمييز: اسحب فوق الكلام أو اضغط على سطر" else "وضع الفرشاة: اسحب لرسم خط مستقيم")
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
            targetPage = start
            seek.progress = start
            updateChrome()
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
        reader.clearPage()
        doc?.close()
        doc = null
        store = null
        docKey = null
        undoStack.clear()
        mode = 0
        modeRow.visibility = View.GONE
        styleButton(hlBtn, false)
        styleButton(erBtn, false)
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
        val a = window.attributes
        a.screenBrightness = originalBrightness
        window.attributes = a
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

    /** بيرسم الصفحة في خيط خلفي عشان الجهاز مايتعلّقش، وبيستبدل الصفحة لما تخلص. */
    private fun showPage(index: Int) {
        val d = doc ?: return
        if (index < 0 || index >= d.pageCount) return
        targetPage = index
        val token = ++renderToken
        val screenW = resources.displayMetrics.widthPixels
        titleText.text = pageLabel(index)
        renderExec.execute {
            val bm = try {
                d.renderPage(index, screenW, 1250)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (token == renderToken && doc === d) {
                    if (bm == null) {
                        toast("تعذر رسم الصفحة ${index + 1}")
                    } else {
                        currentPage = index
                        reader.setPage(bm)
                        updateChrome()
                        pagesScreen?.refresh()
                        val key = docKey
                        if (key != null) prefs.edit().putInt("page:$key", index).apply()
                    }
                }
            }
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
        if (undoStack.size > 20) undoStack.removeAt(0)
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

    override fun turnPage(delta: Int) {
        showPage(targetPage + delta)
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
            "الصفحات والفهرس والبحث",
            "تراجع عن آخر تعديل",
            "إضافة مربع نص",
            "لون وشفافية التمييز",
            "الانتقال إلى رقم صفحة",
            "الوضع الليلي" + onOff(night),
            "أبيض وأسود" + onOff(grayscale),
            "ملاءمة العرض" + onOff(fitWidth),
            "الإعدادات",
            "إغلاق الكتاب والعودة للمكتبة"
        )
        AlertDialog.Builder(this).setTitle("أدوات القراءة").setItems(items) { _, i ->
            when (i) {
                0 -> showPages(false)
                1 -> undo()
                2 -> addTextBox()
                3 -> showColorSettings()
                4 -> gotoDialog()
                5 -> {
                    night = !night
                    prefs.edit().putBoolean("night", night).apply()
                    reader.invalidate()
                }
                6 -> {
                    grayscale = !grayscale
                    prefs.edit().putBoolean("gray", grayscale).apply()
                    reader.invalidate()
                }
                7 -> {
                    fitWidth = !fitWidth
                    prefs.edit().putBoolean("fitWidth", fitWidth).apply()
                    reader.resetView()
                }
                8 -> showSettings()
                9 -> goHome()
                else -> {}
            }
        }.show()
    }

    private fun undo() {
        val st = store ?: return
        if (undoStack.isEmpty()) {
            toast("مفيش حاجة للتراجع عنها")
            return
        }
        val snap = undoStack.removeAt(undoStack.lastIndex)
        st.marks.clear()
        st.marks.addAll(snap.map { it.copy() })
        marksChanged()
        toast("تم التراجع عن آخر تعديل")
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

    private fun showColorSettings() {
        AlertDialog.Builder(this).setTitle("لون التمييز")
            .setSingleChoiceItems(COLOR_NAMES, chosenColor) { d, which ->
                chosenColor = which
                prefs.edit().putInt("color", which).apply()
                buildPalette()
                d.dismiss()
                showOpacityDialog()
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

    private fun showSettings() {
        val items = arrayOf(
            "أزرار الصوت: تقليب الصفحات" + onOff(soundMode == 0),
            "أزرار الصوت: التحكم الطبيعي في الصوت" + onOff(soundMode == 1),
            "أزرار الصوت: التحكم في السطوع" + onOff(soundMode == 2),
            "منع إطفاء الشاشة أثناء القراءة" + onOff(keepAwake),
            "كتاب عربي (التقليب من اليمين لليسار)" + onOff(rtlBook),
            "تطبيق اللون والشفافية الحاليين على كل التمييزات"
        )
        AlertDialog.Builder(this).setTitle("إعدادات القراءة").setItems(items) { _, which ->
            when (which) {
                0, 1, 2 -> {
                    soundMode = which
                    prefs.edit().putInt("soundMode", which).apply()
                    toast("تم حفظ إعداد أزرار الصوت")
                }
                3 -> {
                    keepAwake = !keepAwake
                    prefs.edit().putBoolean("awake", keepAwake).apply()
                    if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    toast(if (keepAwake) "منع إطفاء الشاشة مفعّل" else "تم إيقاف منع إطفاء الشاشة")
                }
                4 -> {
                    rtlBook = !rtlBook
                    prefs.edit().putBoolean("rtl", rtlBook).apply()
                    applyBookDirection()
                    toast(if (rtlBook) "التقليب من اليمين لليسار" else "التقليب من اليسار لليمين")
                }
                5 -> {
                    val st = store
                    if (st != null) {
                        rememberUndo()
                        st.marks.forEach {
                            it.color = COLORS[chosenColor]
                            it.alpha = opacity
                        }
                        marksChanged()
                        toast("تم تطبيق الإعداد على كل التمييزات")
                    }
                }
                else -> {}
            }
        }.show()
    }

    // =====================================================================
    // أزرار الصوت والرجوع
    // =====================================================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (doc != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            when (soundMode) {
                0 -> turnPage(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                1 -> return super.onKeyDown(keyCode, event)
                2 -> {
                    brightness = (brightness + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.08f else -0.08f).coerceIn(0.05f, 1f)
                    val a = window.attributes
                    a.screenBrightness = brightness
                    window.attributes = a
                }
            }
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
        val a = window.attributes
        a.screenBrightness = originalBrightness
        window.attributes = a
        super.onDestroy()
    }
}
