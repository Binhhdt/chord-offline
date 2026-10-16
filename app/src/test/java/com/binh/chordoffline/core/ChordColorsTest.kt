package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test

class ChordColorsTest {
    @Test fun paletteIsReadableOnDarkBackground() {
        ChordColors.PALETTE.forEach { c ->
            assertTrue("${c.name} ${ChordColors.toHex(c.argb)} tương phản ${"%.2f".format(ChordColors.contrast(c.argb, ChordColors.BACKGROUND))}",
                ChordColors.readable(c.argb))
        }
        assertEquals(ChordColors.PALETTE.size, ChordColors.PALETTE.map { it.argb }.distinct().size)
        assertTrue(ChordColors.PALETTE.any { it.argb == ChordColors.DEFAULT })
    }

    @Test fun parseAndFormatHex() {
        assertEquals(0xFF4FD1C5L, ChordColors.parseHex("#4FD1C5"))
        assertEquals(0xFF4FD1C5L, ChordColors.parseHex(" 4fd1c5 "))
        assertEquals(0xFFFFCC00L, ChordColors.parseHex("#FC0"))
        assertNull(ChordColors.parseHex("#12345"))
        assertNull(ChordColors.parseHex("xyzxyz"))
        assertNull(ChordColors.parseHex(""))
        assertEquals("#4FD1C5", ChordColors.toHex(0xFF4FD1C5L))
        assertEquals("#2B3CC2", ChordColors.toHex(ChordColors.DEFAULT))
    }

    @Test fun contrastMath() {
        assertEquals(21.0, ChordColors.contrast(0xFF000000L, 0xFFFFFFFFL), 0.01)
        assertEquals(1.0, ChordColors.contrast(0xFF777777L, 0xFF777777L), 0.0001)
        assertFalse("màu nhạt trên nền trắng không đọc được", ChordColors.readable(0xFFF2A33AL))
        assertTrue(ChordColors.readable(0xFF151826L))
    }

    @Test fun oldAmberFromDarkThemeIsReplaced() {
        assertEquals(ChordColors.DEFAULT, ChordColors.sanitize(0xFFF2A33AL))   // hổ phách của giao diện tối cũ
        assertEquals(0xFFC62828L, ChordColors.sanitize(0xFFC62828L))
    }

    @Test fun defaultStyle() {
        val s = ChordStyle()
        assertEquals(ChordColors.DEFAULT, s.color)
        assertFalse(s.highlight)
    }
}
