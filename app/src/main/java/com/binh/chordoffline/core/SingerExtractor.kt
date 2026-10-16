package com.binh.chordoffline.core

import org.jsoup.nodes.Document

/** Một ca sĩ thể hiện bài hát: tên, tone hát, mã phát của website và mã video YouTube (nếu tìm được trong HTML). */
data class SingerInfo(val name: String, val tone: String?, val playId: String? = null, val youtubeId: String? = null) {
    val youtubeUrl: String? get() = youtubeId?.let { "https://www.youtube.com/watch?v=$it" }
    val thumbnailUrl: String? get() = youtubeId?.let { "https://img.youtube.com/vi/$it/mqdefault.jpg" }
}

/**
 * Đọc danh sách ca sĩ + tone + video từ trang bài hát.
 * Cấu trúc thật (16/10/2026): mỗi ca sĩ là [id^=song-item-] có data-play-id, .song-singer-text, .tone-badge;
 * ảnh video đầu tiên nằm sẵn trong #main_player img (img.youtube.com/vi/ID/...). Video các ca sĩ khác
 * có thể chỉ được nạp bằng JavaScript -> khi đó youtubeId = null (app hiển thị không có ảnh).
 */
object SingerExtractor {
    // ICU: escape "]" "}" khi là ký tự thường
    private val YT_NEAR = Regex(
        """(?:youtube(?:-nocookie)?\.com/(?:embed/|watch\?v=|vi/)|youtu\.be/|img\.youtube\.com/vi/|i\.ytimg\.com/vi/|["'](?:video_?id|videoId|youtube_?id|yt_?id|youtube|video|vid)["']\s*:\s*["'])([\w\-]{11})"""
    )
    private val ID_ATTRS = listOf("data-video-id", "data-youtube-id", "data-youtube", "data-yt", "data-yt-id", "data-video", "data-vid")

    fun extract(doc: Document): List<SingerInfo> {
        val raw by lazy { doc.outerHtml() }
        val mainThumb = doc.selectFirst("#main_player img[src*=youtube.com/vi/], #main_player img[src*=ytimg.com/vi/]")
            ?.attr("src")?.let { YT_NEAR.find(it)?.groupValues?.get(1) }

        val fromItems = doc.select("[id^=song-item-]").mapNotNull { item ->
            val nameEl = item.selectFirst(".song-singer-text")
            val name = (nameEl?.attr("title")?.ifBlank { null } ?: nameEl?.text())?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val tone = item.selectFirst(".tone-badge")?.text()?.trim()?.ifEmpty { null }
            val playId = item.attr("data-play-id").ifBlank { null }
            // 1) mã video ghi thẳng trên phần tử; 2) trong script/JSON gần data-play-id
            val direct = (listOf(item) + item.select("*")).firstNotNullOfOrNull { el ->
                ID_ATTRS.firstNotNullOfOrNull { a -> el.attr(a).takeIf { it.isNotBlank() } }
                    ?.let { v -> if (v.length == 11) v else YT_NEAR.find(v)?.groupValues?.get(1) }
            }
            val near = if (direct == null && playId != null) findNear(raw, playId) else null
            SingerInfo(name, tone, playId, direct ?: near)
        }.distinctBy { it.name to it.tone }

        val base = fromItems.ifEmpty {
            doc.select("#inline-singer-tones button").mapNotNull { b ->
                val sp = b.select("span")
                if (sp.size >= 2) SingerInfo(sp[0].text().trim(), sp[1].text().trim()) else null
            }
        }
        // Video đầu tiên server render sẵn trong #main_player -> gán cho ca sĩ đầu tiên nếu chưa có
        return if (mainThumb != null && base.isNotEmpty() && base.none { it.youtubeId == mainThumb } && base[0].youtubeId == null)
            listOf(base[0].copy(youtubeId = mainThumb)) + base.drop(1)
        else base
    }

    /** Tìm mã YouTube trong 400 ký tự quanh mỗi lần xuất hiện của play-id (dữ liệu playlist trong script). */
    private fun findNear(raw: String, playId: String): String? {
        var i = raw.indexOf(playId)
        while (i >= 0) {
            val window = raw.substring(maxOf(0, i - 200), minOf(raw.length, i + 400))
            if (!window.contains("data-play-id=\"$playId\"")) YT_NEAR.find(window)?.let { return it.groupValues[1] }
            i = raw.indexOf(playId, i + playId.length)
        }
        return null
    }

    // ---- lưu trong file .cho: {singers: Tên|Tone|playId|youtubeId ;; ...} ----
    private fun clean(s: String?) = s.orEmpty().replace("|", "/").replace(";;", ";").replace("\n", " ").trim()

    fun toDirective(list: List<SingerInfo>): String? =
        list.takeIf { it.isNotEmpty() }?.joinToString(" ;; ") { listOf(clean(it.name), clean(it.tone), clean(it.playId), clean(it.youtubeId)).joinToString("|") }

    /** Đọc lại từ meta của file .cho; bài lưu bằng bản cũ chỉ có singer_tones (không có video). */
    fun fromMeta(meta: Map<String, String>): List<SingerInfo> {
        meta["singers"]?.let { v ->
            return v.split(" ;; ").mapNotNull { e ->
                val f = e.split("|")
                f.getOrNull(0)?.trim()?.ifEmpty { null }?.let { SingerInfo(it, f.getOrNull(1)?.ifBlank { null }, f.getOrNull(2)?.ifBlank { null }, f.getOrNull(3)?.ifBlank { null }) }
            }
        }
        return meta["singer_tones"]?.split(";")?.mapNotNull { e ->
            val t = e.trim(); if (t.isEmpty()) null
            else SingerInfo(t.substringBeforeLast(":").trim(), t.substringAfterLast(":", "").trim().ifEmpty { null })
        }.orEmpty()
    }
}
