package com.binh.chordoffline.core

/** Định dạng lời/hợp âm gọn (ChordPro đơn giản) và tiện ích hiển thị. */
object ChordText {

    // Lưu ý: regex ICU trên Android bắt buộc escape cả "]" và "}" dùng như ký tự thường.
    val CHORD = Regex("""\[([^\]\n]{1,16})\]""")
    private val DIRECTIVE = Regex("""^\{([a-z_]+):\s?(.*)\}$""")

    /** Tạo nội dung file .cho: các dòng {key: value} + một dòng trống + lời (giữ dòng trống, hợp âm inline). */
    fun toChordPro(song: ParsedSong, fetchedAtIso: String): String = buildString {
        fun d(k: String, v: String?) { if (!v.isNullOrBlank()) append("{").append(k).append(": ").append(v.replace("\n", " ")).append("}\n") }
        d("title", song.title)
        d("composer", song.composers.joinToString(", ").ifBlank { null })
        d("key", song.tone)
        d("rhythm", song.rhythm)
        d("singer_tones", song.singerTones.joinToString("; ") { "${it.first}: ${it.second}" }.ifBlank { null })
        d("singers", SingerExtractor.toDirective(song.singers))
        d("youtube", song.youtubeId)
        d("source", song.sourceUrl)
        d("source_site", "Hợp Âm Việt (hopamviet.vn)")
        d("fetched_at", fetchedAtIso)
        val versions = song.lyricVersions
        if (versions.size > 1) d("version_label", versions[0].label)
        append("\n")
        append(song.lyrics.orEmpty())
        append("\n")
        // Phiên bản 2, 3…: nối sau phiên bản 1, mỗi phiên bản mở đầu bằng dòng {version: tên}.
        // Bài một phiên bản không có phần này -> file giống hệt định dạng cũ.
        if (versions.size > 1) versions.drop(1).forEach { v ->
            append("\n{version: ").append(v.label.replace("\n", " ")).append("}\n")
            append(v.lyrics).append("\n")
        }
    }

    private val VERSION_LINE = Regex("""^\{version:\s*(.*?)\s*\}$""")

