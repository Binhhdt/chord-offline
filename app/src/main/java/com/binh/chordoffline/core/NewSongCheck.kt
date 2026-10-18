package com.binh.chordoffline.core

/**
 * Kiểm tra bài mới trên Hợp Âm Việt qua trang "Hợp âm mới nhất" (/chord/latest — robots.txt cho phép):
 * trang liệt kê ~150 bài mới chia sẻ gần nhất, 30 bài/trang, bài mới nhất ở trên cùng.
 * Đọc từ trang 1; gặp trang mà MỌI bài đều đã có trong máy/hàng đợi thì dừng -> thường chỉ 1–2 request.
 * Dùng chung cho nút "Kiểm tra bài mới" và việc tự kiểm tra mỗi ngày (chạy nền).
 */
object NewSongCheck {
    const val URL = "https://hopamviet.vn/chord/latest"
    const val MAX_PAGES = 5

    /** Kết quả tải một trang: mã HTTP, nội dung, lỗi mạng. */
    data class Fetched(val status: Int?, val body: String?, val error: String?)

    data class Result(
        val newSongs: List<FoundSong>,
        val pagesRead: Int,
        /** Đã gặp bài đã có trong máy -> danh sách bài mới là đầy đủ. */
        val reachedKnown: Boolean,
        /** Đọc hết các trang mà chưa gặp bài nào đã có: có thể còn bài mới cũ hơn đã trôi khỏi trang "mới nhất". */
        val maybeGap: Boolean,
        val checkedAt: Long,
        val error: String? = null,
    )

    /**
     * [fetch]: tải một URL (chạy đồng bộ, ở luồng nền). [isKnown]: bài đã lưu hoặc đã có trong hàng đợi.
     * [pause]: nghỉ giữa hai trang (lịch sự với website). [allowed]: kiểm tra robots.txt cho đường dẫn.
     */
    fun run(
        fetch: (String) -> Fetched,
        isKnown: (String) -> Boolean,
        pause: () -> Unit,
        now: () -> Long = System::currentTimeMillis,
        allowed: (String) -> Boolean = { RobotsRules.FALLBACK_HOPAMVIET.isAllowed(it) },
        maxPages: Int = MAX_PAGES,
    ): Result {
        val found = LinkedHashMap<String, FoundSong>()
        var url: String? = URL
        var pages = 0
        var reachedKnown = false
        while (url != null && pages < maxPages) {
            if (!allowed(UrlPolicy.path(url))) return Result(found.values.toList(), pages, reachedKnown, false, now(),
                "robots.txt không cho phép ${UrlPolicy.path(url)}")
            if (pages > 0) pause()
            val f = fetch(url)
            pages++
            if (BatchQueue.isBlockingResponse(f.status, f.body))
                return Result(found.values.toList(), pages, reachedKnown, false, now(),
                    "Website đang giới hạn/chặn (HTTP ${f.status ?: "?"}) — đã dừng, không thử vượt qua.")
            val body = f.body
            if (f.error != null || body == null || f.status == null || f.status !in 200..299)
                return Result(found.values.toList(), pages, reachedKnown, false, now(),
                    "Không tải được trang ${pages}: " + (f.error ?: "HTTP ${f.status}"))
            val page = ListingParser.parse(body, url)
            if (pages == 1 && page.songs.isEmpty())
                return Result(emptyList(), pages, false, false, now(), "Không đọc được danh sách bài trên trang \"Hợp âm mới nhất\" (cấu trúc trang có thể đã đổi).")
            var newHere = 0
            for (s in page.songs) {
                val k = BatchQueue.dedupeKey(s.url)
                if (isKnown(s.url)) { reachedKnown = true; continue }
                if (found.putIfAbsent(k, s) == null) newHere++
            }
            if (newHere == 0 || reachedKnown) break     // gặp bài đã có -> phía sau đều là bài cũ hơn
            url = page.nextUrl
        }
        return Result(found.values.toList(), pages, reachedKnown, maybeGap = !reachedKnown && found.isNotEmpty(), checkedAt = now())
    }

    // ---------- Lưu kết quả lần kiểm tra gần nhất (dùng chung giữa app và việc chạy nền) ----------
    private const val HEADER = "#chord-offline-new-songs v1"

    fun serialize(r: Result): String = buildString {
        append(HEADER).append('\n')
        append("#meta\t").append(r.checkedAt).append('\t').append(r.pagesRead).append('\t')
            .append(if (r.reachedKnown) 1 else 0).append('\t').append(if (r.maybeGap) 1 else 0).append('\t')
            .append(clean(r.error.orEmpty())).append('\n')
        for (s in r.newSongs) append(clean(s.url)).append('\t').append(clean(s.title)).append('\t').append(clean(s.artist.orEmpty())).append('\n')
    }

    fun parse(text: String): Result? {
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return null
        val meta = lines.getOrNull(1)?.takeIf { it.startsWith("#meta\t") }?.split('\t') ?: return null
        val songs = lines.drop(2).filter { it.isNotBlank() }.mapNotNull { l ->
            val f = l.split('\t'); if (f.size < 2) null else FoundSong(f[0], f[1], f.getOrNull(2)?.ifEmpty { null })
        }
        return Result(songs, meta.getOrNull(2)?.toIntOrNull() ?: 0, meta.getOrNull(3) == "1", meta.getOrNull(4) == "1",
            meta.getOrNull(1)?.toLongOrNull() ?: 0L, meta.getOrNull(5)?.ifEmpty { null })
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
