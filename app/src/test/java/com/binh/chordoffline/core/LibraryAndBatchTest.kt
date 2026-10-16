package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LibraryAndBatchTest {

    private fun song(key: String, title: String, composer: String?, singer: String?, at: Long) =
        SavedSong(key, title, null, VersionStatus(SaveState.SAVED, at, 1), VersionStatus.NONE, composer, singer, "Am")

    private val songs = listOf(
        song("A", "Người dưng", "JACK - J97", "Jack - J97", 3),
        song("B", "Vì lỡ thương nhau", "Trần Kim Phú, Hoàng Mộng Ngân", "Hồng Trúc, Thu Hường", 1),
        song("C", "Đêm buồn tỉnh lẻ", "Tú Nhi", null, 2),
    )

    @Test fun foldRemovesVietnameseDiacritics() {
        assertEquals("nguoi dung", LibrarySearch.fold("Người  Dưng"))
        assertEquals("dem buon tinh le", LibrarySearch.fold("Đêm buồn tỉnh lẻ"))
        assertEquals("", LibrarySearch.fold(null))
    }

    @Test fun searchByFieldsWithoutAccents() {
        assertEquals(listOf("A"), LibrarySearch.filter(songs, "nguoi dung").map { it.key })
        assertEquals(listOf("B"), LibrarySearch.filter(songs, "hoang mong", SearchField.COMPOSER).map { it.key })
        assertEquals(listOf("B"), LibrarySearch.filter(songs, "thu huong", SearchField.SINGER).map { it.key })
        // "hong truc" chỉ có trong tên ca sĩ, không có trong tên bài
        assertEquals(listOf("B"), LibrarySearch.filter(songs, "hong truc", SearchField.SINGER).map { it.key })
        assertEquals(emptyList<String>(), LibrarySearch.filter(songs, "hong truc", SearchField.TITLE).map { it.key })
        // tìm theo chuỗi con để gõ dở vẫn ra: "thuong" khớp "thương"
        assertEquals(listOf("B"), LibrarySearch.filter(songs, "thuo", SearchField.TITLE).map { it.key })
        assertEquals(listOf("C"), LibrarySearch.filter(songs, "DEM tinh").map { it.key })   // nhiều từ = AND
        assertEquals(3, LibrarySearch.filter(songs, "  ").size)
    }

    @Test fun sortModes() {
        assertEquals(listOf("A", "C", "B"), LibrarySearch.filter(songs, "", sort = SortMode.RECENT).map { it.key })
        assertEquals(listOf("C", "A", "B"), LibrarySearch.filter(songs, "", sort = SortMode.TITLE).map { it.key })
        assertEquals(listOf("A", "B", "C"), LibrarySearch.filter(songs, "", sort = SortMode.COMPOSER).map { it.key })
        assertEquals(listOf("B", "A", "C"), LibrarySearch.filter(songs, "", sort = SortMode.SINGER).map { it.key }) // thiếu ca sĩ xếp cuối
    }

    @Test fun storeProvidesSearchInfoIncludingLegacyFiles() {
        val dir = Files.createTempDirectory("lib").toFile()
        try {
            val st = SongStore(dir)
            val p = ParsedSong("https://hopamviet.vn/chord/song/a/K1.html", "K1", "1", "Bài một", listOf("Nhạc Sĩ"),
                null, null, "C", listOf("Ca Sĩ" to "D"), emptyList(), null, "[C] lời")
            st.saveCompact("K1", p, ChordText.toChordPro(p, "t"))
            val s = st.list().single()
            assertEquals("Nhạc Sĩ", s.composers); assertEquals("Ca Sĩ", s.singers); assertEquals("C", s.tone)
            // giả lập bài lưu bằng bản cũ: info.properties không có composer/singers
            java.io.File(dir, "K1/info.properties").writeText("title=Bài một\n")
            val legacy = st.list().single()
            assertEquals("Nhạc Sĩ", legacy.composers); assertEquals("Ca Sĩ", legacy.singers)
            // lưu nguyên trang kèm thông tin bài
            st.savePage("K2", "Bài hai", "<html></html>", FetchMeta("u", "u", 200, "text/html", 0), p.copy(title = "Bài hai"))
            assertEquals("Nhạc Sĩ", st.list().first { it.key == "K2" }.composers)
        } finally { dir.deleteRecursively() }
    }

    // ---------- Hàng đợi tải nhiều ----------

    @Test fun addDedupesAndValidates() {
        val text = """
            https://hopamviet.vn/chord/song/a/AAA.html
            https://hopamviet.vn/chord/song/a/AAA.html
            http://www.hopamviet.vn/chord/song/a/AAA.html#top
            # ghi chú bỏ qua

            hopamviet.vn/chord/song/b/BBB.html
            https://example.com/chord/song/x
            https://hopamviet.vn/chord/printsong/a/AAA.html
        """.trimIndent()
        val (q, r) = BatchQueue().add(text)
        assertEquals(listOf("https://hopamviet.vn/chord/song/a/AAA.html", "https://hopamviet.vn/chord/song/b/BBB.html"), q.items.map { it.url })
        assertEquals(2, r.added); assertEquals(2, r.duplicates); assertEquals(2, r.invalid.size)
        // thêm lần nữa: trùng với hàng đợi hiện có
        val (q2, r2) = q.add("https://hopamviet.vn/chord/song/b/BBB.html\nhttps://hopamviet.vn/chord/song/c/CCC.html")
        assertEquals(3, q2.total); assertEquals(1, r2.duplicates)
    }

    @Test fun lifecycleRetryCancelRecover() {
        var q = BatchQueue().add("https://hopamviet.vn/chord/song/a/1.html\nhttps://hopamviet.vn/chord/song/a/2.html\nhttps://hopamviet.vn/chord/song/a/3.html").first
        val u1 = q.next()!!.url
        q = q.update(u1) { it.copy(state = ItemState.DONE, attempts = 1, title = "Một") }
        val u2 = q.next()!!.url
        q = q.update(u2) { it.copy(state = ItemState.ERROR, attempts = 1, message = "HTTP 500") }
        q = q.update(q.next()!!.url) { it.copy(state = ItemState.RUNNING) }
        assertNull(q.next())
        // app bị tắt khi đang tải -> mục RUNNING trở lại PENDING
        q = q.recoverAfterRestart(); assertEquals(1, q.count(ItemState.PENDING))
        q = q.cancelRemaining(5); assertEquals(1, q.count(ItemState.CANCELLED)); assertFalse(q.hasPending)
        q = q.retryFailed(); assertEquals(2, q.count(ItemState.PENDING)); assertEquals(1, q.count(ItemState.DONE))
        assertNull(q.items.first { it.url == u2 }.message)
        assertEquals(2, q.clearDone().total)
    }

    @Test fun serializeRoundTrip() {
        var q = BatchQueue(savePage = true, saveCompact = false)
            .add("https://hopamviet.vn/chord/song/a/1.html\nhttps://hopamviet.vn/chord/song/a/2.html").first
        q = q.update(q.items[0].url) { it.copy(state = ItemState.ERROR, attempts = 2, message = "lỗi\tcó tab\nvà \\ xuống dòng", title = "Tên", updatedAt = 99, key = "K1") }
        val back = BatchQueue.parse(q.serialize())
        assertEquals(q, back)
        assertEquals(BatchQueue(), BatchQueue.parse(""))
    }

    @Test fun blockingResponsesStopTheQueue() {
        assertTrue(BatchQueue.isBlockingResponse(429, ""))
        assertTrue(BatchQueue.isBlockingResponse(403, null))
        assertTrue(BatchQueue.isBlockingResponse(200, "<title>Just a moment...</title>"))
        assertFalse(BatchQueue.isBlockingResponse(200, "<div id=lyricBox></div>"))
        assertFalse(BatchQueue.isBlockingResponse(404, ""))
    }

    @Test fun defaultsSaveBothVersionsAndOldFilesStillParse() {
        val q = BatchQueue()
        assertTrue(q.saveCompact); assertTrue(q.savePage)
        // file hàng đợi cũ ghi page=0 vẫn được tôn trọng
        assertFalse(BatchQueue.parse("${BatchQueue.HEADER}\n#opts\tcompact=1\tpage=0\n").savePage)
    }

    @Test fun storeSkipsAlreadySavedSongs() {
        val dir = Files.createTempDirectory("skip").toFile()
        try {
            val st = SongStore(dir)
            val url = "https://hopamviet.vn/chord/song/bai/K9X.html"
            val key = st.keyForUrl(url)
            assertEquals("K9X", key)
            assertFalse(st.isSaved(key, compact = true, page = true))
            val p = ParsedSong(url, "K9X", "1", "Bài", emptyList(), null, null, "C", emptyList(), emptyList(), null, "[C] lời")
            st.saveCompact(key, p, ChordText.toChordPro(p, "t"))
            assertTrue(st.isSaved(key, compact = true, page = false))
            assertFalse("chưa có nguyên trang", st.isSaved(key, compact = true, page = true))
            st.savePage(key, "Bài", "<html></html>", FetchMeta(url, url, 200, "text/html", 0), p)
            assertTrue(st.isSaved(key, compact = true, page = true))
            assertEquals("Bài", st.info(key)["title"])
            assertFalse(st.isSaved(key, compact = false, page = false))
            assertNull(SongStore.slugFromUrl("https://hopamviet.vn/"))
        } finally { dir.deleteRecursively() }
    }
}