    /**
     * Tách thân bài thành các phiên bản (tên, lời). Bài một phiên bản -> một phần tử.
     * Tên phiên bản đầu lấy từ {version_label} (mặc định "Phiên bản 1").
     */
    fun splitVersions(doc: ChordProDoc): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var label = doc.meta["version_label"] ?: "Phiên bản 1"
        val buf = mutableListOf<String>()
        fun flush() { out += label to buf.joinToString("\n").trim('\n') ; buf.clear() }
        for (line in doc.body.split('\n')) {
            val m = VERSION_LINE.matchEntire(line.trim())
            if (m != null) { flush(); label = m.groupValues[1].ifEmpty { "Phiên bản ${out.size + 1}" } } else buf += line
        }
        flush()
        return out.filterIndexed { i, (_, b) -> i == 0 || b.isNotBlank() }
    }

    fun hasVersions(text: String): Boolean = text.contains("\n{version: ")

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

    /** Một dòng đã tách: lời không còn hợp âm + danh sách (vị trí ký tự trong lời, tên hợp âm). */
    data class Anchored(val lyric: String, val chords: List<Pair<Int, String>>)

    /**
     * Tách hợp âm khỏi lời và neo mỗi hợp âm vào chỉ số ký tự của lời mà nó đứng trước.
     * "nửa [G] che" -> lời "nửa che", G neo vào chữ "c" (bỏ khoảng trắng thừa sau hợp âm).
     */
    fun anchor(line: String): Anchored {
        val lyric = StringBuilder()
        val chords = mutableListOf<Pair<Int, String>>()
        var pos = 0
        for (m in CHORD.findAll(line)) {
            lyric.append(line, pos, m.range.first)
            pos = m.range.last + 1
            if ((lyric.isEmpty() || lyric.endsWith(" ")) && pos < line.length && line[pos] == ' ') pos++
            chords += lyric.length to m.groupValues[1]
        }
        lyric.append(line.substring(pos))
        return Anchored(lyric.toString().trimEnd(), chords)
    }

    /** Xếp hợp âm vào một dòng theo cột; hợp âm chồng lên hợp âm trước bị đẩy sang phải 1 khoảng trắng. */
    private fun layout(chords: List<Pair<Int, String>>): String {
        val sb = StringBuilder()
        for ((col0, name) in chords) {
            var col = col0
            if (sb.isNotEmpty() && col <= sb.length) col = sb.length + 1
            while (sb.length < col) sb.append(' ')
            sb.append(name)
        }
        return sb.toString()
    }

    /**
     * Tách một dòng có hợp âm inline thành (dòng hợp âm, dòng lời) để hiển thị hợp âm phía trên,
     * dùng font monospace. Dòng không có hợp âm trả về ("", dòng gốc).
     */
    fun splitAbove(line: String): Pair<String, String> {
        if (!CHORD.containsMatchIn(line)) return "" to line
        val a = anchor(line)
        return layout(a.chords) to a.lyric
    }

    /**
     * Ngắt một dòng thành nhiều cặp (hợp âm, lời) vừa [maxCols] cột, hợp âm luôn đứng đúng trên ký tự
     * nó được neo vào. Ưu tiên ngắt ở khoảng trắng; nếu hợp âm cuối đoạn tràn quá độ rộng thì lùi điểm ngắt.
     * Dòng trống trả về ("", ""); dòng chỉ có hợp âm được xếp thành các dòng hợp âm vừa độ rộng.
     */
    fun wrapAbove(line: String, maxCols: Int): List<Pair<String, String>> {
        val w = maxCols.coerceAtLeast(8)
        if (!CHORD.containsMatchIn(line)) return wrapPlain(line, w).map { "" to it }
        val a = anchor(line)
        val lyric = a.lyric

        if (lyric.isBlank()) {               // dòng chỉ có hợp âm (dạo đầu, gian tấu)
            val out = mutableListOf<Pair<String, String>>()
            var cur = StringBuilder()
            for ((_, name) in a.chords) {
                if (cur.isNotEmpty() && cur.length + 1 + name.length > w) { out += cur.toString() to ""; cur = StringBuilder() }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(name)
            }
            out += cur.toString() to ""
            return out
        }

        fun chordsIn(start: Int, endExcl: Int, last: Boolean) =
            a.chords.filter { (i, _) -> i >= start && (last || i < endExcl) }.map { (i, n) -> (i - start) to n }

        val out = mutableListOf<Pair<String, String>>()
        var s = 0
        while (true) {
            if (lyric.length - s <= w) {
                val ch = layout(chordsIn(s, Int.MAX_VALUE, last = true))
                // phần còn lại vừa (kể cả hợp âm đứng sau chữ cuối) hoặc không còn chỗ ngắt -> kết thúc
                if (ch.length <= w || lyric.lastIndexOf(' ', lyric.length - 1) <= s) {
                    out += ch to lyric.substring(s)
                    break
                }
            }
            // điểm ngắt: khoảng trắng cuối cùng trong w cột đầu
            var b = lyric.lastIndexOf(' ', s + w)
            while (b > s) {
                val ch = layout(chordsIn(s, b + 1, last = false))
                if (ch.length <= w) break
                b = lyric.lastIndexOf(' ', b - 1)  // hợp âm tràn -> lùi về khoảng trắng trước
            }
            val (end, next) = if (b > s) b to b + 1 else (s + w) to (s + w)   // không có khoảng trắng: cắt cứng
            out += layout(chordsIn(s, next, last = false)) to lyric.substring(s, end).trimEnd()
            s = next
            while (s < lyric.length && lyric[s] == ' ' && a.chords.none { it.first == s }) s++
        }
        return out
    }

    /** Ngắt dòng không có hợp âm ở khoảng trắng (giữ dòng trống là một dòng rỗng). */
    private fun wrapPlain(line: String, w: Int): List<String> {
        if (line.length <= w) return listOf(line)
        val out = mutableListOf<String>()
        var rest = line
        while (rest.length > w) {
            val b = rest.lastIndexOf(' ', w).takeIf { it > 0 } ?: w
            out += rest.substring(0, b).trimEnd()
            rest = rest.substring(b).trimStart()
        }
        out += rest
        return out
    }
}
