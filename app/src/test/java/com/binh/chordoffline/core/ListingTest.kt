package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Trang danh sách mô phỏng cấu trúc thật của hopamviet.vn (kiểm tra 16/10/2026):
 * cột chính div.lg:col-span-8 chứa thẻ a.song-card; cột bên aside.lg:col-span-4 ("Bài hát hot");
 * phân trang ?page=N đánh số từ 0. Tên bài/ca sĩ là dữ liệu giả.
 */
class ListingTest {

    private fun card(slug: String, id: String, title: String, artist: String, hot: Boolean = false) = """
        <a class="song-card group flex" href="https://hopamviet.vn/chord/song/$slug/$id.html">
          <div class="w-10 h-10"><span class="text-sm font-black">1</span></div>
          <div class="flex-1 min-w-0">
            <div class="flex items-center pr-2"><div class="font-bold text-[15px] truncate">$title</div></div>
            <div class="text-[13px] text-[#4A4A4A] truncate">${if (hot) "<span class=\"uppercase\">HOT</span>" else ""}<span class="truncate">$artist</span></div>
            <div class="text-[12px] italic">♪ 1. lời giả...</div>
          </div>
          <div class="shrink-0"><div class="text-[12px]">29.4K</div></div>
        </a>"""

    private fun page(n: Int, mainCards: String, extraPagination: String = "") = """
        <html><head><title>Nhạc Trẻ</title></head><body>
        <header><nav><a href="/chord/song/menu-link/MENU1.html">menu</a></nav></header>
        <div class="grid grid-cols-1 lg:grid-cols-12">
          <div class="lg:col-span-8 min-w-0">
            $mainCards
            <a href="https://hopamviet.vn/chord/song/bai-a/AAA1.html?ref=x">Bài A lặp lại với query</a>
            <a href="/chord/printsong/bai-a/AAA1.html">In</a>
            <a href="https://example.com/chord/song/x/Y.html">Ngoài site</a>
            <ul class="pagination">
              <li><a href="https://hopamviet.vn/chord/category/3/nhac-tre">1</a></li>
              <li><a href="https://hopamviet.vn/chord/category/3/nhac-tre?page=${n + 1}">${n + 2}</a></li>
              <li><a href="https://hopamviet.vn/chord/category/3/nhac-tre?page=${n + 2}">${n + 3}</a></li>
              <li><a href="https://hopamviet.vn/chord/composer/1/khac?page=${n + 1}">trang danh sách khác</a></li>
              $extraPagination
            </ul>
          </div>
          <aside class="lg:col-span-4 space-y-4">
            <h3>Bài hát hot</h3>
            ${card("bai-hot", "HOT1", "Bài hot", "Ca sĩ hot", hot = true)}
            ${card("bai-a", "AAA1", "Bài A", "Ca sĩ A")}
          </aside>
        </div>
        <footer><a href="/chord/song/footer/FOOT1.html">f</a></footer>
        </body></html>"""

    private val p0 = page(0, card("bai-a", "AAA1", "Bài A", "Ca sĩ A", hot = true) + card("bai-b", "BBB2", "Bài B", "Ca sĩ B, Ca sĩ C"))

    @Test fun extractsOnlyMainColumnSongs() {
        val r = ListingParser.parse(p0, "https://hopamviet.vn/chord/category/3/nhac-tre.html")
        assertEquals("main", r.method)
        assertEquals(listOf("https://hopamviet.vn/chord/song/bai-a/AAA1.html", "https://hopamviet.vn/chord/song/bai-b/BBB2.html"), r.songs.map { it.url })
        assertEquals(listOf("Bài A", "Bài B"), r.songs.map { it.title })
        assertEquals(listOf("Ca sĩ A", "Ca sĩ B, Ca sĩ C"), r.songs.map { it.artist })   // không lấy nhãn HOT
        assertTrue("phải loại link ở aside/nav/footer", r.excluded >= 4)
    }

