package com.binh.chordoffline.core

/**
 * Chuyển tone theo bán cung. Chỉ biến đổi chuỗi để HIỂN THỊ — dữ liệu gốc (file .cho) không bị sửa.
 * Hỗ trợ hợp âm trưởng/thứ/7/maj7/m7/sus/dim/aug/add9/6/9/11/13/m7b5 và slash chord (C/G).
 * Ký hiệu không phải hợp âm trong ngoặc vuông (ví dụ [x2], [ĐK]) được giữ nguyên.
 */
object Transpose {
    private val SHARPS = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLATS = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    private val NATURAL = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    // ICU (Android): escape "]", "}", "(", ")", "+" khi dùng như ký tự thường.
    private val CHORD_RX = Regex("""^([A-G])([#b♯♭]?)([^/\s]*)(?:/([A-G])([#b♯♭]?))?$""")
    private val QUALITY_RX = Regex("""^(?:maj|min|dim|aug|sus|add|m|M|\+|°|ø|o|[0-9]|b|#|\(|\)|,|-)*$""")

    /** Tone trưởng/thứ thường viết bằng dấu giáng (b); còn lại dùng dấu thăng (#). */
    private val FLAT_MAJOR = setOf(5, 10, 3, 8, 1)      // F Bb Eb Ab Db
    private val FLAT_MINOR = setOf(2, 7, 0, 5, 10, 3)   // Dm Gm Cm Fm Bbm Ebm

    data class Parsed(val root: Int, val quality: String, val bass: Int?, val usesFlat: Boolean)

    fun parse(chord: String): Parsed? {
        val m = CHORD_RX.matchEntire(chord.trim()) ?: return null
        val (r, acc, q, b, bAcc) = m.destructured
        if (!QUALITY_RX.matches(q)) return null
        val flat = acc == "b" || acc == "♭" || bAcc == "b" || bAcc == "♭"
        return Parsed(pc(r[0], acc), q, b.takeIf { it.isNotEmpty() }?.let { pc(it[0], bAcc) }, flat)
    }

    private fun pc(letter: Char, acc: String): Int {
        val base = NATURAL.getValue(letter)
        return Math.floorMod(base + when (acc) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }, 12)
    }

    private fun name(pc: Int, flats: Boolean) = (if (flats) FLATS else SHARPS)[Math.floorMod(pc, 12)]

    /** Chuyển một hợp âm; trả null nếu chuỗi không phải hợp âm (giữ nguyên khi hiển thị). */
    fun chord(chord: String, steps: Int, flats: Boolean): String? {
        val p = parse(chord) ?: return null
        if (Math.floorMod(steps, 12) == 0) return chord.trim()
        val root = name(p.root + steps, flats)
        val bass = p.bass?.let { "/" + name(it + steps, flats) } ?: ""
        return root + p.quality + bass
    }

    /** Có nên viết bằng dấu giáng không: theo tone đích nếu biết, nếu không theo cách viết của bài gốc. */
    fun preferFlats(originalKey: String?, steps: Int, originalText: String): Boolean {
        val k = originalKey?.let { parse(it) }
        if (k != null) {
            val target = Math.floorMod(k.root + steps, 12)
            val minor = k.quality.startsWith("m") && !k.quality.startsWith("maj")
            return if (minor) target in FLAT_MINOR else target in FLAT_MAJOR
        }
        val all = ChordText.CHORD.findAll(originalText).mapNotNull { parse(it.groupValues[1]) }.toList()
        return all.count { it.usesFlat } > all.size / 2
    }

    /** Chuyển mọi [hợp âm] trong văn bản; giữ nguyên lời, dòng trống và ký hiệu không phải hợp âm. */
    fun text(body: String, steps: Int, flats: Boolean): String {
        if (Math.floorMod(steps, 12) == 0) return body
        return ChordText.CHORD.replace(body) { m ->
            val c = m.groupValues[1]
            "[" + (chord(c, steps, flats) ?: c) + "]"
        }
    }

    /** Tone sau khi chuyển, ví dụ ("Am", +2) -> "Bm". */
    fun key(originalKey: String?, steps: Int): String? {
        originalKey ?: return null
        return chord(originalKey, steps, preferFlats(originalKey, steps, "")) ?: originalKey
    }

    /** Đưa số bán cung về khoảng -5..+6 để hiển thị gọn. */
    fun normalize(steps: Int): Int { val s = Math.floorMod(steps, 12); return if (s > 6) s - 12 else s }
}

/** Trạng thái chuyển tone trong trình xem, có lịch sử để hoàn tác. Không ghi gì xuống file. */
data class TransposeState(val steps: Int = 0, val history: List<Int> = emptyList()) {
    val canUndo: Boolean get() = history.isNotEmpty()
    fun up() = TransposeState(Transpose.normalize(steps + 1), history + steps)
    fun down() = TransposeState(Transpose.normalize(steps - 1), history + steps)
    fun reset() = if (steps == 0) this else TransposeState(0, history + steps)
    fun undo() = if (history.isEmpty()) this else TransposeState(history.last(), history.dropLast(1))
    val label: String get() = when { steps > 0 -> "+$steps"; else -> "$steps" }
}
