package com.ahmedrehan.simplepdfreader

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.min

/**
 * شاشة نظرة عامة على كل صفحات الكتاب: مصغّرات بشارات ملونة للوسوم، وفهرس مرتب،
 * مع بحث وفلاتر. الضغط على صفحة بيفتحها، والضغط المطوّل (أو زر ✎ في الفهرس) بيعدّل اسمها ووسومها وتعليقها.
 */
class PagesScreen(
    private val act: Activity,
    private val store: DocStore,
    private val pageCount: Int,
    private val loader: ThumbLoader,
    private val currentPage: () -> Int,
    private val onOpen: (Int) -> Unit,
    private val onEdit: (Int) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(act) {

    private enum class Show(val label: String) {
        ALL("الكل"), MAIN("رئيسي"), SUB("جانبي"), REF("مرجعي"), STAR("★ مهمة"), NOTED("بتعليق")
    }

    private var show = Show.ALL
    private var query = ""
    private var tocMode = false
    private var items: List<Int> = emptyList()

    private val grid = GridView(act)
    private val toc = ListView(act)
    private val empty = TextView(act)
    private val countText = TextView(act)
    private val search = EditText(act)
    private val chipViews = ArrayList<TextView>()
    private val tabGrid: TextView
    private val tabToc: TextView
    private val gridAdapter = GridAdapter()
    private val tocAdapter = TocAdapter()

    private class CellViews(val img: ImageView, val badges: LinearLayout, val label: TextView)

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setBackgroundColor(Theme.PAPER)
        isClickable = true
        setPadding(act.dp(14), act.dp(10), act.dp(14), 0)

        // العنوان وزر الإغلاق
        val header = LinearLayout(act)
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(act)
        title.text = "الصفحات والفهرس"
        title.textSize = 22f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Theme.INK)
        header.addView(title, lp(0, -2, 1f))
        header.addView(act.uiButton("إغلاق ✕", false, 14f) { onClose() }, lp(-2, -2))
        addView(header, lp(-1, -2))

        // البحث
        search.hint = "ابحث في الأسماء والتعليقات والوسوم أو رقم الصفحة"
        search.isSingleLine = true
        search.textSize = 16f
        search.setTextColor(Theme.INK)
        search.setHintTextColor(Theme.MUTED)
        search.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        search.background = act.box(Theme.SURFACE, Theme.INK, 1, 10)
        search.setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                refresh()
            }
        })
        val sp = lp(-1, -2)
        sp.topMargin = act.dp(10)
        addView(search, sp)

        // الفلاتر
        val chipScroll = HorizontalScrollView(act)
        chipScroll.isHorizontalScrollBarEnabled = false
        val chipRow = LinearLayout(act)
        for (s in Show.values()) {
            val chip = act.uiChip(s.label, chipColor(s), s == show) {
                show = s
                styleChips()
                refresh()
            }
            chipViews.add(chip)
            val cp = lp(-2, -2)
            cp.setMargins(act.dp(4), 0, act.dp(4), 0)
            chipRow.addView(chip, cp)
        }
        chipScroll.addView(chipRow)
        val cs = lp(-1, -2)
        cs.topMargin = act.dp(10)
        addView(chipScroll, cs)

        // تبويبات: مصغّرات / فهرس
        val tabs = LinearLayout(act)
        tabGrid = act.uiButton("المصغّرات", true, 14f) { setToc(false) }
        tabToc = act.uiButton("الفهرس", false, 14f) { setToc(true) }
        val t1 = lp(0, -2, 1f)
        t1.setMargins(act.dp(4), 0, act.dp(4), 0)
        val t2 = lp(0, -2, 1f)
        t2.setMargins(act.dp(4), 0, act.dp(4), 0)
        tabs.addView(tabGrid, t1)
        tabs.addView(tabToc, t2)
        val tp = lp(-1, -2)
        tp.topMargin = act.dp(10)
        addView(tabs, tp)

        countText.textSize = 13f
        countText.setTextColor(Theme.MUTED)
        countText.setPadding(0, act.dp(8), 0, act.dp(4))
        addView(countText, lp(-1, -2))

        // المحتوى
        val content = FrameLayout(act)
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = act.dp(118)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.horizontalSpacing = act.dp(8)
        grid.verticalSpacing = act.dp(8)
        grid.selector = ColorDrawable(Color.TRANSPARENT)
        grid.clipToPadding = false
        grid.setPadding(0, 0, 0, act.dp(20))
        grid.adapter = gridAdapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            val p = items.getOrNull(pos)
            if (p != null) onOpen(p)
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val p = items.getOrNull(pos)
            if (p != null) onEdit(p)
            true
        }
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                updateWanted(first, visible)
            }
        })

        toc.divider = ColorDrawable(Theme.LINE)
        toc.dividerHeight = act.dp(1)
        toc.selector = ColorDrawable(Theme.PRESSED)
        toc.clipToPadding = false
        toc.setPadding(0, 0, 0, act.dp(20))
        toc.adapter = tocAdapter
        toc.setOnItemClickListener { _, _, pos, _ ->
            val p = items.getOrNull(pos)
            if (p != null) onOpen(p)
        }
        toc.setOnItemLongClickListener { _, _, pos, _ ->
            val p = items.getOrNull(pos)
            if (p != null) onEdit(p)
            true
        }
        toc.visibility = View.GONE

        empty.textSize = 16f
        empty.setTextColor(Theme.MUTED)
        empty.gravity = Gravity.CENTER
        empty.setPadding(act.dp(24), act.dp(40), act.dp(24), 0)
        empty.visibility = View.GONE

        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        content.addView(toc, FrameLayout.LayoutParams(-1, -1))
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        addView(content, lp(-1, 0, 1f))

        refresh()
        val cur = currentPage()
        val pos = items.indexOf(cur)
        if (pos > 0) grid.setSelection(pos)
    }

    private fun chipColor(s: Show): Int = when (s) {
        Show.MAIN -> PageTag.MAIN.color
        Show.SUB -> PageTag.SUB.color
        Show.REF -> PageTag.REF.color
        Show.STAR -> PageTag.STAR.color
        else -> Theme.INK
    }

    private fun styleChips() {
        val all = Show.values()
        for (i in chipViews.indices) act.styleChip(chipViews[i], chipColor(all[i]), all[i] == show)
    }

    private fun setToc(on: Boolean) {
        tocMode = on
        act.styleButton(tabGrid, !on)
        act.styleButton(tabToc, on)
        refresh()
    }

    fun openSearch() {
        search.requestFocus()
        try {
            val imm = act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {
        }
    }

    private fun updateWanted(first: Int, visible: Int) {
        if (items.isEmpty()) return
        val lo = items.getOrNull(first) ?: 0
        val hi = items.getOrNull(min(items.size - 1, first + visible)) ?: pageCount
        loader.wantedFrom = lo - 6
        loader.wantedTo = hi + 6
    }

    private fun passes(p: Int, q: String): Boolean {
        val info = store.pages[p]
        val ok = when (show) {
            Show.ALL -> true
            Show.NOTED -> info != null && info.comment.isNotBlank()
            Show.MAIN -> info != null && info.tags.contains(PageTag.MAIN)
            Show.SUB -> info != null && info.tags.contains(PageTag.SUB)
            Show.REF -> info != null && info.tags.contains(PageTag.REF)
            Show.STAR -> info != null && info.tags.contains(PageTag.STAR)
        }
        return ok && store.matches(p, q)
    }

    /** بيعيد بناء القائمة بعد أي تغيير في البحث أو الفلتر أو بيانات الصفحات. */
    fun refresh() {
        val q = normalizeAr(query.trim())
        val source: List<Int> = if (tocMode) store.pages.keys.sorted() else (0 until pageCount).toList()
        val list = ArrayList<Int>()
        for (p in source) if (passes(p, q)) list.add(p)
        items = list
        gridAdapter.notifyDataSetChanged()
        tocAdapter.notifyDataSetChanged()
        grid.visibility = if (tocMode) View.GONE else View.VISIBLE
        toc.visibility = if (tocMode) View.VISIBLE else View.GONE
        countText.text = if (tocMode) {
            "${list.size} صفحة في الفهرس. اضغط على صفحة لفتحها، أو ✎ لتعديلها."
        } else {
            "${list.size} من $pageCount صفحة. اضغط مطولاً على أي صفحة لتسميتها ووسمها والتعليق عليها."
        }
        if (list.isEmpty()) {
            empty.text = if (tocMode && store.pages.isEmpty() && query.isBlank() && show == Show.ALL) {
                "لسه مفيش صفحات موسومة.\nاضغط مطولاً على أي صفحة في المصغّرات، أو استخدم زر (وسم وتعليق) وإنت بتقرأ."
            } else {
                "مفيش نتائج مطابقة."
            }
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
        }
    }

    // ---------- شبكة المصغّرات ----------
    private inner class GridAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].toLong()

        private fun buildCell(): View {
            val cell = LinearLayout(act)
            cell.orientation = LinearLayout.VERTICAL
            cell.setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(4))
            val frame = FrameLayout(act)
            frame.background = act.box(Color.WHITE, Theme.LINE, 1, 4)
            val img = ImageView(act)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            frame.addView(img, FrameLayout.LayoutParams(-1, act.dp(150)))
            val badges = LinearLayout(act)
            val bp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            bp.setMargins(act.dp(3), act.dp(3), act.dp(3), 0)
            frame.addView(badges, bp)
            val label = TextView(act)
            label.textSize = 12f
            label.setTextColor(Theme.INK)
            label.maxLines = 2
            label.ellipsize = TextUtils.TruncateAt.END
            label.setPadding(0, act.dp(3), 0, 0)
            cell.addView(frame, lp(-1, -2))
            cell.addView(label, lp(-1, -2))
            cell.tag = CellViews(img, badges, label)
            return cell
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val page = items[position]
            val cell = convertView ?: buildCell()
            val v = cell.tag as CellViews
            val info = store.pages[page]

            v.img.tag = page
            val cached = loader.cached(page)
            if (cached != null) {
                v.img.setImageBitmap(cached)
            } else {
                v.img.setImageBitmap(null)
                loader.load(page) { bm ->
                    if (v.img.tag == page) v.img.setImageBitmap(bm)
                }
            }

            v.badges.removeAllViews()
            if (info != null) {
                for (t in info.tags.sortedBy { it.ordinal }) {
                    val bp = lp(-2, -2)
                    bp.setMargins(0, 0, act.dp(3), 0)
                    v.badges.addView(act.tagBadge(t), bp)
                }
            }

            val sb = StringBuilder()
            sb.append(page + 1)
            if (info != null && info.title.isNotBlank()) sb.append(" · ").append(info.title)
            if (info != null && info.comment.isNotBlank()) sb.append(" 💬")
            if (store.hasMarks(page)) sb.append(" ✎")
            v.label.text = sb.toString()
            v.label.setTypeface(null, if (page == currentPage()) Typeface.BOLD else Typeface.NORMAL)
            cell.background = if (page == currentPage()) act.box(Color.TRANSPARENT, Theme.INK, 3, 6) else null
            return cell
        }
    }

    // ---------- الفهرس ----------
    private inner class TocAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val page = items[position]
            val info = store.pages[page]
            val tags: Set<PageTag> = info?.tags ?: emptySet()
            val isMain = tags.contains(PageTag.MAIN)
            val isSub = tags.contains(PageTag.SUB) && !isMain

            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(act.dp(4), act.dp(8), act.dp(4), act.dp(8))
            if (page == currentPage()) row.setBackgroundColor(Theme.PRESSED)

            val accent = View(act)
            val first = tags.sortedBy { it.ordinal }.firstOrNull()
            accent.setBackgroundColor(first?.color ?: Theme.LINE)
            val ap = lp(act.dp(5), -1)
            if (isSub) ap.setMargins(act.dp(28), 0, 0, 0)
            row.addView(accent, ap)

            val col = LinearLayout(act)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(act.dp(12), 0, act.dp(8), 0)

            val name = TextView(act)
            val title = info?.title ?: ""
            name.text = "صفحة ${page + 1}" + (if (title.isNotBlank()) " — $title" else "")
            name.textSize = if (isMain) 18f else 16f
            name.typeface = if (isMain) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            name.setTextColor(Theme.INK)
            col.addView(name, lp(-1, -2))

            if (info != null) {
                val sb = android.text.SpannableStringBuilder()
                for (t in tags.sortedBy { it.ordinal }) {
                    val s = sb.length
                    sb.append(t.label)
                    sb.setSpan(
                        android.text.style.ForegroundColorSpan(t.color), s, sb.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.append("   ")
                }
                if (info.comment.isNotBlank()) sb.append("💬 ").append(info.comment.replace('\n', ' ').take(90))
                if (sb.isNotEmpty()) {
                    val sub = TextView(act)
                    sub.text = sb
                    sub.textSize = 13f
                    sub.setTextColor(Theme.MUTED)
                    sub.maxLines = 2
                    sub.ellipsize = TextUtils.TruncateAt.END
                    col.addView(sub, lp(-1, -2))
                }
            }
            row.addView(col, lp(0, -2, 1f))
            row.addView(act.uiButton("✎", false, 16f) { onEdit(page) }, lp(act.dp(48), act.dp(48)))
            return row
        }
    }
}
