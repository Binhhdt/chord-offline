package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test

/** Trang "Hợp âm mới nhất" giả lập theo cấu trúc trang danh sách thật (cột chính lg:col-span-8, ?page=N từ 0). */
class NewSongCheckTest {
    private fun url(i: Int) = "https://hopamviet.vn/chord/song/bai-$i/ID$i.html"
    private fun card(i: Int) = """<a class="song-card" href="${url(i)}"><div class="font-bold">Bài $i</div><div><span class="truncate">Ca sĩ $i</span></div></a>"""
    private fun page(n: Int, ids: IntRange, last: Int = 4) = """<html><body><div class="lg:col-span-8">
        ${ids.joinToString("") { card(it) }}
        <ul class="pagination">${(0..last).joinToString("") { p -> "<li><a href=\"https://hopamviet.vn/chord/latest${if (p > 0) "?page=$p" else ""}\">${p + 1}</a></li>" }}</ul>
        </div><aside class="lg:col-span-4">${card(999)}</aside></body></html>"""

    /** 5 trang, mỗi trang 30 bài: trang 0 = bài 150..121 (mới nhất), trang 4 = bài 30..1. */
    private fun site(): Map<String, String> = (0..4).associate { p ->
        val hi = 150 - p * 30
        (NewSongCheck.URL + if (p > 0) "?page=$p" else "") to page(p, hi downTo hi - 29 step 1)
    }.mapValues { (_, v) -> v }

    private fun page(n: Int, ids: IntProgression) = page(n, IntRange(0, -1)).replace(
        "<div class=\"lg:col-span-8\">", "<div class=\"lg:col-span-8\">" + ids.joinToString("") { card(it) })

    private fun runWith(known: Set<Int>, pages: Map<String, String> = site(), fetched: MutableList<String> = mutableListOf()): NewSongCheck.Result {
        var pauses = 0
        val r = NewSongCheck.run(
            fetch = { u -> fetched += u; pages[u]?.let { NewSongCheck.Fetched(200, it, null) } ?: NewSongCheck.Fetched(404, "", null) },
            isKnown = { u -> known.any { url(it) == u } },
            pause = { pauses++ }, now = { 42L })
        assertEquals("nghỉ giữa các trang", maxOf(0, fetched.size - 1), pauses)
        return r
    }

    @Test fun stopsOnFirstPageWhenItReachesKnownSongs() {
        val fetched = mutableListOf<String>()
        val r = runWith(known = (1..140).toSet(), fetched = fetched)
        assertEquals(listOf(NewSongCheck.URL), fetched)            // chỉ 1 request
        assertEquals((150 downTo 141).map { url(it) }, r.newSongs.map { it.url })
        assertTrue(r.reachedKnown); assertFalse(r.maybeGap); assertNull(r.error)
        assertFalse("không lấy bài ở cột bên", r.newSongs.any { it.url == url(999) })
    }

    @Test fun readsMorePagesWhenFirstPageIsAllNew() {
        val fetched = mutableListOf<String>()
        val r = runWith(known = (1..100).toSet(), fetched = fetched)
        assertEquals(2, fetched.size)
        assertEquals(50, r.newSongs.size)
        assertTrue(r.reachedKnown)
    }

    @Test fun nothingNew() {
        val r = runWith(known = (1..150).toSet())
        assertTrue(r.newSongs.isEmpty()); assertTrue(r.reachedKnown); assertEquals(1, r.pagesRead)
    }

    @Test fun allNewMeansPossibleGapAndStopsAtMaxPages() {
        val fetched = mutableListOf<String>()
        val r = runWith(known = emptySet(), fetched = fetched)
        assertEquals(5, fetched.size)
        assertEquals(150, r.newSongs.size)
        assertTrue(r.maybeGap); assertFalse(r.reachedKnown)
    }

    @Test fun blockedOrErrorStopsWithoutRetrying() {
        val fetched = mutableListOf<String>()
        val r = NewSongCheck.run(fetch = { fetched += it; NewSongCheck.Fetched(429, "", null) }, isKnown = { false }, pause = {})
        assertEquals(1, fetched.size)
        assertTrue(r.error!!.contains("giới hạn"))
        val r2 = NewSongCheck.run(fetch = { NewSongCheck.Fetched(null, null, "UnknownHostException") }, isKnown = { false }, pause = {})
        assertTrue(r2.error!!.contains("Không tải được"))
    }

    @Test fun resultFileRoundTrip() {
        val r = runWith(known = (1..140).toSet())
        val back = NewSongCheck.parse(NewSongCheck.serialize(r))!!
        assertEquals(r.newSongs, back.newSongs)
        assertEquals(r.checkedAt, back.checkedAt); assertEquals(r.reachedKnown, back.reachedKnown)
        assertNull(NewSongCheck.parse("rác"))
    }
}
