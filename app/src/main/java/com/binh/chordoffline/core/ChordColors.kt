package com.binh.chordoffline.core

/** Cách hiển thị hợp âm do người dùng chọn (áp dụng cho mọi bài, chỉ đổi hiển thị). */
data class ChordStyle(
    val color: Long = ChordColors.DEFAULT,
    /** Tô nền mờ phía sau hợp âm để dễ nhìn hơn khi đang đàn. */
    val highlight: Boolean = false,
)

/** Bảng màu hợp âm cho nền tối của giao diện "Sân khấu đêm" + kiểm tra độ tương phản. */
object ChordColors {
    data class Named(val name: String, val argb: Long)

    const val DEFAULT = 0xFFF2A33AL           // hổ phách
    const val BACKGROUND = 0xFF15171CL        // nền màn hình xem bài

    val PALETTE = listOf(
        Named("Hổ phách", 0xFFF2A33AL),
        Named("Vàng chanh", 0xFFE8D44DL),
        Named("Cam đào", 0xFFFF9A6BL),
        Named("Đỏ san hô", 0xFFFF6B6BL),
        Named("Hồng", 0xFFFF7AA8L),
        Named("Tím", 0xFFB69CFFL),
        Named("Xanh dương", 0xFF6CA8FFL),
        Named("Xanh ngọc", 0xFF4FD1C5L),
        Named("Xanh lá", 0xFF7BD88FL),
        Named("Trắng", 0xFFF5F5F5L),
    )

    /** "#4FD1C5", "4fd1c5", "#4FC" -> 0xFF4FD1C5; null nếu không hợp lệ. */
    fun parseHex(s: String): Long? {
        var h = s.trim().removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length != 6 || h.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return 0xFF000000L or h.toLong(16)
    }

    fun toHex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

    private fun channel(c: Long, shift: Int): Double {
        val v = ((c shr shift) and 0xFF) / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    /** Độ sáng tương đối theo WCAG. */
    fun luminance(argb: Long) = 0.2126 * channel(argb, 16) + 0.7152 * channel(argb, 8) + 0.0722 * channel(argb, 0)

    fun contrast(a: Long, b: Long): Double {
        val (l1, l2) = luminance(a) to luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** Đủ rõ trên nền tối: tối thiểu 4.5:1 (chuẩn chữ thường WCAG AA). */
    fun readable(argb: Long, background: Long = BACKGROUND) = contrast(argb, background) >= 4.5
}
