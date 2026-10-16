package com.binh.chordoffline.core

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Cấu trúc mô phỏng trang thật: [id^=song-item-] + .song-singer-text + .tone-badge, ảnh đầu trong #main_player. Dữ liệu giả. */
class SingerAndBackupTest {

    private fun item(i: Int, play: String, name: String, tone: String, extra: String = "") = """
        <div id="song-item-$i" class="song-item-btn" data-play-id="$play" $extra>
          <span class="song-num">${i + 1}</span>
          <div class="song-singer-text" title="$name"><a href="/chord/singer/1/x">$name</a></div>
          <span class="tone-badge">$tone</span>
        </div>"""

    private val mainPlayer = """<div id="main_player"><img src="https://img.youtube.com/vi/AAAAAAAAAA1/hqdefault.jpg"></div>"""

    @Test fun firstSingerGetsServerRenderedThumbnail() {
        val doc = Jsoup.parse(mainPlayer + item(0, "111", "Ca sĩ Nam", "Bm") + item(1, "222", "Ca sĩ Nữ", "Em"))
        val s = SingerExtractor.extract(doc)
        assertEquals(listOf("Ca sĩ Nam", "Ca sĩ Nữ"), s.map { it.name })
        assertEquals(listOf("Bm", "Em"), s.map { it.tone })
        assertEquals("AAAAAAAAAA1", s[0].youtubeId)
        assertNull("video ca sĩ 2 không có trong HTML -> null, không bịa", s[1].youtubeId)
        assertEquals("https://www.youtube.com/watch?v=AAAAAAAAAA1", s[0].youtubeUrl)
        assertEquals("https://img.youtube.com/vi/AAAAAAAAAA1/mqdefault.jpg", s[0].thumbnailUrl)
    }

    @Test fun videoIdFromAttributeOrNearbyScript() {
        val html = mainPlayer + item(0, "111", "A", "C") + item(1, "222", "B", "D", "data-video-id=\"BBBBBBBBBB2\"") +
            item(2, "333", "C", "E") +
            """<script>var playlist=[{"id":333,"video_id":"CCCCCCCCCC3"}];</script>"""
        val s = SingerExtractor.extract(Jsoup.parse(html))
        assertEquals(listOf("AAAAAAAAAA1", "BBBBBBBBBB2", "CCCCCCCCCC3"), s.map { it.youtubeId })
    }

    @Test fun fallbackToInlineSingerTones() {
        val html = """<div id="inline-singer-tones"><button><span>Ca sĩ X</span>:<span>Am</span></button></div>""" + mainPlayer
        val s = SingerExtractor.extract(Jsoup.parse(html))
        assertEquals(listOf(SingerInfo("Ca sĩ X", "Am", null, "AAAAAAAAAA1")), s)
        assertTrue(SingerExtractor.extract(Jsoup.parse("<p>không có ca sĩ</p>")).isEmpty())
    }

    @Test fun directiveRoundTripAndLegacyMeta() {
        val list = listOf(SingerInfo("Tên|lạ;;x", "Bm", "1", "AAAAAAAAAA1"), SingerInfo("B", null, null, null))
        val back = SingerExtractor.fromMeta(mapOf("singers" to SingerExtractor.toDirective(list)!!))
        assertEquals(listOf(SingerInfo("Tên/lạ;x", "Bm", "1", "AAAAAAAAAA1"), SingerInfo("B", null, null, null)), back)
        // file .cho lưu bằng bản cũ
        assertEquals(listOf(SingerInfo("Jack - J97", "Bm"), SingerInfo("Ca sĩ: có hai chấm", "Am")),
            SingerExtractor.fromMeta(mapOf("singer_tones" to "Jack - J97: Bm; Ca sĩ: có hai chấm: Am")))
        assertNull(SingerExtractor.toDirective(emptyList()))
    }

