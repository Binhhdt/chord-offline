package com.binh.chordoffline.core

import java.io.File

/**
 * Sổ mục lục thư viện: thông tin mọi bài đã lưu trong MỘT file (một dòng mỗi bài).
 * Mở app chỉ cần đọc file này thay vì mở ~5 file của từng bài (47.000 bài ≈ 240.000 lần đọc file).
 * Đây chỉ là bản sao để tăng tốc — dữ liệu thật vẫn nằm trong thư mục từng bài; mục lục hỏng/thiếu thì dựng lại.
 */
object LibraryIndex {
    const val HEADER = "#chord-offline-library v1"

    fun serialize(songs: List<SavedSong>): String = buildString(songs.size * 160) {
        append(HEADER).append('\n')
        for (s in songs) {
            val f = listOf(s.key, s.title, s.sourceUrl.orEmpty(), s.composers.orEmpty(), s.singers.orEmpty(), s.tone.orEmpty()) +
                status(s.page) + status(s.compact)
            f.forEachIndexed { i, v -> if (i > 0) append('\t'); esc(v, this) }
            append('\n')
        }
    }

    private fun status(v: VersionStatus) =
        listOf(v.state.name, v.savedAtMillis?.toString().orEmpty(), v.bytes?.toString().orEmpty(), v.error.orEmpty())

    /** null nếu file không đúng định dạng (khi đó app dựng lại mục lục từ thư mục bài). */
    fun parse(text: String): List<SavedSong>? {
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) return null
        val out = ArrayList<SavedSong>(lines.size)
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val f = line.split('\t').map(::unesc)
            if (f.size < 14) return null
            fun st(o: Int) = VersionStatus(
                runCatching { SaveState.valueOf(f[o]) }.getOrDefault(SaveState.NOT_SAVED),
                f[o + 1].toLongOrNull(), f[o + 2].toLongOrNull(), f[o + 3].ifEmpty { null })
            out += SavedSong(f[0], f[1], f[2].ifEmpty { null }, st(6), st(10),
                composers = f[3].ifEmpty { null }, singers = f[4].ifEmpty { null }, tone = f[5].ifEmpty { null })
        }
        return out
    }

    fun read(file: File): List<SavedSong>? = runCatching { if (file.isFile) parse(file.readText(Charsets.UTF_8)) else null }.getOrNull()

    /** Ghi kiểu tạm-rồi-đổi-tên để tắt app giữa chừng không làm hỏng mục lục. */
    fun write(file: File, songs: List<SavedSong>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(serialize(songs), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /**
     * Đối chiếu mục lục với danh sách thư mục bài thực có (một lần liệt kê thư mục — rất nhanh):
     * bỏ bài không còn thư mục; trả về các mã bài có thư mục nhưng chưa có trong mục lục (cần đọc thêm).
     */
    fun reconcile(index: List<SavedSong>, dirKeys: Collection<String>): Pair<List<SavedSong>, List<String>> {
        val dirs = dirKeys.toHashSet()
        val kept = index.filter { it.key in dirs }
        val known = kept.mapTo(HashSet()) { it.key }
        return kept to dirKeys.filter { it !in known }
    }

    private fun esc(s: String, sb: StringBuilder) {
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\"); '\t' -> sb.append("\\t"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
    }

    private fun unesc(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> s[i + 1] }); i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
