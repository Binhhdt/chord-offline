package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LibraryIndexTest {
    private fun song(i: Int, title: String = "Bài $i", singers: String? = "Ca sĩ $i") = SavedSong(
        "K$i", title, "https://hopamviet.vn/chord/song/bai-$i/K$i.html",
        VersionStatus(SaveState.SAVED, 1_000L + i, 300_000L + i), VersionStatus(SaveState.SAVED, 2_000L + i, 1_500L),
        composers = "Nhạc sĩ $i", singers = singers, tone = "Am")

    @Test fun roundTripKeepsEveryField() {
        val tricky = SavedSong("X1", "Tên\tcó tab\nvà xuống dòng \\ gạch", null,
            VersionStatus(SaveState.ERROR, null, null, "HTTP 503\tbận"), VersionStatus.NONE)
        val list = listOf(song(1), song(2, singers = null), tricky)
        val back = LibraryIndex.parse(LibraryIndex.serialize(list))
        assertEquals(list, back)
    }

    @Test fun rejectsUnknownFormat() {
        assertNull(LibraryIndex.parse("rác"))
        assertNull(LibraryIndex.parse(LibraryIndex.HEADER + "\nK1\tthiếu cột"))
    }

    @Test fun reconcileDropsDeletedAndFindsNewFolders() {
        val idx = listOf(song(1), song(2), song(3))
        val (kept, missing) = LibraryIndex.reconcile(idx, listOf("K1", "K3", "K9"))
        assertEquals(listOf("K1", "K3"), kept.map { it.key })   // K2 đã bị xoá thư mục
        assertEquals(listOf("K9"), missing)                     // K9 có thư mục nhưng chưa trong mục lục
    }

    @Test fun readWriteFileAndRealStore() {
        val dir = Files.createTempDirectory("idx").toFile()
        try {
            val st = SongStore(java.io.File(dir, "songs"))
            val p = SongParser.parse(
                """<div id="songHeaderCard"><h1>Người dưng</h1></div><div id="lyric-version-0">[<span class="chord">Am</span>]Lời</div>""",
                "https://hopamviet.vn/chord/song/nguoi-dung/W8IUDI0D.html")
            val key = st.keyFor("https://hopamviet.vn/chord/song/nguoi-dung/W8IUDI0D.html", p.slugId)
            st.saveCompact(key, p, ChordText.toChordPro(p, "2026-10-18T00:00:00+07:00"))
            assertEquals(listOf(key), st.dirKeys())
            assertEquals(st.list(), listOfNotNull(st.load(key)))
            val f = java.io.File(dir, "library_index.tsv")
            LibraryIndex.write(f, st.list())
            assertEquals(st.list(), LibraryIndex.read(f))
        } finally { dir.deleteRecursively() }
    }

    @Test fun indexedSearchMatchesOldSearch() {
        val songs = listOf(
            song(1, "Người dưng", "Lê Quyên"), song(2, "Xin em đừng khóc vu quy", "Giáng Tiên"),
            song(3, "Đừng khóc", "Đàm Vĩnh Hưng"), song(4, "Mưa chiều", null))
        val idx = LibrarySearch.index(songs)
        for (q in listOf("", "dung", "xin em dung kh", "khoc", "dam", "quyen nguoi", "zzz"))
            for (f in SearchField.entries) for (s in SortMode.entries)
                assertEquals("$q/$f/$s", LibrarySearch.filter(songs, q, f, s).map { it.key }.toSet(),
                    LibrarySearch.filterIndexed(idx, q, f, s).map { it.key }.toSet())
        assertEquals(listOf("K2"), LibrarySearch.suggestIndexed(idx, "xin em dung kh").map { it.key })
        assertEquals(listOf("K3", "K2"), LibrarySearch.suggestIndexed(idx, "dung khoc").map { it.key })
        // dựng lại sau khi thêm 1 bài: các mục cũ được dùng lại
        val more = listOf(song(5, "Bài mới")) + songs
        val idx2 = LibrarySearch.index(more, idx)
        assertSame(idx[0], idx2[1])
    }

    @Test fun fiftyThousandSongsSearchIsFast() {
        val songs = (1..50_000).map { song(it, "Bài hát số $it Người dưng ${it % 97}", "Ca sĩ Đàm ${it % 500}") }
        val idx = LibrarySearch.index(songs)
        val t0 = System.nanoTime()
        repeat(10) { k ->
            LibrarySearch.suggestIndexed(idx, "nguoi dung $k")
            LibrarySearch.filterIndexed(idx, "dam 4", SearchField.ALL, SortMode.TITLE)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("10 lượt tìm trên 50.000 bài mất $ms ms", ms < 3_000)
        val t1 = System.nanoTime()
        val text = LibraryIndex.serialize(songs)
        assertEquals(50_000, LibraryIndex.parse(text)!!.size)
        val ms2 = (System.nanoTime() - t1) / 1_000_000
        assertTrue("ghi+đọc mục lục 50.000 bài mất $ms2 ms", ms2 < 3_000)
        println("search10=${ms}ms index-roundtrip=${ms2}ms size=${text.length / 1024}KB")
    }
}
