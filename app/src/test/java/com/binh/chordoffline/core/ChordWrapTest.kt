package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test

/** Kiểm tra chế độ "Hợp âm phía trên": giữ đúng vị trí hợp âm khi tự xuống dòng ở mọi độ rộng. */
class ChordWrapTest {

    // Lời giả, mô phỏng các trường hợp gặp trong bài thật: hợp âm đầu/giữa/cuối dòng, liền nhau,
    // dòng chỉ có hợp âm, nhãn đoạn, dòng trống, dòng rất dài, hợp âm dài (slash, maj7, sus4).
    private val sample = listOf(
        "[Am] Một hai ba bốn năm, sáu bảy [G] tám",
        "Chín [Em] mười mười một mười hai mười ba [Am] mười bốn",
        "",
        "ĐK:",
        "Dòng [F] này rất dài để buộc phải xuống dòng nhiều lần khi màn hình hẹp [G] và còn dài thêm nữa [Em] cho chắc [Am] chắn",
        "[C][G/B] hai hợp âm liền nhau",
        "[Am] [G] [F] [E7]",
        "Cuối dòng có hợp âm [Cmaj7]",
        "Hợp âm dài [Asus4] ở [Bbmaj7/F] giữa và [F#m7b5] cuối",
        "Mộtchữdàikhôngcókhoảngtrắngnàocảđểthửcắtcứng [C] xong",
        "   ",
    )

    /** Lấy (tên hợp âm, ký tự lời ngay dưới) theo thứ tự. */
    private fun chordsWithChar(parts: List<Pair<String, String>>): List<Pair<String, Char?>> =
        parts.flatMap { (c, l) -> Regex("""\S+""").findAll(c).map { it.value to l.getOrNull(it.range.first) }.toList() }

    @Test fun fuzzAllWidths() {
        for (line in sample) for (w in 8..80) {
            val parts = ChordText.wrapAbove(line, w)
            val a = ChordText.anchor(line)
            // 1. lời không vượt độ rộng
            parts.forEach { (_, l) -> assertTrue("w=$w lời quá rộng: '$l' ← $line", l.length <= w) }
            // 2. không mất/thừa ký tự lời (bỏ qua khoảng trắng tại điểm ngắt)
            assertEquals("w=$w $line", a.lyric.replace(" ", ""), parts.joinToString("") { it.second }.replace(" ", ""))
            // 3. giữ thứ tự và đủ hợp âm
            assertEquals("w=$w $line", a.chords.map { it.second }, chordsWithChar(parts).map { it.first })
            // 4. hợp âm không bị đẩy (không chồng nhau) phải nằm đúng trên ký tự được neo
            val expected = a.chords.mapIndexed { i, (idx, name) ->
                val prev = a.chords.getOrNull(i - 1)
                val pushed = prev != null && idx <= prev.first + prev.second.length
                name to (if (pushed) null else a.lyric.getOrNull(idx))
            }
            chordsWithChar(parts).zip(expected).forEach { (got, exp) ->
                if (exp.second != null && exp.second != ' ') assertEquals("w=$w $line", exp, got)
            }
            // 5. dòng hợp âm vừa độ rộng khi có thể (w đủ chứa hợp âm dài nhất + 1 từ)
            if (w >= 24) parts.forEach { (c, _) -> assertTrue("w=$w hợp âm quá rộng: '$c' ← $line", c.length <= w) }
        }
    }

    @Test fun blankLinesPreserved() {
        assertEquals(listOf("" to ""), ChordText.wrapAbove("", 30))
        assertEquals(listOf("" to "ĐK:"), ChordText.wrapAbove("ĐK:", 30))
    }

    @Test fun chordOnlyLineWraps() {
        assertEquals(listOf("Am G F" to "", "E7" to ""), ChordText.wrapAbove("[Am] [G] [F] [E7]", 8))
        assertEquals(listOf("Am G F E7" to ""), ChordText.wrapAbove("[Am] [G] [F] [E7]", 30))
    }

    @Test fun chordAtLineEndStaysOnLastPart() {
        val parts = ChordText.wrapAbove("Cuối dòng có hợp âm [Cmaj7]", 12)
        assertEquals("Cmaj7", parts.last().first.trim())
    }

    @Test fun differentFontSizesGiveConsistentChords() {
        // Cỡ chữ khác nhau -> số cột khác nhau; thứ tự/tên hợp âm luôn như nhau
        val line = sample[4]
        val ref = ChordText.anchor(line).chords.map { it.second }
        listOf(14, 20, 28, 36, 44).forEach { cols ->
            assertEquals(ref, chordsWithChar(ChordText.wrapAbove(line, cols)).map { it.first })
        }
    }
}
