package com.ahmedrehan.simplepdfreader

import android.graphics.Color

/** أنواع الصفحات اللي تقدر تعلّم بيها أي صفحة (ممكن أكتر من نوع للصفحة الواحدة). */
enum class PageTag(val key: String, val label: String, val short: String, val color: Int) {
    MAIN("main", "عنوان رئيسي", "رئيسي", 0xFFB71C1C.toInt()),
    SUB("sub", "عنوان جانبي", "جانبي", 0xFF0D47A1.toInt()),
    REF("ref", "صفحة مرجعية", "مرجع", 0xFF1B5E20.toInt()),
    STAR("star", "مهمة ★", "★ مهمة", 0xFFE65100.toInt());

    companion object {
        fun fromKey(k: String): PageTag? = values().firstOrNull { it.key == k }
    }
}

/** بيانات صفحة واحدة: اسمها، تعليقك عليها، ووسومها. */
data class PageInfo(
    var title: String = "",
    var comment: String = "",
    val tags: MutableSet<PageTag> = linkedSetOf()
) {
    fun isEmpty(): Boolean = title.isBlank() && comment.isBlank() && tags.isEmpty()
}

data class Mark(
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    var color: Int,
    var alpha: Int,
    val brush: Boolean = false
)

data class TextBox(
    val page: Int,
    var x: Float,
    var y: Float,
    var text: String,
    var color: Int = Color.BLACK,
    var size: Float = 18f
)
