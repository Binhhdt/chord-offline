package com.binh.chordoffline.core

/** Cách hiển thị hợp âm do người dùng chọn (áp dụng cho mọi bài, chỉ đổi hiển thị). */
data class ChordStyle(
    val color: Long = ChordColors.DEFAULT,
    /** Tô nền mờ phía sau hợp âm để dễ nhìn hơn khi đang đàn. */
    val highlight: Boolean = false,
)

/** Bảng màu hợp âm cho nền sáng của giao diện "Tối giản xanh" + kiểm tra độ tương phản. */
object ChordColors {
    data class Named(val name: String, val argb: Long)

    const val DEFAULT = 0xFF2B3CC2L           // xanh chàm
    const val BACKGROUND = 0xFFFFFFFFL        // nền màn hình xem bài (trắng)

    val PALETTE = listOf(
        Named("Xanh chàm", 0xFF2B3CC2L),
        Named("Xanh dương", 0xFF1565C0L),
        Named("Xanh ngọc", 0xFF00796BL),
        Named("Xanh lá", 0xFF2E7D32L),
        Named("Cam đất", 0xFFB45309L),
        Named("Đỏ", 0xFFC62828L),
        Named("Hồng", 0xFFAD1457L),
        Named("Tím", 0xFF6A1B9AL),
        Named("Nâu", 0xFF6D4C41L),
        Named("Đen", 0xFF151826L),
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

    /** Đủ rõ trên nền: tối thiểu 4.5:1 (chuẩn chữ thường WCAG AA). */
    fun readable(argb: Long, background: Long = BACKGROUND) = contrast(argb, background) >= 4.5

    /** Màu đã lưu từ giao diện cũ (nền tối) có thể không đọc được trên nền sáng -> dùng màu mặc định. */
    fun sanitize(argb: Long): Long = if (readable(argb)) argb else DEFAULT
}
