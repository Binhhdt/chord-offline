package com.binh.chordoffline.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/** Một bài hát tìm thấy trên trang danh sách. */
data class FoundSong(val url: String, val title: String, val artist: String?)

/** Kết quả phân tích một trang danh sách. */
data class ListingPage(
    val songs: List<FoundSong>,
    /** URL trang kế tiếp (page+1) nếu trang có link phân trang tương ứng; null nếu hết. */
    val nextUrl: String?,
    /** "main" = chỉ lấy trong cột chính (lg:col-span-8); "fallback" = không thấy cột chính, đã loại aside/nav/header/footer. */
    val method: String,
    /** Số link bài bị loại vì nằm ở cột bên (Bài hát hot/Thịnh hành), menu, chân trang. */
    val excluded: Int,
    /** Tổng số trang của danh sách đọc từ thanh phân trang (ví dụ 599); null nếu không thấy. */
    val lastPage: Int? = null,
)

/**
 * Chỉ cho phép trang danh sách mà robots.txt cho phép và có giới hạn phạm vi rõ ràng.
 * Trang tìm kiếm /chord/search bị robots.txt của hopamviet.vn chặn nên bị từ chối.
 */
object ListingPolicy {
    private val HOSTS = setOf("hopamviet.vn", "www.hopamviet.vn")
    val ALLOWED_PREFIXES = listOf("/chord/category/", "/chord/composer/", "/chord/singer/", "/chord/rhythm/", "/chord/hot", "/chord/latest")
    const val MAX_PAGES_LIMIT = 20

    fun validate(url: String): String? {
        val u = try { URI(BatchQueue.normalizeUrl(url)) } catch (e: Exception) { return "URL không hợp lệ." }
        if (u.host?.lowercase() !in HOSTS) return "Chỉ hỗ trợ trang danh sách của hopamviet.vn."
        val path = u.rawPath ?: ""
        if (path.startsWith("/chord/search")) return "Trang tìm kiếm (/chord/search) bị robots.txt của Hợp Âm Việt chặn, app không tải. " +
            "Hãy dùng trang thể loại, nhạc sĩ, ca sĩ hoặc điệu (ví dụ https://hopamviet.vn/chord/category/3/nhac-tre.html)."
        if (path.startsWith("/chord/song/")) return "Đây là trang một bài hát — dùng ô \"Dán link\" hoặc tab Kiểm tra URL."
        if (ALLOWED_PREFIXES.none { path.startsWith(it) })
            return "Chỉ hỗ trợ trang thể loại, nhạc sĩ, ca sĩ, điệu, bài hot, bài mới (/chord/category/…, /chord/composer/…, /chord/singer/…, /chord/rhythm/…)."
        return null
    }

