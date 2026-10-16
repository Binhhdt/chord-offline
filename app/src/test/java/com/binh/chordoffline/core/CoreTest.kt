package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/**
 * Test dùng HTML mẫu mô phỏng cấu trúc trang (lời giả), không chứa nội dung có bản quyền.
 * Cấu trúc lấy theo file HTML thật đã phân tích ngày 2026-10-16.
 */
class CoreTest {

    private val serverLike = """
        <html><head><link rel="canonical" href="https://hopamviet.vn/chord/song/bai-mau/ABCD1234.html"></head><body>
        <div id="songHeaderCard"><h1 class="x">
            Bài mẫu   </h1>
          <div id="mobileMetadata"><a href="https://hopamviet.vn/chord/composer/1/tac-gia.html">TÁC GIẢ</a></div>
          <div id="song-header-badges"><a href="https://hopamviet.vn/chord/category/3/nhac-tre.html">Nhạc Trẻ</a></div>
          <button><span id="currentRhythmLabelMobile">Ballad</span></button>
          <button id="btn-like" data-song-id="123" data-liked="0"></button>
          <div id="inline-singer-tones"><button><span>Ca sĩ A</span>:<span>Bm</span></button></div>
        </div>
        <button class="toolbar-btn" id="song-tone">[Am]</button>
        <div id="lyricBox"><div class="lyric-block lyric-version" id="lyric-version-0"><span class="chord">[Am]</span> Dòng một có <span class="chord">[G]</span> hợp âm
Dòng <span class="chord">[Em]</span> hai

ĐK:
Dòng ba không hợp âm
<span class="chord">[F]</span> Dòng bốn  </div></div>
        <div id="show_tab"><span chord="Am" class="jtab chordonly"></span><span chord="G" class="jtab chordonly"></span></div>
        <img src="https://img.youtube.com/vi/AAAAAAAAAAA/hqdefault.jpg">
        </body></html>
    """.trimIndent()

    @Test fun parsesAllFields() {
        val s = SongParser.parse(serverLike)
        assertEquals("Bài mẫu", s.title)
        assertEquals("ABCD1234", s.slugId)
        assertEquals("123", s.songId)
        assertEquals(listOf("TÁC GIẢ"), s.composers)
        assertEquals("Nhạc Trẻ", s.category)
        assertEquals("Ballad", s.rhythm)
        assertEquals("Am", s.tone)
        assertEquals(listOf("Ca sĩ A" to "Bm"), s.singerTones)
        assertEquals(listOf("Am", "G"), s.chordsUsed)
        assertEquals("AAAAAAAAAAA", s.youtubeId)
    }

    @Test fun lyricsKeepBlankLinesAndInlineChords() {
        val l = SongParser.parse(serverLike).lyrics!!
        assertEquals(
            "[Am] Dòng một có [G] hợp âm\nDòng [Em] hai\n\nĐK:\nDòng ba không hợp âm\n[F] Dòng bốn", l,
        )
    }

    @Test fun brTagsBecomeNewlines() {
        val h = """<div id="lyricBox"><div id="lyric-version-0"><span class="chord">C</span> a<br>b<br><br>c</div></div>"""
        assertEquals("[C] a\nb\n\nc", SongParser.parse(h).lyrics)
    }

    @Test fun selectorCheckReportsMissing() {
        val r = SelectorCheck.check("<html><body><div id='lyricBox'></div></body></html>")
        assertEquals(SelectorCheck.REQUIRED, r.map { it.selector })
        assertTrue(r[0].found)
        assertFalse(r[1].found); assertFalse(r[2].found); assertFalse(r[3].found); assertFalse(r[4].found)
    }

    @Test fun chordProRoundTrip() {
        val s = SongParser.parse(serverLike)
        val cho = ChordText.toChordPro(s, "2026-10-16T18:00:00+07:00")
        val d = ChordText.parseChordPro(cho)
        assertEquals(s.lyrics, d.body)
        assertEquals("Bài mẫu", d.meta["title"])
        assertEquals("https://hopamviet.vn/chord/song/bai-mau/ABCD1234.html", d.meta["source"])
    }

