package com.binh.chordoffline.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Dữ liệu một bài hát lấy từ HTML trang bài hát. Mọi trường đều có thể null nếu HTML thiếu. */
data class ParsedSong(
    val sourceUrl: String?,
    val slugId: String?,          // ví dụ W8IUDI0D (phần cuối URL)
    val songId: String?,          // data-song-id, ví dụ 49676
    val title: String?,
    val composers: List<String>,
    val category: String?,
    val rhythm: String?,
    val tone: String?,            // tone của bản hợp âm, ví dụ Am
    val singerTones: List<Pair<String, String>>,
    val chordsUsed: List<String>,
    val youtubeId: String?,
    /** Lời với hợp âm inline dạng [Am], giữ nguyên dòng trống. null nếu không có trong HTML. */
    val lyrics: String?,
    /** Ca sĩ + tone + video YouTube (nếu có trong HTML). Thêm ở v0.4.0, mặc định rỗng để tương thích. */
    val singers: List<SingerInfo> = emptyList(),
) {
    val lyricLineCount: Int get() = lyrics?.lines()?.size ?: 0
    val inlineChordCount: Int get() = lyrics?.let { ChordText.CHORD.findAll(it).count() } ?: 0
    val hasLyrics: Boolean get() = !lyrics.isNullOrBlank()
}

object SongParser {

    fun parse(html: String, baseUrl: String? = null): ParsedSong = parse(Jsoup.parse(html, baseUrl ?: ""))

    fun parse(doc: Document): ParsedSong {
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")?.takeIf { it.isNotBlank() }
        val tone = doc.selectFirst("#song-tone")?.text()?.trim()?.removePrefix("[")?.removeSuffix("]")
            ?.trim()?.takeIf { it.isNotEmpty() }

        val composers = doc.select("#mobileMetadata a[href*=/chord/composer/]").map { it.text().trim() }
            .ifEmpty { doc.select("#song-header-badges a[href*=/chord/composer/]").map { it.text().trim() } }
            .filter { it.isNotEmpty() }.distinct()

        val singerTones = doc.select("#inline-singer-tones button").mapNotNull { b ->
            val spans = b.select("span")
            if (spans.size >= 2) spans[0].text().trim() to spans[1].text().trim() else null
        }

        val youtube = Regex("""(?:img\.youtube\.com/vi/|youtube\.com/embed/)([\w\-]{11})""")
            .find(doc.outerHtml())?.groupValues?.get(1)

        return ParsedSong(
            sourceUrl = canonical,
            slugId = canonical?.substringAfterLast('/')?.removeSuffix(".html")?.takeIf { it.isNotBlank() },
            songId = doc.selectFirst("#btn-like[data-song-id]")?.attr("data-song-id")
                ?: doc.selectFirst("[data-song-id]")?.attr("data-song-id"),
            title = doc.selectFirst("#songHeaderCard h1")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            composers = composers,
            category = doc.selectFirst("#song-header-badges a[href*=/chord/category/]")?.text()?.trim(),
            rhythm = doc.selectFirst("#currentRhythmLabelMobile")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            tone = tone,
            singerTones = singerTones,
            chordsUsed = doc.select("#show_tab [chord]").map { it.attr("chord").trim() }
                .filter { it.isNotEmpty() }.distinct(),
            youtubeId = youtube,
            lyrics = extractLyrics(doc),
            singers = runCatching { SingerExtractor.extract(doc) }.getOrDefault(emptyList()),
        )
    }

    /** Phần tử chứa lời: #lyric-version-0, dự phòng .lyric-version đầu tiên trong #lyricBox. */
    fun lyricElement(doc: Document): Element? =
        doc.selectFirst("#lyric-version-0") ?: doc.selectFirst("#lyricBox .lyric-version")

    fun extractLyrics(doc: Document): String? {
        val el = lyricElement(doc)?.clone() ?: return null
        // <br> -> xuống dòng (trang hiện dùng \n thật, nhưng phòng khi đổi sang <br>)
        el.select("br").forEach { it.replaceWith(TextNode("\n")) }
        // <span class="chord">[Am]</span> -> [Am] (thêm ngoặc nếu trang bỏ ngoặc)
        el.select("span.chord").forEach { span ->
            val t = span.text().trim()
            val c = if (t.startsWith("[") && t.endsWith("]")) t else "[$t]"
            span.replaceWith(TextNode(c))
        }
        // Khối con dạng block (div/p) -> thêm xuống dòng sau mỗi khối
        el.select("div, p").forEach { it.appendChild(TextNode("\n")) }
        val text = normalize(el.wholeText())
        return text.takeIf { it.isNotBlank() }
    }

    /** Chuẩn hoá xuống dòng, bỏ khoảng trắng cuối dòng, giữ nguyên dòng trống bên trong. */
    fun normalize(raw: String): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ')
            .split('\n').map { it.trimEnd() }
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return ""
        val last = lines.indexOfLast { it.isNotBlank() }
        return lines.subList(first, last + 1).joinToString("\n")
    }
}
