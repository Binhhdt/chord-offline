package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test

/** Cấu trúc tab phiên bản theo trang thật "Lời hứa vu vơ (Paroles, Paroles)" (18/10/2026); lời rút gọn. */
class LyricVersionsTest {
    private val twoVersions = """<html><head><link rel="canonical" href="https://hopamviet.vn/chord/song/loi-hua-vu-vo/W8IUABCD.html"></head><body>
        <div id="songHeaderCard"><h1>Lời hứa vu vơ (Paroles, Paroles)</h1></div>
        <div class="flex flex-col" id="lyricBox">
          <div class="flex items-center" id="lyric-tab-bar">
            <button type="button" class="lyric-tab-btn" id="lyric-tab-0">
                Phiên bản 1
            </button>
            <button type="button" class="lyric-tab-btn" id="lyric-tab-1">
                Phiên bản 2
            </button>
            <button type="button" id="lyric-tab-collapse" title="Hiển thị tất cả phiên bản">Tất cả</button>
          </div>
          <div class="lyric-block lyric-version" id="lyric-version-0" style="display: none;">1. <span class="chord">[Em]</span> Cuộc tình sẽ <span class="chord">[Am]</span> trôi
 
ĐK:
Cho <span class="chord">[Em]</span> em một lần quên</div>
          <div class="lyric-block lyric-version" id="lyric-version-1" style=""><span class="chord">[Em]</span> Encore des <span class="chord">[Am]</span> mots
Rien que des <span class="chord">[C]</span> mots <span class="chord">[B7]</span></div>
        </div></body></html>"""

    @Test fun parsesAllVersionsWithTabLabels() {
        val p = SongParser.parse(twoVersions)
        assertEquals("1. [Em] Cuộc tình sẽ [Am] trôi\n\nĐK:\nCho [Em] em một lần quên", p.lyrics)   // phiên bản 1 giữ nguyên như trước
        assertEquals(listOf("Phiên bản 1", "Phiên bản 2"), p.lyricVersions.map { it.label })
        assertEquals(p.lyrics, p.lyricVersions[0].lyrics)
        assertEquals("[Em] Encore des [Am] mots\nRien que des [C] mots [B7]", p.lyricVersions[1].lyrics)
    }

    @Test fun chordProRoundTripKeepsEveryVersion() {
        val p = SongParser.parse(twoVersions)
        val cho = ChordText.toChordPro(p, "2026-10-18T19:00:00+07:00")
        assertTrue(ChordText.hasVersions(cho))
        val doc = ChordText.parseChordPro(cho)
        assertEquals("Lời hứa vu vơ (Paroles, Paroles)", doc.meta["title"])
        val v = ChordText.splitVersions(doc)
        assertEquals(listOf("Phiên bản 1", "Phiên bản 2"), v.map { it.first })
        assertEquals(p.lyrics, v[0].second)
        assertEquals(p.lyricVersions[1].lyrics, v[1].second)
    }

    @Test fun singleVersionSongsAreSavedExactlyAsBefore() {
        val one = twoVersions.replace(Regex("""<div class="lyric-block lyric-version" id="lyric-version-1".*?</div>""", RegexOption.DOT_MATCHES_ALL), "")
        val p = SongParser.parse(one)
        assertTrue(p.lyricVersions.isEmpty())
        val cho = ChordText.toChordPro(p, "x")
        assertFalse(cho.contains("version"))
        val v = ChordText.splitVersions(ChordText.parseChordPro(cho))
        assertEquals(1, v.size); assertEquals(p.lyrics, v[0].second)
        // file .cho cũ (không có phiên bản) vẫn đọc được như một phiên bản
        val old = "{title: Người dưng}\n\n[Am] Lời\n\n[C] Lời 2\n"
        assertEquals(listOf("Phiên bản 1" to "[Am] Lời\n\n[C] Lời 2"), ChordText.splitVersions(ChordText.parseChordPro(old)))
    }

    @Test fun missingTabLabelFallsBack() {
        val noTabs = twoVersions.replace(Regex("""<div class="flex items-center" id="lyric-tab-bar">.*?</div>""", RegexOption.DOT_MATCHES_ALL), "")
        assertEquals(listOf("Phiên bản 1", "Phiên bản 2"), SongParser.parse(noTabs).lyricVersions.map { it.label })
    }
}
