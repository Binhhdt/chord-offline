package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test

class ScanProgressTest {
    private val base = "https://hopamviet.vn/chord/category/2/nhac-tru-tinh"

    @Test fun recordsFurthestPageAndResumePoint() {
        var b = ScanProgressBook()
        b = b.record("$base.html", 1, 599, "$base?page=1", false, 10)
        b = b.record("$base?page=19", 20, 599, "$base?page=20", false, 20)
        val p = b.get("https://www.hopamviet.vn/chord/category/2/nhac-tru-tinh?page=7")!!   // cùng danh sách, khác trang
        assertEquals(20, p.lastScanned); assertEquals(599, p.totalPages)
        assertEquals(21, p.resumePage); assertFalse(p.reachedEnd)
        assertEquals("category/2/nhac-tru-tinh", p.label)
        // quét lại từ đầu không làm mất tiến độ xa nhất
        b = b.record("$base.html", 1, 599, "$base?page=1", false, 30)
        assertEquals(20, b.get(base)!!.lastScanned)
        assertEquals(21, b.get(base)!!.resumePage)
    }

    @Test fun reachingEndClearsResume() {
        val b = ScanProgressBook().record("$base?page=598", 599, 599, null, true, 5)
        val p = b.get(base)!!
        assertTrue(p.reachedEnd); assertNull(p.resumeUrl); assertEquals(599, p.lastScanned)
    }

    @Test fun separateListsAndRoundTrip() {
        val b = ScanProgressBook()
            .record("$base.html", 3, 599, "$base?page=3", false, 1)
            .record("https://hopamviet.vn/chord/category/3/nhac-tre.html", 5, 636, "https://hopamviet.vn/chord/category/3/nhac-tre?page=5", false, 2)
        assertEquals(2, b.all.size)
        assertEquals("category/3/nhac-tre", b.all.first().label)   // mới nhất trước
        assertEquals(b, ScanProgressBook.parse(b.serialize()))
        assertEquals(ScanProgressBook(), ScanProgressBook.parse("rác\n\n#x"))
    }

    @Test fun mergeKeepsFurthest() {
        val local = ScanProgressBook().record("$base.html", 5, 599, "$base?page=5", false, 100)
        val backup = ScanProgressBook().record("$base.html", 40, 599, "$base?page=40", false, 50)
        assertEquals(40, local.merge(backup).get(base)!!.lastScanned)
        assertEquals(40, backup.merge(local).get(base)!!.lastScanned)
        assertEquals(41, local.merge(backup).get(base)!!.resumePage)
    }
}
