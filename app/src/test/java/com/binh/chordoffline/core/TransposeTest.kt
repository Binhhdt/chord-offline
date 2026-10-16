package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test

class TransposeTest {

    private fun up(c: String, n: Int, flats: Boolean = false) = Transpose.chord(c, n, flats)

    @Test fun majorMinorAndSevenths() {
        assertEquals("D", up("C", 2))
        assertEquals("Bm", up("Am", 2))
        assertEquals("A#m", up("Am", 1))
        assertEquals("Bbm", up("Am", 1, flats = true))
        assertEquals("F#7", up("E7", 2))
        assertEquals("Dmaj7", up("Cmaj7", 2))
        assertEquals("Em7", up("Dm7", 2))
        assertEquals("C#m7b5", up("Bm7b5", 2))
    }

    @Test fun susDimAugAdd9() {
        assertEquals("Bsus4", up("Asus4", 2))
        assertEquals("Esus2", up("Dsus2", 2))
        assertEquals("C#dim", up("Bdim", 2))
        assertEquals("Fdim7", up("Ebdim7", 2))
        assertEquals("Daug", up("Caug", 2))
        assertEquals("D+", up("C+", 2))
        assertEquals("Gadd9", up("Fadd9", 2))
        assertEquals("A6", up("G6", 2))
        assertEquals("E7(b9)", up("D7(b9)", 2))
    }

    @Test fun slashChords() {
        assertEquals("D/F#", up("C/E", 2))
        assertEquals("Am/G", up("Gm/F", 2))
        assertEquals("Bb/D", up("Ab/C", 2, flats = true))
    }

    @Test fun downAndWrapAround() {
        assertEquals("B", up("C", -1))
        assertEquals("G#m", up("Am", -1))
        assertEquals("Abm", up("Am", -1, flats = true))
        assertEquals("C", up("C", 12))
        assertEquals("C", up("C", -12))
        assertEquals("Am", up("Am", 0))
    }

    @Test fun flatInputs() {
        assertEquals("C", up("Bb", 2))
        assertEquals("Db", up("Bb", 3, flats = true))
        assertEquals("C#", up("Bb", 3, flats = false))
    }

    @Test fun nonChordsUnchanged() {
        assertNull(Transpose.chord("x2", 2, false))
        assertNull(Transpose.chord("ĐK", 2, false))
        assertNull(Transpose.chord("Hello", 2, false))
        val body = "[Am] lời [x2] và [ĐK] [G/B] hết"
        assertEquals("[Bm] lời [x2] và [ĐK] [A/C#] hết", Transpose.text(body, 2, false))
    }

    @Test fun textKeepsLyricsAndBlankLines() {
        val body = "[Am] Một [G] hai\n\nĐK:\nBa [F] bốn [E7]"
        val t = Transpose.text(body, 3, Transpose.preferFlats("Am", 3, body))
        assertEquals("[Cm] Một [Bb] hai\n\nĐK:\nBa [Ab] bốn [G7]", t)
        // chuyển ngược về đúng bản gốc
        assertEquals(body, Transpose.text(t, -3, Transpose.preferFlats("Am", 0, body)))
        assertEquals(body, Transpose.text(body, 0, false))
        assertEquals(body, Transpose.text(body, 12, false))
    }

    @Test fun keyAndFlatPreference() {
        assertEquals("Bm", Transpose.key("Am", 2))
        assertEquals("Bbm", Transpose.key("Am", 1))   // Bbm là tone giáng
        assertEquals("F#m", Transpose.key("Am", -3))
        assertEquals("Bb", Transpose.key("C", -2))
        assertEquals("D", Transpose.key("C", 2))
        assertNull(Transpose.key(null, 2))
    }

    @Test fun undoHistory() {
        var s = TransposeState()
        assertFalse(s.canUndo)
        s = s.up().up().down()          // 0 -> 1 -> 2 -> 1
        assertEquals(1, s.steps)
        s = s.undo(); assertEquals(2, s.steps)
        s = s.reset(); assertEquals(0, s.steps)
        s = s.undo(); assertEquals(2, s.steps)
        s = s.undo().undo(); assertEquals(0, s.steps)
        assertFalse(s.canUndo)
        assertEquals(s, s.undo())
        // chuẩn hoá về -5..+6
        var t = TransposeState(); repeat(7) { t = t.up() }; assertEquals(-5, t.steps)
        assertEquals("-5", t.label)
    }
}
