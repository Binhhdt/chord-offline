package com.binh.chordoffline.core

/**
 * Khoảng nghỉ giữa các request tới website. Người dùng chọn trong [OPTIONS] (thấp nhất 1 giây, không tải song song);
 * nếu website trả lời chậm thì tự nghỉ lâu hơn (bằng thời gian trả lời vừa rồi, tối đa 10 giây) để không dồn tải.
 */
object PacePolicy {
    val OPTIONS = listOf(3_000L, 2_000L, 1_500L, 1_000L)
    const val DEFAULT = 2_000L
    const val MIN = 1_000L
    const val MAX_ADAPTIVE = 10_000L

    fun clamp(ms: Long): Long = ms.coerceIn(MIN, OPTIONS.max())

    fun effectiveDelay(settingMs: Long, lastResponseMs: Long): Long =
        maxOf(clamp(settingMs), minOf(lastResponseMs, MAX_ADAPTIVE))

    /** Thời gian còn lại ước tính (phút) cho [left] bài: mỗi bài = khoảng nghỉ + ~0.6 s tải. */
    fun etaMinutes(left: Int, settingMs: Long): Long = (left * (clamp(settingMs) + 600L) + 59_999L) / 60_000L

    fun label(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000} giây" else "${"%.1f".format(ms / 1000.0).replace('.', ',')} giây"
}