    /** Chỉ số trang (tham số ?page=, đánh số từ 0; không có = 0). */
    fun pageIndex(url: String): Int =
        Regex("""[?&]page=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Khoá so trùng trang danh sách: bỏ ".html", "www.", #fragment; giữ page. "nhac-tre.html" == "nhac-tre?page=0". */
    fun pageKey(url: String): String {
        val u = URI(BatchQueue.normalizeUrl(url))
        val path = (u.rawPath ?: "").removeSuffix(".html").trimEnd('/').lowercase()
        return "${u.host?.lowercase()?.removePrefix("www.")}$path#${pageIndex(url)}"
    }

    fun listKey(url: String): String = pageKey(url).substringBefore('#')

    /** URL trang thứ [index] (đánh số từ 0) của cùng danh sách, theo dạng website dùng: ".../nhac-tre?page=N". */
    fun withPage(url: String, index: Int): String {
        val u = URI(BatchQueue.normalizeUrl(url))
        val path = (u.rawPath ?: "").removeSuffix(".html")
        return "https://${u.host?.lowercase()?.removePrefix("www.")}$path" + if (index > 0) "?page=$index" else ""
    }
}

object ListingParser {
    private val SONG_PATH = Regex("""^/chord/song/[^/]+/[A-Za-z0-9]+\.html$""")
    private val EXCLUDE = setOf("aside", "nav", "header", "footer")

    private fun inExcluded(a: Element): Boolean = a.parents().any { p ->
        p.tagName() in EXCLUDE || p.id().contains("modal", ignoreCase = true) || p.id().startsWith("mobileMenu")
    }

    /** Chuẩn hoá link bài hát; null nếu không phải trang bài hát hợp lệ. */
    fun songUrl(href: String, base: String): String? {
        val abs = try { URI(base).resolve(href.trim()).toString() } catch (e: Exception) { return null }
        val norm = BatchQueue.normalizeUrl(abs).substringBefore('?')
        val u = try { URI(norm) } catch (e: Exception) { return null }
        if (u.host?.lowercase() != "hopamviet.vn") return null
        if (!SONG_PATH.matches(u.rawPath ?: "")) return null
        return norm
    }

    fun parse(html: String, pageUrl: String): ListingPage {
        val doc = Jsoup.parse(html, pageUrl)
        val main = doc.allElements.firstOrNull { it.hasClass("lg:col-span-8") }
        val scope = main ?: doc.body() ?: doc
        val allSongLinks = doc.select("a[href*=/chord/song/]")
        val hrefs = doc.select("a[href]").map { it.attr("abs:href") }
        val lastPage = lastPage(hrefs, pageUrl)

        val seen = linkedMapOf<String, FoundSong>()
        for (a in scope.select("a[href*=/chord/song/]")) {
            if (inExcluded(a)) continue
            val url = songUrl(a.attr("href"), pageUrl) ?: continue
            if (url in seen) continue
            val title = a.selectFirst("[class*=font-bold]")?.text()?.trim()?.ifEmpty { null }
                ?: a.attr("title").trim().ifEmpty { null }
                ?: a.ownText().trim().ifEmpty { null }
                ?: a.text().trim().take(80)
            // Thẻ bài của website: dòng "text-[13px]" chứa nhãn HOT (tuỳ bài) + <span class="truncate">Ca sĩ</span>
            val artistLine = a.select("div").firstOrNull { it.className().contains("text-[13px]") }
            val artist = (artistLine?.select("span.truncate")?.lastOrNull()?.text()
                ?: artistLine?.ownText())
                ?.trim()?.takeIf { it.isNotEmpty() && it != title && !it.equals("HOT", ignoreCase = true) }
            seen[url] = FoundSong(url, title.removePrefix("♪").trim(), artist)
        }
        return ListingPage(
            songs = seen.values.toList(),
            nextUrl = nextPage(hrefs, pageUrl, lastPage),
            method = if (main != null) "main" else "fallback",
            excluded = allSongLinks.size - scope.select("a[href*=/chord/song/]").count { !inExcluded(it) },
            lastPage = lastPage,
        )
    }

    private fun sameListPageLinks(hrefs: List<String>, pageUrl: String): Sequence<String> {
        val list = ListingPolicy.listKey(pageUrl)
        return hrefs.asSequence()
            .filter { it.contains("page=") }
            .filter { runCatching { ListingPolicy.listKey(it) == list }.getOrDefault(false) }
    }

    /** Tổng số trang = số trang lớn nhất trong thanh phân trang của cùng danh sách (page đánh số từ 0 → +1). */
    fun lastPage(hrefs: List<String>, pageUrl: String): Int? =
        sameListPageLinks(hrefs, pageUrl).map { ListingPolicy.pageIndex(it) }.maxOrNull()
            ?.let { maxOf(it, ListingPolicy.pageIndex(pageUrl)) + 1 }

    /**
     * Trang kế tiếp = link phân trang của CÙNG danh sách có page = trang hiện tại + 1.
     * Nếu thanh phân trang không có link đó nhưng cho biết còn trang phía sau (lastPage), tự tạo URL trang kế
     * theo đúng mẫu "?page=N" của website. Đang ở trang cuối -> null (hết trang).
     */
    fun nextPage(hrefs: List<String>, pageUrl: String, lastPage: Int? = null): String? {
        val want = ListingPolicy.pageIndex(pageUrl) + 1
        sameListPageLinks(hrefs, pageUrl).firstOrNull { ListingPolicy.pageIndex(it) == want }
            ?.let { return BatchQueue.normalizeUrl(it) }
        return if (lastPage != null && want < lastPage) ListingPolicy.withPage(pageUrl, want) else null
    }
}

/**
 * Gom kết quả nhiều trang danh sách, chống vòng lặp và giới hạn phạm vi:
 * không quay lại trang đã quét, dừng khi đủ số trang, hết trang, hoặc trang không có bài mới.
 */
class Discovery(val maxPages: Int, val maxSongs: Int = 600) {
    private val visited = linkedSetOf<String>()
    private val songs = linkedMapOf<String, FoundSong>()
    val pages: Int get() = visited.size
    val found: List<FoundSong> get() = songs.values.toList()
    var stopReason: String? = null; private set
    /** true khi đã quét tới trang cuối của danh sách. */
    var reachedEnd: Boolean = false; private set
    /** Trang để "Quét tiếp" khi dừng vì đủ giới hạn số trang/số bài; null nếu hết trang hoặc dừng vì lý do khác. */
    var resumeUrl: String? = null; private set

    fun alreadyVisited(url: String) = ListingPolicy.pageKey(url) in visited

    /** Ghi nhận một trang; trả số bài MỚI (chưa thấy ở trang trước). */
    fun add(pageUrl: String, page: ListingPage): Int {
        visited += ListingPolicy.pageKey(pageUrl)
        var added = 0
        for (s in page.songs) {
            if (songs.size >= maxSongs) break
            if (songs.putIfAbsent(BatchQueue.dedupeKey(s.url), s) == null) added++
        }
        return added
    }

    /** Có nên tải tiếp trang [next] không; nếu không thì ghi lý do vào [stopReason]. */
    fun shouldContinue(next: String?, lastAdded: Int): Boolean {
        reachedEnd = next == null
        resumeUrl = null
        stopReason = when {
            next == null -> "Đã tới trang cuối — hết trang."
            lastAdded == 0 -> "Trang vừa quét không có bài mới — dừng để tránh lặp."
            alreadyVisited(next) -> "Trang kế tiếp đã quét rồi — dừng để tránh vòng lặp."
            pages >= maxPages -> { resumeUrl = next; "Đã đủ $maxPages trang theo giới hạn đã chọn." }
            songs.size >= maxSongs -> { resumeUrl = next; "Đã đủ $maxSongs bài (giới hạn mỗi lần quét)." }
            else -> null
        }
        return stopReason == null
    }
}
