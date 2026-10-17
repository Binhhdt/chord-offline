package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test

class ConcurrentDownloadTest {

    @Test fun configDefaultsAndLimits() {
        assertEquals(3, DownloadConfig.MAX_WORKERS)
        assertEquals(0L, DownloadConfig.DELAY)
        assertEquals(3, DownloadConfig.MAX_RETRIES)
        assertEquals(listOf(1, 2, 3), DownloadConfig.WORKER_OPTIONS)
        assertEquals(3, DownloadConfig.clampWorkers(50))      // không bao giờ 50/100 luồng
        assertEquals(3, DownloadConfig.clampWorkers(100))
        assertEquals(1, DownloadConfig.clampWorkers(0))
        assertTrue(DownloadConfig.WORKER_OPTIONS.all { it <= DownloadConfig.HARD_MAX_WORKERS })
        assertEquals(333L, DownloadConfig.minIntervalMs)   // tối đa 3 request/giây
    }

    @Test fun classifyResponses() {
        assertEquals(ResponseClass.OK, ResponsePolicy.classify(200, "<div id=lyricBox>", false))
        assertEquals(ResponseClass.THROTTLE, ResponsePolicy.classify(429, "", false))
        assertEquals(ResponseClass.THROTTLE, ResponsePolicy.classify(502, null, false))
        assertEquals(ResponseClass.THROTTLE, ResponsePolicy.classify(503, null, false))
        assertEquals(ResponseClass.THROTTLE, ResponsePolicy.classify(null, null, true))
        assertEquals(ResponseClass.BLOCKED, ResponsePolicy.classify(403, null, false))
        assertEquals(ResponseClass.BLOCKED, ResponsePolicy.classify(200, "<title>Just a moment...</title>", false))
        assertEquals(ResponseClass.FAIL, ResponsePolicy.classify(404, "", false))
    }

    @Test fun rateGateCapsRequestsPerSecondAcrossWorkers() {
        val g = RateGate(DownloadConfig.minIntervalMs)
        // 3 worker cùng xin khe ở t=0 (và xin tiếp) -> các khe cách nhau 333 ms => tối đa 3 request/giây
        val waits = (1..9).map { g.reserve(0) }
        assertEquals((0 until 9).map { it * 333L }, waits)
        assertTrue("9 request phải trải ra ≥ 2.6 giây", waits.last() >= 2_600)
        // sau khi rảnh lâu, khe mới bắt đầu ngay
        assertEquals(0L, g.reserve(10_000))
    }

    @Test fun adaptiveConcurrencyHalvesOnThrottleAndRecoversSlowly() {
        val c = AdaptiveConcurrency(max = 3, recoverAfter = 3, cooldownMs = 1_000)
        assertEquals(3, c.limit)
        assertEquals(3, c.onSuccess(0))          // bình thường: giữ nguyên
        assertEquals(1, c.onThrottle(100))       // 3/2 = 1
        assertEquals(1, c.onThrottle(200))       // không xuống dưới 1
        c.onThrottle(300); c.onThrottle(400)
        assertTrue(c.reduced)
        // chưa qua thời gian hồi -> chưa tăng
        repeat(5) { c.onSuccess(500) }
        assertEquals(1, c.limit)
        // đủ 3 lần thành công liên tiếp sau thời gian hồi -> +1 mỗi đợt
        repeat(3) { c.onSuccess(2_000) }
        assertEquals(2, c.limit)
        repeat(30) { c.onSuccess(5_000) }
        assertEquals(3, c.limit)                 // không vượt số worker người dùng chọn
        assertFalse(c.reduced)
        assertEquals(4, c.throttleCount)
    }

    @Test fun exponentialBackoffWithRetryAfter() {
        assertEquals(1_000L, Backoff.delayMs(1))
        assertEquals(2_000L, Backoff.delayMs(2))
        assertEquals(4_000L, Backoff.delayMs(3))
        assertEquals(60_000L, Backoff.delayMs(10))
        assertEquals(30_000L, Backoff.delayMs(1, retryAfterSec = 30))   // tôn trọng Retry-After
        assertEquals(1_250L, Backoff.delayMs(1, jitter = 0.25))
    }