    @Test fun splitAbove() {
        // "Dòng một có " dài 12 ký tự -> G nằm ở cột 12, ngay trên chữ "h"
        assertEquals("Am" + " ".repeat(10) + "G" to "Dòng một có hợp âm", ChordText.splitAbove("[Am] Dòng một có [G] hợp âm"))
        assertEquals("" to "không có", ChordText.splitAbove("không có"))
        assertEquals("C D" to "x", ChordText.splitAbove("[C][D]x"))
    }

    @Test fun comparisonDetectsJsOnlyContent() {
        val saved = serverLike.replace("""class="jtab chordonly"""", """class="jtab chordonly rendered"""")
            .replace("</body>", "<iframe src='about:blank'></iframe></body>")
        val r = HtmlComparison.compare(serverLike, saved)
        assertEquals(true, r.lyricsIdentical)
        val byLabel = r.rows.associateBy { it.selector }
        assertEquals(Presence.BOTH, byLabel["#lyric-version-0"]!!.presence)
        assertEquals(Presence.SAVED_ONLY, byLabel["#show_tab .jtab.rendered"]!!.presence)
        assertEquals(Presence.SAVED_ONLY, byLabel["iframe"]!!.presence)
    }

    @Test fun diagnosis() {
        assertTrue(Diagnosis.explainMissingLyrics(403, "text/html", "", null).contains("403"))
        assertTrue(Diagnosis.explainMissingLyrics(200, "text/html", "<div id=lyricBox><div id=lyric-version-0></div></div>", null).contains("rỗng"))
        assertTrue(Diagnosis.explainMissingLyrics(200, "application/json", "{}", null).contains("Content-Type"))
        assertTrue(Diagnosis.explainMissingLyrics(null, null, null, "UnknownHostException: x").startsWith("Không tải được"))
    }

    @Test fun robotsAndUrlPolicy() {
        val r = RobotsRules.parse("User-agent: *\nAllow: /\nDisallow: /chord/printsong/\nDisallow: /chord/search\n", "ChordOffline")
        assertTrue(r.isAllowed("/chord/song/a/B.html"))
        assertFalse(r.isAllowed("/chord/printsong/a/B.html"))
        assertFalse(r.isAllowed("/chord/search?q=x"))
        assertNull(UrlPolicy.validateSongUrl("https://hopamviet.vn/chord/song/a/B.html"))
        assertNotNull(UrlPolicy.validateSongUrl("http://hopamviet.vn/chord/song/a/B.html"))
        assertNotNull(UrlPolicy.validateSongUrl("https://example.com/chord/song/a"))
        assertNotNull(UrlPolicy.validateSongUrl("https://hopamviet.vn/chord/printsong/a/B.html"))
    }

    @Test fun storeKeepsVersionsIndependent() {
        val dir = Files.createTempDirectory("store").toFile()
        try {
            val st = SongStore(dir)
            val s = SongParser.parse(serverLike)
            val key = st.keyFor(s.sourceUrl!!, s.slugId)
            assertEquals("ABCD1234", key)
            assertEquals(SaveState.NOT_SAVED, st.status(key, Version.PAGE).state)
            assertEquals(SaveState.SAVED, st.saveCompact(key, s, ChordText.toChordPro(s, "t")).state)
            assertEquals(SaveState.NOT_SAVED, st.status(key, Version.PAGE).state)
            assertEquals(SaveState.SAVED, st.savePage(key, s.title, serverLike, FetchMeta("u", "u", 200, "text/html", 0)).state)
            assertEquals(serverLike, st.read(key, Version.PAGE))
            st.delete(key, Version.PAGE)
            assertEquals(SaveState.NOT_SAVED, st.status(key, Version.PAGE).state)
            assertEquals(SaveState.SAVED, st.status(key, Version.COMPACT).state)
            assertEquals(1, st.list().size)
            val failed = st.saveCompact("other", s.copy(lyrics = null), "")
            assertEquals(SaveState.ERROR, failed.state)
            assertNotNull(failed.error)
        } finally { dir.deleteRecursively() }
    }
}
