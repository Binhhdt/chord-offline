package com.binh.chordoffline.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Kết quả kiểm tra một selector trên một tài liệu HTML. */
data class SelectorResult(
    val selector: String,
    val found: Boolean,
    val count: Int,
    val detail: String?,
)

object SelectorCheck {
    /** Các selector bắt buộc theo yêu cầu kiểm tra. */
    val REQUIRED = listOf(
        "#lyricBox",
        "#lyric-version-0",
        "#songHeaderCard h1",
        "#song-tone",
        "#btn-like[data-song-id]",
    )

    fun check(html: String): List<SelectorResult> = check(Jsoup.parse(html))

    fun check(doc: Document): List<SelectorResult> = REQUIRED.map { sel ->
        val els = doc.select(sel)
        val first = els.firstOrNull()
        val detail = when {
            first == null -> null
            sel == "#lyric-version-0" -> {
                val lyr = SongParser.extractLyrics(doc)
                if (lyr == null) "phần tử có nhưng RỖNG"
                else "${lyr.lines().size} dòng, ${ChordText.CHORD.findAll(lyr).count()} hợp âm inline, " +
                    "${first.select("span.chord").size} span.chord"
            }
            sel == "#lyricBox" -> "${first.select(".lyric-version").size} khối .lyric-version, " +
                "${first.text().length} ký tự text"
            sel == "#btn-like[data-song-id]" -> "data-song-id=${first.attr("data-song-id")}"
            else -> first.text().trim().take(80).ifEmpty { "(rỗng)" }
        }
        SelectorResult(sel, els.isNotEmpty(), els.size, detail)
    }
}

enum class Presence(val label: String) {
    BOTH("Có trong HTML server"),
    SAVED_ONLY("Chỉ có trong bản đã lưu (do JavaScript tạo/sửa)"),
    SERVER_ONLY("Chỉ có trong HTML server"),
    NEITHER("Không có ở cả hai"),
}

data class ProbeRow(
    val label: String,
    val selector: String,
    val server: String,
    val saved: String,
    val presence: Presence,
    val differs: Boolean,
)

data class ComparisonReport(
    val rows: List<ProbeRow>,
    /** null nếu một trong hai bên không có lời. */
    val lyricsIdentical: Boolean?,
    val serverLyricLines: Int,
    val savedLyricLines: Int,
    val serverBytes: Int,
    val savedBytes: Int,
)

/**
 * So sánh HTML server trả về (tải trực tiếp, không chạy JS) với file HTML đã lưu từ trình duyệt
 * (DOM sau khi JS chạy). Phần tử có ở server = server render; chỉ có ở bản lưu = JS thêm vào.
 */
object HtmlComparison {

    private class Probe(val label: String, val selector: String, val measure: (Document) -> Pair<Int, String>)

    private fun count(sel: String): (Document) -> Pair<Int, String> = { d -> d.select(sel).size.let { it to "$it" } }
    private fun text(sel: String): (Document) -> Pair<Int, String> = { d ->
        val e = d.select(sel); e.size to (e.firstOrNull()?.text()?.trim()?.take(40) ?: "—")
    }