    @Test fun nextPageIsSameListPagePlusOne() {
        assertEquals("https://hopamviet.vn/chord/category/3/nhac-tre?page=1",
            ListingParser.parse(p0, "https://hopamviet.vn/chord/category/3/nhac-tre.html").nextUrl)
        val p3 = page(3, card("bai-c", "CCC3", "Bài C", "X"))
        assertEquals("https://hopamviet.vn/chord/category/3/nhac-tre?page=4",
            ListingParser.parse(p3, "https://hopamviet.vn/chord/category/3/nhac-tre?page=3").nextUrl)
    }

    @Test fun noNextWhenLastPage() {
        val last = """<div class="lg:col-span-8">${card("x", "X1", "X", "Y")}
            <a href="https://hopamviet.vn/chord/category/3/nhac-tre?page=4">5</a></div>"""
        assertNull(ListingParser.parse(last, "https://hopamviet.vn/chord/category/3/nhac-tre?page=5").nextUrl)
    }

    @Test fun fallbackWithoutMainColumnStillExcludesSidebar() {
        val html = """<body>${card("a", "A1", "A", "B")}<aside>${card("h", "H1", "Hot", "Z")}</aside></body>"""
        val r = ListingParser.parse(html, "https://hopamviet.vn/chord/hot")
        assertEquals("fallback", r.method)
        assertEquals(listOf("https://hopamviet.vn/chord/song/a/A1.html"), r.songs.map { it.url })
    }

    @Test fun policyAllowsListingsRejectsSearchAndOthers() {
        assertNull(ListingPolicy.validate("https://hopamviet.vn/chord/category/3/nhac-tre.html"))
        assertNull(ListingPolicy.validate("hopamviet.vn/chord/composer/7579/jack-j97.html"))
        assertNull(ListingPolicy.validate("https://hopamviet.vn/chord/singer/1669/jack-j97"))
        assertNull(ListingPolicy.validate("https://hopamviet.vn/chord/rhythm/1/bolero.html?page=2"))
        assertTrue(ListingPolicy.validate("https://hopamviet.vn/chord/search?q=abc")!!.contains("robots.txt"))
        assertNotNull(ListingPolicy.validate("https://hopamviet.vn/chord/song/a/B.html"))
        assertNotNull(ListingPolicy.validate("https://hopamviet.vn/user/1/x"))
        assertNotNull(ListingPolicy.validate("https://example.com/chord/category/1/x"))
    }

    @Test fun pageKeysTreatHtmlAndPage0AsSame() {
        assertEquals(ListingPolicy.pageKey("https://hopamviet.vn/chord/category/3/nhac-tre.html"),
            ListingPolicy.pageKey("https://www.hopamviet.vn/chord/category/3/nhac-tre?page=0"))
        assertNotEquals(ListingPolicy.pageKey("https://hopamviet.vn/chord/category/3/nhac-tre?page=1"),
            ListingPolicy.pageKey("https://hopamviet.vn/chord/category/3/nhac-tre?page=2"))
        assertEquals(2, ListingPolicy.pageIndex("https://hopamviet.vn/x?a=1&page=2"))
        assertEquals(0, ListingPolicy.pageIndex("https://hopamviet.vn/x"))
    }

