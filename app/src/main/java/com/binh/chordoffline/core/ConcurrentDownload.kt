package com.binh.chordoffline.core

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * Cấu hình tải song song CÓ KIỂM SOÁT. Thay cho kiểu cũ "tải 1 → nghỉ → tải 2 → nghỉ".
 *  - MAX_WORKERS: số luồng (worker) chạy cùng lúc — chọn 1/2/3, mặc định 3 (phương án B), không bao giờ vượt HARD_MAX_WORKERS.
 *  - DELAY: nghỉ THÊM sau mỗi request của một worker (mặc định 0 — không bắt buộc sleep).
 *  - MAX_RETRIES: số lần thử lại khi lỗi mạng / 429 / 502 / 503 / 504 (backoff luỹ thừa).
 *  - MAX_REQUESTS_PER_SECOND: trần tổng số request/giây của cả pool → không thể dồn dập dù nhiều worker.
 */
object DownloadConfig {
    const val MAX_WORKERS = 3
    val WORKER_OPTIONS = listOf(1, 2, 3)
    const val HARD_MAX_WORKERS = 3
    const val DELAY = 0L
    val DELAY_OPTIONS = listOf(0L, 500L, 1_000L, 2_000L)
    const val MAX_RETRIES = 3
    const val MAX_REQUESTS_PER_SECOND = 3.0

    fun clampWorkers(n: Int) = n.coerceIn(1, HARD_MAX_WORKERS)
    fun clampDelay(ms: Long) = ms.coerceIn(0L, 10_000L)
    val minIntervalMs: Long get() = (1000.0 / MAX_REQUESTS_PER_SECOND).toLong()
}

/** Phân loại phản hồi để quyết định: lưu / thử lại & giảm tốc / dừng hẳn / lỗi thường. */
/** SKIPPED: bài đã có trong máy, không gửi request. */
enum class ResponseClass { OK, THROTTLE, BLOCKED, FAIL, SKIPPED }

object ResponsePolicy {
    private val THROTTLE_CODES = setOf(429, 502, 503, 504)

    fun classify(status: Int?, body: String?, networkError: Boolean): ResponseClass {
        if (networkError) return ResponseClass.THROTTLE            // lỗi mạng tạm thời: thử lại có backoff
        if (status in THROTTLE_CODES) return ResponseClass.THROTTLE
        if (status == 403) return ResponseClass.BLOCKED
        val lower = body?.take(20_000)?.lowercase()
        if (lower != null && listOf("cf-chl", "challenge-platform", "captcha", "just a moment", "attention required").any { it in lower })
            return ResponseClass.BLOCKED                               // trang xác minh: KHÔNG vượt qua, dừng
        return if (status != null && status in 200..299) ResponseClass.OK else ResponseClass.FAIL
    }
}

/**
 * Giới hạn tốc độ toàn cục (một "khe" mỗi [intervalMs]). Mọi worker xin khe trước khi gửi request,
 * nên tổng request/giây không vượt trần dù có bao nhiêu worker. Gọi trong vùng đồng bộ (Mutex).
 */
class RateGate(var intervalMs: Long) {
    private var next = 0L
    /** Đặt chỗ một khe; trả về số ms phải chờ trước khi gửi. */
    fun reserve(now: Long): Long {
        val slot = maxOf(now, next)
        next = slot + intervalMs
        return slot - now
    }

    /** Server bảo chờ (Retry-After) → không cấp khe nào cho cả pool trước thời điểm [untilMs]. */
    fun holdUntil(untilMs: Long) { next = maxOf(next, untilMs) }
}