    @Test fun throughputUsesRecentWindowOnly() {
        val m = ThroughputMeter(windowMs = 60_000, minSpanMs = 5_000)
        m.start(0)
        assertNull("chưa đủ dữ liệu", m.rate(1_000))
        // 0–60 s: 2 bài/giây
        for (t in 0 until 60_000 step 500) m.record(t.toLong())
        assertEquals(2.0, m.rate(60_000)!!, 0.05)
        // 60–120 s: tăng lên 5 bài/giây -> cửa sổ 60 s gần nhất phản ánh tốc độ mới
        for (t in 60_000 until 120_000 step 200) m.record(t.toLong())
        assertEquals(5.0, m.rate(120_000)!!, 0.05)
        assertEquals(177L, m.etaSeconds(885, 120_000))          // 885 bài / 5 bài/giây
        assertEquals(0L, m.etaSeconds(0, 120_000))
    }

    @Test fun formatting() {
        assertEquals("3 phút 05 giây", ThroughputMeter.formatEta(185))
        assertEquals("45 giây", ThroughputMeter.formatEta(45))
        assertEquals("1 giờ 02 phút", ThroughputMeter.formatEta(3725))
        assertEquals("đang tính…", ThroughputMeter.formatEta(null))
        assertEquals("4,8 bài/giây", ThroughputMeter.formatRate(4.8))
    }

    @Test fun rateGateHoldsForRetryAfter() {
        val g = RateGate(333)
        assertEquals(0L, g.reserve(0))
        g.holdUntil(5_000)                       // server bảo chờ 5 s
        assertEquals(4_000L, g.reserve(1_000))   // không ai được gửi trước t=5000
        assertEquals(4_333L, g.reserve(1_000))
    }

    @Test fun retryAfterHeaderParsing() {
        assertEquals(120L, RetryAfter.parseSeconds("120", 0))
        assertEquals(300L, RetryAfter.parseSeconds("99999", 0))   // giới hạn 5 phút
        assertNull(RetryAfter.parseSeconds(null, 0))
        assertNull(RetryAfter.parseSeconds("abc", 0))
        val now = java.time.ZonedDateTime.parse("Wed, 21 Oct 2026 07:28:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli() - 30_000
        assertEquals(30L, RetryAfter.parseSeconds("Wed, 21 Oct 2026 07:28:00 GMT", now))
    }

    private fun url(i: Int) = "https://hopamviet.vn/chord/song/bai-$i/ABC${i}XYZ.html"

    @Test fun parallelWorkersNeverClaimTheSameItem() {
        var q = BatchQueue().add((1..300).joinToString("\n") { url(it) }).first
        assertEquals(300, q.total)
        val lock = Any()
        val claimed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = (1..3).map {
            Thread {
                while (true) {
                    val item = synchronized(lock) { val (nq, it2) = q.claimNext(null); q = nq; it2 } ?: break
                    claimed += item.url
                    synchronized(lock) { q = q.update(item.url) { x -> x.copy(state = ItemState.DONE) } }
                }
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(300, claimed.size)
        assertEquals(300, claimed.toSet().size)          // không bài nào bị tải 2 lần
        assertEquals(300, q.count(ItemState.DONE))
        assertTrue(q.items.all { it.attempts == 1 })
    }

    @Test fun retryOnlyFailedItems() {
        var q = BatchQueue().add((1..5).joinToString("\n") { url(it) }).first
        q = q.update(url(1)) { it.copy(state = ItemState.DONE) }
            .update(url(2)) { it.copy(state = ItemState.ERROR) }
            .update(url(3)) { it.copy(state = ItemState.CANCELLED) }
        // url(4), url(5) vẫn đang chờ (chưa tải) — "Thử lại lỗi" KHÔNG được động tới
        val failed = q.failedUrls()
        assertEquals(setOf(url(2), url(3)), failed)
        q = q.retryFailed()
        assertEquals(2, q.pendingCount(failed))
        assertEquals(4, q.pendingCount(null))
        val (q2, a) = q.claimNext(failed); val (q3, b) = q2.claimNext(failed); val (_, c) = q3.claimNext(failed)
        assertEquals(setOf(url(2), url(3)), setOf(a!!.url, b!!.url))
        assertNull(c)                                    // hết mục lỗi -> dừng, không tải url(4), url(5)
    }
}
