package com.ahmedrehan.simplepdfreader

import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** يوحّد الحروف العربية عشان البحث يلاقي الكلمة حتى لو اتكتبت بهمزة أو تاء مربوطة مختلفة. */
fun normalizeAr(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s.lowercase()) {
        if (ch in 'ً'..'ْ' || ch == 'ـ') continue
        val c: Char = when {
            ch == 'أ' || ch == 'إ' || ch == 'آ' -> 'ا'
            ch == 'ى' -> 'ي'
            ch == 'ة' -> 'ه'
            ch in '٠'..'٩' -> '0' + (ch - '٠')
            else -> ch
        }
        sb.append(c)
    }
    return sb.toString()
}

/**
 * كل بيانات كتاب واحد: التمييزات، مربعات النص، وبيانات الصفحات (اسم + تعليق + وسوم).
 * متخزنة في SharedPreferences بمفتاح الكتاب، وبتتحمّل البيانات القديمة تلقائياً.
 */
class DocStore(private val prefs: SharedPreferences, private val key: String) {
    val marks = mutableListOf<Mark>()
    val boxes = mutableListOf<TextBox>()
    val pages = HashMap<Int, PageInfo>()

    init {
        loadMarks()
        loadBoxes()
        loadPages()
    }

    // ---------- التمييزات ----------
    private fun loadMarks() {
        marks.clear()
        try {
            val a = JSONArray(prefs.getString("marks:$key", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                marks.add(
                    Mark(
                        o.getInt("p"),
                        o.getDouble("l").toFloat(),
                        o.getDouble("t").toFloat(),
                        o.getDouble("r").toFloat(),
                        o.getDouble("b").toFloat(),
                        o.getInt("c"),
                        o.getInt("a"),
                        o.optBoolean("brush", false)
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    fun saveMarks() {
        val a = JSONArray()
        marks.forEach { m ->
            a.put(
                JSONObject()
                    .put("p", m.page)
                    .put("l", m.left.toDouble())
                    .put("t", m.top.toDouble())
                    .put("r", m.right.toDouble())
                    .put("b", m.bottom.toDouble())
                    .put("c", m.color)
                    .put("a", m.alpha)
                    .put("brush", m.brush)
            )
        }
        prefs.edit().putString("marks:$key", a.toString()).apply()
    }

    fun hasMarks(page: Int): Boolean = marks.any { it.page == page }

    // ---------- مربعات النص ----------
    private fun loadBoxes() {
        boxes.clear()
        try {
            val o = JSONObject(prefs.getString("pageNotes:$key", "{}") ?: "{}")
            val b = o.optJSONArray("boxes") ?: JSONArray()
            for (i in 0 until b.length()) {
                val x = b.getJSONObject(i)
                boxes.add(
                    TextBox(
                        x.getInt("p"),
                        x.getDouble("x").toFloat(),
                        x.getDouble("y").toFloat(),
                        x.getString("t"),
                        x.optInt("c", Color.BLACK),
                        x.optDouble("s", 18.0).toFloat()
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    fun saveBoxes() {
        val b = JSONArray()
        boxes.forEach { x ->
            b.put(
                JSONObject()
                    .put("p", x.page)
                    .put("x", x.x.toDouble())
                    .put("y", x.y.toDouble())
                    .put("t", x.text)
                    .put("c", x.color)
                    .put("s", x.size.toDouble())
            )
        }
        prefs.edit().putString("pageNotes:$key", JSONObject().put("boxes", b).toString()).apply()
    }

    // ---------- بيانات الصفحات ----------
    private fun loadPages() {
        pages.clear()
        val raw = prefs.getString("pageInfo:$key", null)
        if (raw != null) {
            try {
                val o = JSONObject(raw)
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val p = k.toIntOrNull() ?: continue
                    val x = o.getJSONObject(k)
                    val info = PageInfo(x.optString("t", ""), x.optString("c", ""))
                    val g = x.optJSONArray("g")
                    if (g != null) {
                        for (i in 0 until g.length()) {
                            val tag = PageTag.fromKey(g.getString(i))
                            if (tag != null) info.tags.add(tag)
                        }
                    }
                    if (!info.isEmpty()) pages[p] = info
                }
            } catch (_: Exception) {
            }
            return
        }
        migrateOldFormat()
    }

    /** النسخة القديمة كانت بتخزن العناوين والتعليقات وعلامات الصفحات في أماكن منفصلة. */
    private fun migrateOldFormat() {
        try {
            val old = JSONObject(prefs.getString("pageNotes:$key", "{}") ?: "{}")
            val t = old.optJSONObject("titles")
            val c = old.optJSONObject("comments")
            if (t != null) {
                val ks = t.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val p = k.toIntOrNull() ?: continue
                    pages.getOrPut(p) { PageInfo() }.title = t.optString(k)
                }
            }
            if (c != null) {
                val ks = c.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val p = k.toIntOrNull() ?: continue
                    pages.getOrPut(p) { PageInfo() }.comment = c.optString(k)
                }
            }
        } catch (_: Exception) {
        }
        val stars = prefs.getStringSet("bookmarks:$key", emptySet()) ?: emptySet()
        for (s in stars) {
            val p = s.toIntOrNull() ?: continue
            pages.getOrPut(p) { PageInfo() }.tags.add(PageTag.STAR)
        }
        val dead = pages.filterValues { it.isEmpty() }.keys.toList()
        for (p in dead) pages.remove(p)
        if (pages.isNotEmpty()) savePages()
    }

    fun savePages() {
        val o = JSONObject()
        for ((p, info) in pages) {
            if (info.isEmpty()) continue
            val g = JSONArray()
            info.tags.forEach { g.put(it.key) }
            o.put(p.toString(), JSONObject().put("t", info.title).put("c", info.comment).put("g", g))
        }
        prefs.edit().putString("pageInfo:$key", o.toString()).apply()
    }

    fun updatePage(page: Int, title: String, comment: String, tags: Set<PageTag>) {
        val info = PageInfo(title.trim(), comment.trim(), tags.toMutableSet())
        if (info.isEmpty()) pages.remove(page) else pages[page] = info
        savePages()
    }

    /** هل الصفحة دي بتطابق نص البحث؟ (النص لازم يكون مطبّع بـ normalizeAr) */
    fun matches(page: Int, q: String): Boolean {
        if (q.isEmpty()) return true
        val info = pages[page]
        val sb = StringBuilder()
        sb.append(page + 1).append(' ').append("صفحة ").append(page + 1).append(' ')
        if (info != null) {
            sb.append(info.title).append(' ').append(info.comment).append(' ')
            for (t in info.tags) sb.append(t.label).append(' ').append(t.short).append(' ')
        }
        val hay = normalizeAr(sb.toString())
        for (tok in q.split(' ')) {
            if (tok.isNotEmpty() && !hay.contains(tok)) return false
        }
        return true
    }
}