/** Đọc header Retry-After: số giây ("120") hoặc ngày giờ HTTP ("Wed, 21 Oct 2026 07:28:00 GMT"). Giới hạn 0..300 giây. */
object RetryAfter {
    fun parseSeconds(header: String?, nowMs: Long): Long? {
        val h = header?.trim()?.ifEmpty { null } ?: return null
        h.toLongOrNull()?.let { return it.coerceIn(0, 300) }
        return runCatching {
            val t = java.time.ZonedDateTime.parse(h, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            ((t - nowMs + 999) / 1000).coerceIn(0, 300)
        }.getOrNull()
    }
}

/**
 * Tự điều chỉnh số worker (AIMD): gặp 429/502/503/504 → giảm một nửa (tối thiểu 1) và giãn khoảng cách request;
 * phản hồi bình thường liên tục [recoverAfter] lần và đã qua [cooldownMs] kể từ lần bị giới hạn → tăng lại 1 worker,
 * tối đa bằng số người dùng chọn. Phản hồi bình thường thì giữ nguyên.
 */
class AdaptiveConcurrency(val max: Int, private val recoverAfter: Int = 20, private val cooldownMs: Long = 30_000) {
    var limit: Int = max; private set
    var throttleCount = 0; private set
    private var okStreak = 0
    private var lastThrottleAt = Long.MIN_VALUE / 2

    fun onThrottle(now: Long): Int {
        limit = maxOf(1, limit / 2)
        okStreak = 0
        lastThrottleAt = now
        throttleCount++
        return limit
    }

    fun onSuccess(now: Long): Int {
        okStreak++
        if (limit < max && okStreak >= recoverAfter && now - lastThrottleAt >= cooldownMs) {
            limit++
            okStreak = 0
        }
        return limit
    }

    val reduced: Boolean get() = limit < max
}

/** Backoff luỹ thừa: 1 s, 2 s, 4 s, … (tối đa 60 s), tôn trọng Retry-After của server, có jitter để các worker không dội lại cùng lúc. */
object Backoff {
    fun delayMs(attempt: Int, retryAfterSec: Long? = null, baseMs: Long = 1_000, capMs: Long = 60_000, jitter: Double = 0.0): Long {
        val exp = (baseMs * 2.0.pow((attempt - 1).coerceAtLeast(0))).toLong()
        val withServer = maxOf(exp, (retryAfterSec ?: 0) * 1000)
        val jittered = withServer + (withServer * jitter.coerceIn(0.0, 0.5)).toLong()
        return min(jittered, capMs)
    }
}

/**
 * Đo tốc độ thực tế (bài/giây) trong cửa sổ trượt [windowMs] (mặc định 60 s) — ETA dựa trên tốc độ gần nhất,
 * không dùng thời gian cố định. Khi mới chạy (< 5 s dữ liệu) chưa ước tính để tránh số ảo.
 */
class ThroughputMeter(private val windowMs: Long = 60_000, private val minSpanMs: Long = 5_000) {
    private val events = ArrayDeque<Long>()
    private var startedAt: Long? = null

    fun start(now: Long) { if (startedAt == null) startedAt = now }
    fun reset() { events.clear(); startedAt = null }

    fun record(now: Long) {
        start(now)
        events.addLast(now)
        trim(now)
    }

    private fun trim(now: Long) { while (events.isNotEmpty() && events.first() < now - windowMs) events.removeFirst() }

    /** Số bài/giây trong cửa sổ gần nhất; null nếu chưa đủ dữ liệu. */
    fun rate(now: Long): Double? {
        trim(now)
        val start = startedAt ?: return null
        val span = minOf(now - start, windowMs)
        if (span < minSpanMs || events.isEmpty()) return null
        return events.size * 1000.0 / span
    }

    fun etaSeconds(left: Int, now: Long): Long? {
        if (left <= 0) return 0
        val r = rate(now) ?: return null
        return if (r <= 0.0) null else ceil(left / r).toLong()
    }

    companion object {
        /** 185 -> "3 phút 05 giây"; 3725 -> "1 giờ 02 phút". */
        fun formatEta(sec: Long?): String {
            if (sec == null) return "đang tính…"
            val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
            return when {
                h > 0 -> "$h giờ %02d phút".format(m)
                m > 0 -> "$m phút %02d giây".format(s)
                else -> "$s giây"
            }
        }

        fun formatRate(r: Double?): String = r?.let { "%.1f bài/giây".format(it).replace('.', ',') } ?: "đang đo…"
    }
}