    private val PROBES = listOf(
        Probe("Khung lời", "#lyricBox", count("#lyricBox")),
        Probe("Khối lời", "#lyric-version-0", { d ->
            val lyr = SongParser.extractLyrics(d)
            (if (lyr == null) 0 else 1) to (lyr?.let { "${it.lines().size} dòng" } ?: "rỗng/không có")
        }),
        Probe("Hợp âm inline", "#lyric-version-0 span.chord", count("#lyric-version-0 span.chord")),
        Probe("Tên bài", "#songHeaderCard h1", text("#songHeaderCard h1")),
        Probe("Tone", "#song-tone", text("#song-tone")),
        Probe("ID bài (nút thích)", "#btn-like[data-song-id]", { d ->
            val e = d.select("#btn-like[data-song-id]"); e.size to (e.firstOrNull()?.attr("data-song-id") ?: "—")
        }),
        Probe("Điệu", "#currentRhythmLabelMobile", text("#currentRhythmLabelMobile")),
        Probe("Tone ca sĩ", "#inline-singer-tones button", count("#inline-singer-tones button")),
        Probe("Danh sách hợp âm sử dụng", "#show_tab [chord]", count("#show_tab [chord]")),
        Probe("Sơ đồ hợp âm đã vẽ (jtab)", "#show_tab .jtab.rendered", count("#show_tab .jtab.rendered")),
        Probe("SVG sơ đồ hợp âm", "#show_tab svg", count("#show_tab svg")),
        Probe("Quảng cáo đã nạp", "ins.adsbygoogle[data-adsbygoogle-status]", count("ins.adsbygoogle[data-adsbygoogle-status]")),
        Probe("iframe", "iframe", count("iframe")),
        Probe("Trình phát YouTube", "#movie_player, #player iframe", count("#movie_player, #player iframe")),
        Probe("Ảnh thumbnail YouTube", "img[src*=img.youtube.com]", count("img[src*=img.youtube.com]")),
        Probe("Bài liên quan", ".song-card", count(".song-card")),
        Probe("Thẻ <script>", "script", count("script")),
    )

    fun compare(serverHtml: String, savedHtml: String): ComparisonReport {
        val s = Jsoup.parse(serverHtml)
        val v = Jsoup.parse(savedHtml)
        val rows = PROBES.map { p ->
            val (sc, sv) = p.measure(s)
            val (vc, vv) = p.measure(v)
            val presence = when {
                sc > 0 && vc > 0 -> Presence.BOTH
                sc > 0 -> Presence.SERVER_ONLY
                vc > 0 -> Presence.SAVED_ONLY
                else -> Presence.NEITHER
            }
            ProbeRow(p.label, p.selector, sv, vv, presence, differs = sv != vv)
        }
        val sl = SongParser.extractLyrics(s)
        val vl = SongParser.extractLyrics(v)
        return ComparisonReport(
            rows = rows,
            lyricsIdentical = if (sl != null && vl != null) sl == vl else null,
            serverLyricLines = sl?.lines()?.size ?: 0,
            savedLyricLines = vl?.lines()?.size ?: 0,
            serverBytes = serverHtml.toByteArray().size,
            savedBytes = savedHtml.toByteArray().size,
        )
    }
}

/** Giải thích vì sao không lấy được lời — chỉ dựa trên dữ liệu thực tế đã tải. */
object Diagnosis {
    fun explainMissingLyrics(status: Int?, contentType: String?, html: String?, error: String?): String {
        if (error != null) return "Không tải được: $error"
        if (status == null) return "Chưa có phản hồi HTTP."
        if (status == 403 || status == 429 || status == 503)
            return "Máy chủ trả HTTP $status (từ chối/giới hạn truy cập). App không thử vượt qua giới hạn này."
        if (status !in 200..299) return "Máy chủ trả HTTP $status, không phải trang bài hát."
        if (contentType != null && !contentType.contains("html", ignoreCase = true))
            return "Content-Type là \"$contentType\", không phải HTML."
        val h = html.orEmpty()
        val lower = h.lowercase()
        if (listOf("cf-chl", "challenge-platform", "captcha", "just a moment", "attention required").any { it in lower })
            return "Phản hồi là trang xác minh/chống bot, không phải nội dung bài hát. App không vượt qua bước xác minh."
        val doc = Jsoup.parse(h)
        if (doc.selectFirst("#lyricBox") == null)
            return "HTML server không có #lyricBox — có thể cấu trúc trang đã đổi hoặc URL không phải trang bài hát."
        if (SongParser.lyricElement(doc) == null)
            return "#lyricBox có nhưng không có #lyric-version-0 — có thể lời được nạp bằng JavaScript sau khi mở trang."
        return "#lyric-version-0 có nhưng rỗng — lời có thể được nạp bằng JavaScript (không có trong HTML server)."
    }
}