    @Test fun chordProKeepsSingersAndOldFieldsStillWork() {
        val p = ParsedSong("https://hopamviet.vn/chord/song/a/K1.html", "K1", "1", "Bài", listOf("NS"), null, "Ballad", "Am",
            listOf("Ca sĩ" to "Bm"), emptyList(), "AAAAAAAAAA1", "[Am] lời\n\n[G] hai",
            singers = listOf(SingerInfo("Ca sĩ", "Bm", "9", "AAAAAAAAAA1")))
        val d = ChordText.parseChordPro(ChordText.toChordPro(p, "t"))
        assertEquals(p.lyrics, d.body)
        assertEquals("AAAAAAAAAA1", d.meta["youtube"])
        assertEquals(p.singers, SingerExtractor.fromMeta(d.meta))
        assertEquals("Ca sĩ: Bm", d.meta["singer_tones"])
    }

    @Test fun stepsToSingerTone() {
        assertEquals(2, Transpose.stepsBetween("Am", "Bm"))
        assertEquals(-2, Transpose.stepsBetween("Am", "Gm"))
        assertEquals(6, Transpose.stepsBetween("C", "F#"))
        assertEquals(0, Transpose.stepsBetween("Am", "Am"))
        assertNull(Transpose.stepsBetween(null, "Am"))
        assertNull(Transpose.stepsBetween("Am", "?"))
        // nhảy tới tone ca sĩ rồi hoàn tác
        val t = TransposeState().up().to(Transpose.stepsBetween("Am", "Bm")!!)
        assertEquals(2, t.steps); assertEquals(1, t.undo().steps)
        assertEquals(t, t.to(2))
    }

    @Test fun suggestionsRankTitlePrefixFirst() {
        fun s(k: String, t: String, singer: String? = null) =
            SavedSong(k, t, null, VersionStatus(SaveState.SAVED, 1, 1), VersionStatus.NONE, null, singer, null)
        val songs = listOf(s("1", "Nhớ mẹ mỗi độ xuân về"), s("2", "Mỗi độ xuân về", "Hoài Phương"),
            s("3", "Mỗi mùa xuân về"), s("4", "Bài khác", "Mỗi Độ"))
        assertEquals(listOf("2", "1", "4"), LibrarySearch.suggest(songs, "moi do").map { it.key })
        assertEquals(1, LibrarySearch.suggest(songs, "moi", limit = 1).size)
        assertTrue(LibrarySearch.suggest(songs, " ").isEmpty())
    }

    @Test fun backupFilesAndSafeImport() {
        val a = Files.createTempDirectory("a").toFile(); val b = Files.createTempDirectory("b").toFile()
        try {
            val src = SongStore(a)
            val p = ParsedSong("https://hopamviet.vn/chord/song/a/K1.html", "K1", "1", "Bài", emptyList(), null, null, "C",
                emptyList(), emptyList(), null, "[C] lời")
            src.saveCompact("K1", p, ChordText.toChordPro(p, "t"))
            src.saveThumb("K1", "AAAAAAAAAA1", ByteArray(500) { 1 })
            java.io.File(a, "K1/song.cho.tmp").writeText("rác")
            assertEquals(listOf("compact.properties", "info.properties", "song.cho", "yt_AAAAAAAAAA1.jpg"), src.filesOf("K1").map { it.name })
            assertEquals(listOf("K1"), src.keys())

            // khôi phục sang máy "mới"
            val dst = SongStore(b)
            src.filesOf("K1").forEach { assertTrue(dst.importFile("K1", it.name, it.readBytes())) }
            assertEquals(SaveState.SAVED, dst.status("K1", Version.COMPACT).state)
            assertTrue(dst.hasThumb("K1", "AAAAAAAAAA1"))
            assertEquals("Bài", dst.list().single().title)
            // không ghi đè khi đã có; chặn tên lạ và đường dẫn ../
            assertFalse(dst.importFile("K1", "song.cho", "x".toByteArray()))
            assertTrue(dst.importFile("K1", "song.cho", "x".toByteArray(), overwrite = true))
            assertFalse(dst.importFile("K1", "../evil.sh", "x".toByteArray()))
            assertFalse(dst.importFile("../K1", "song.cho", "x".toByteArray()))
            assertFalse(dst.importFile("K1", "other.txt", "x".toByteArray()))
            // ảnh hỏng bị từ chối
            assertThrows(IllegalArgumentException::class.java) { dst.saveThumb("K1", "AAAAAAAAAA1", ByteArray(3)) }
        } finally { a.deleteRecursively(); b.deleteRecursively() }
    }
}