    @Test fun discoveryDedupesLimitsAndAvoidsLoops() {
        val d = Discovery(maxPages = 3)
        val u0 = "https://hopamviet.vn/chord/category/3/nhac-tre.html"
        val r0 = ListingParser.parse(p0, u0)
        assertEquals(2, d.add(u0, r0))
        assertTrue(d.shouldContinue(r0.nextUrl, 2))
        // trang 1 lặp lại đúng bài của trang 0 -> không có bài mới -> dừng
        val u1 = r0.nextUrl!!
        val r1 = ListingParser.parse(page(1, card("bai-a", "AAA1", "Bài A", "x")), u1)
        assertEquals(0, d.add(u1, r1))
        assertFalse(d.shouldContinue(r1.nextUrl, 0))
        assertTrue(d.stopReason!!.contains("không có bài mới"))
        assertNull("dừng vì lặp thì không cho quét tiếp", d.resumeUrl)

        // trang kế tiếp trỏ về trang đã quét -> dừng (vòng lặp)
        val d2 = Discovery(maxPages = 10)
        d2.add(u0, r0)
        assertFalse(d2.shouldContinue("https://hopamviet.vn/chord/category/3/nhac-tre?page=0", 2))
        assertTrue(d2.stopReason!!.contains("vòng lặp"))

        // đủ số trang
        val d3 = Discovery(maxPages = 1)
        d3.add(u0, r0)
        assertFalse(d3.shouldContinue(r0.nextUrl, 2))
        assertTrue(d3.stopReason!!.contains("1 trang"))
        assertEquals("đủ số trang -> nhớ trang kế để Quét tiếp", r0.nextUrl, d3.resumeUrl)
        assertFalse(d3.reachedEnd)

        // giới hạn số bài
        val d4 = Discovery(maxPages = 5, maxSongs = 1)
        assertEquals(1, d4.add(u0, r0)); assertEquals(1, d4.found.size)
        assertFalse(d4.shouldContinue(r0.nextUrl, 1))

        // hết trang
        val d5 = Discovery(3)
        assertFalse(d5.shouldContinue(null, 5))
        assertTrue(d5.reachedEnd); assertNull(d5.resumeUrl)
        assertTrue(d5.stopReason!!.contains("hết trang"))
    }

    @Test fun songUrlNormalization() {
        assertEquals("https://hopamviet.vn/chord/song/a/B1.html", ListingParser.songUrl("/chord/song/a/B1.html?x=1#y", "https://hopamviet.vn/chord/hot"))
        assertEquals("https://hopamviet.vn/chord/song/a/B1.html", ListingParser.songUrl("http://www.hopamviet.vn/chord/song/a/B1.html", "https://hopamviet.vn/"))
        assertNull(ListingParser.songUrl("/chord/song/a", "https://hopamviet.vn/"))
        assertNull(ListingParser.songUrl("/chord/printsong/a/B1.html", "https://hopamviet.vn/"))
    }

    // Thanh phân trang thật: 1, 2, 3, …, 599, › (trang cuối = ?page=598)
    private fun pager(cur: Int, vararg pages: Int) = """<div class="lg:col-span-8">${card("b$cur", "S$cur", "Bài $cur", "X")}
        <nav aria-label="pagination">${pages.joinToString("") { """<a href="https://hopamviet.vn/chord/category/2/nhac-tru-tinh?page=$it">${it + 1}</a>""" }}
        <a href="https://hopamviet.vn/chord/category/2/nhac-tru-tinh">1</a></nav></div>"""

    @Test fun lastPageReadFromPagination() {
        val r = ListingParser.parse(pager(0, 1, 2, 598), "https://hopamviet.vn/chord/category/2/nhac-tru-tinh.html")
        assertEquals(599, r.lastPage)
        assertEquals("https://hopamviet.vn/chord/category/2/nhac-tru-tinh?page=1", r.nextUrl)
    }

    @Test fun nextPageBuiltWhenPaginatorSkipsNeighbour() {
        // đang ở trang 21 (page=20), thanh phân trang chỉ có 1 … 599 -> tự tạo ?page=21
        val r = ListingParser.parse(pager(20, 0, 598), "https://hopamviet.vn/chord/category/2/nhac-tru-tinh?page=20")
        assertEquals("https://hopamviet.vn/chord/category/2/nhac-tru-tinh?page=21", r.nextUrl)
    }

    @Test fun lastPageHasNoNext() {
        val r = ListingParser.parse(pager(598, 0, 596, 597), "https://hopamviet.vn/chord/category/2/nhac-tru-tinh?page=598")
        assertEquals(599, r.lastPage)
        assertNull("trang 599/599 -> hết trang", r.nextUrl)
    }

    @Test fun withPageUsesSiteFormat() {
        assertEquals("https://hopamviet.vn/chord/category/2/nhac-tru-tinh?page=20",
            ListingPolicy.withPage("http://www.hopamviet.vn/chord/category/2/nhac-tru-tinh.html", 20))
        assertEquals("https://hopamviet.vn/chord/category/2/nhac-tru-tinh",
            ListingPolicy.withPage("https://hopamviet.vn/chord/category/2/nhac-tru-tinh?page=5", 0))
    }
}
