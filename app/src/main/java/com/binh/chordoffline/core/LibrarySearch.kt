package com.binh.chordoffline.core

import java.text.Normalizer

enum class SearchField(val label: String) { ALL("Tất cả"), TITLE("Tên bài"), SINGER("Ca sĩ"), COMPOSER("Nhạc sĩ") }

enum class SortMode(val label: String) { RECENT("Mới lưu"), TITLE("Tên A–Z"), COMPOSER("Nhạc sĩ A–Z"), SINGER("Ca sĩ A–Z") }

/** Tìm kiếm & sắp xếp bài đã lưu, hoàn toàn offline. Không phân biệt hoa/thường và dấu tiếng Việt. */
object LibrarySearch {
    private val MARKS = Regex("""\p{Mn}+""")
    private val SPACES = Regex("""\s+""")

    /** Bộ nhớ đệm bản bỏ dấu: thư viện hàng chục nghìn bài -> không tính lại mỗi lần gõ/sắp xếp. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val CACHE_MAX = 200_000

    /** "Người Dưng" -> "nguoi dung"; "Đ/đ" -> "d". */
    fun fold(s: String?): String {
        if (s.isNullOrBlank()) return ""
        cache[s]?.let { return it }
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")
        val r = n.replace('đ', 'd').replace('Đ', 'd').lowercase().replace(SPACES, " ").trim()
        if (cache.size > CACHE_MAX) cache.clear()
        cache[s] = r
        return r
    }

    private fun fields(s: SavedSong, f: SearchField): String = when (f) {
        SearchField.TITLE -> s.title
        SearchField.SINGER -> s.singers.orEmpty()
        SearchField.COMPOSER -> s.composers.orEmpty()
        SearchField.ALL -> listOfNotNull(s.title, s.singers, s.composers).joinToString(" ")
    }

    /** Mọi từ trong truy vấn đều phải xuất hiện (AND). Truy vấn rỗng -> giữ tất cả. */
    fun filter(songs: List<SavedSong>, query: String, field: SearchField = SearchField.ALL, sort: SortMode = SortMode.RECENT): List<SavedSong> {
        val tokens = fold(query).split(' ').filter { it.isNotEmpty() }
        val hit = if (tokens.isEmpty()) songs else songs.filter { s ->
            val hay = fold(fields(s, field)); tokens.all { it in hay }
        }
        val byTitle = compareBy<SavedSong> { fold(it.title) }
        return when (sort) {
            SortMode.RECENT -> hit.sortedByDescending { it.lastSavedMillis }
            SortMode.TITLE -> hit.sortedWith(byTitle)
            // bài thiếu thông tin xếp cuối
            SortMode.COMPOSER -> hit.sortedWith(compareBy<SavedSong>({ it.composers.isNullOrBlank() }, { fold(it.composers) }).then(byTitle))
            SortMode.SINGER -> hit.sortedWith(compareBy<SavedSong>({ it.singers.isNullOrBlank() }, { fold(it.singers) }).then(byTitle))
        }
    }

    /**
     * Gợi ý khi đang gõ (giống ô tìm kiếm của website): tối đa [limit] bài, ưu tiên tên bài bắt đầu bằng
     * truy vấn, rồi tên bài chứa truy vấn, rồi khớp ca sĩ/nhạc sĩ.
     */
    fun suggest(songs: List<SavedSong>, query: String, limit: Int = 6): List<SavedSong> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        fun rank(s: SavedSong): Int {
            val t = fold(s.title)
            return when { t.startsWith(q) -> 0; t.contains(q) -> 1; else -> 2 }
        }
        // Lọc trước (không sắp xếp cả thư viện), rồi chỉ sắp xếp các bài khớp theo hạng + tên.
        val hits = filter(songs, query, SearchField.ALL, SortMode.RECENT)
        return hits.sortedWith(compareBy<SavedSong>({ rank(it) }, { fold(it.title) })).take(limit)
    }
}
