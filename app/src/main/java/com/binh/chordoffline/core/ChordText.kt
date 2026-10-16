package com.binh.chordoffline.core

/** Định dạng lời/hợp âm gọn (ChordPro đơn giản) và tiện ích hiển thị. */
object ChordText {

    val CHORD = Regex("""\[([^\]\n]{1,16})]""")
    private val DIRECTIVE = Regex("""^\{([a-z_]+):\s?(.*)}$""")

    /** Tạo nội dung file .cho: các dòng {key: value} + một dòng trống + lời (giữ dòng trống, hợp âm inline). */
    fun toChordPro(song: ParsedSong, fetchedAtIso: String): String = buildString {
        fun d(k: String, v: String?) { if (!v.isNullOrBlank()) append("{").append(k).append(": ").append(v.replace("\n", " ")).append("}\n") }
        d("title", song.title)
        d("composer", song.composers.joinToString(", ").ifBlank { null })
        d("key", song.tone)
        d("rhythm", song.rhythm)
        d("singer_tones", song.singerTones.joinToString("; ") { "${it.first}: ${it.second}" }.ifBlank { null })
        d("source", song.sourceUrl)
        d("source_site", "Hợp Âm Việt (hopamviet.vn)")
        d("fetched_at", fetchedAtIso)
        append("\n")
        append(song.lyrics.orEmpty())
        append("\n")
    }

    data class ChordProDoc(val meta: Map<String, String>, val body: String)

    fun parseChordPro(text: String): ChordProDoc {
        val lines = text.replace("\r\n", "\n").split('\n')
        val meta = linkedMapOf<String, String>()
        var i = 0
        while (i < lines.size) {
            val m = DIRECTIVE.matchEntire(lines[i].trim()) ?: break
            meta[m.groupValues[1]] = m.groupValues[2]
            i++
        }
        if (i < lines.size && lines[i].isBlank()) i++   // dòng trống ngăn cách header
        return ChordProDoc(meta, lines.drop(i).joinToString("\n").trimEnd('\n'))
    }

    /**
     * Tách một dòng có hợp âm inline thành (dòng hợp âm, dòng lời) để hiển thị hợp âm phía trên,
     * dùng font monospace. Dòng không có hợp âm trả về ("", dòng gốc).
     */
    fun splitAbove(line: String): Pair<String, String> {
        if (!CHORD.containsMatchIn(line)) return "" to line
        val lyric = StringBuilder()
        val chords = StringBuilder()
        var pos = 0
        for (m in CHORD.findAll(line)) {
            lyric.append(line, pos, m.range.first)
            pos = m.range.last + 1
            // "nửa [G] che" -> tránh hai khoảng trắng liền nhau sau khi bỏ hợp âm
            if ((lyric.isEmpty() || lyric.endsWith(" ")) && pos < line.length && line[pos] == ' ') pos++
            var col = lyric.length
            if (chords.isNotEmpty() && col <= chords.length) col = chords.length + 1
            while (chords.length < col) chords.append(' ')
            chords.append(m.groupValues[1])
        }
        lyric.append(line.substring(pos))
        return chords.toString().trimEnd() to lyric.toString().trimEnd()
    }
}
