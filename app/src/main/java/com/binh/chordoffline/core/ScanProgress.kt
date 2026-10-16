package com.binh.chordoffline.core

/** Tiến độ quét của MỘT danh sách (danh mục/nhạc sĩ/ca sĩ…): trang xa nhất đã quét, tổng số trang, trang để quét tiếp. */
data class ScanProgress(
    val listKey: String,
    val url: String,
    val lastScanned: Int,
    val totalPages: Int?,
    val resumeUrl: String?,
    val reachedEnd: Boolean,
    val updatedAt: Long,
) {
    /** Tên ngắn để hiển thị, ví dụ "category/2/nhac-tru-tinh". */
    val label: String get() = listKey.substringAfter("/chord/").ifEmpty { listKey }
    val resumePage: Int? get() = resumeUrl?.let { ListingPolicy.pageIndex(it) + 1 }
}

/**
 * Sổ ghi tiến độ quét các danh sách — lưu ra file để tắt app / cài lại (khôi phục từ thư mục lưu file)
 * vẫn biết đã quét tới trang mấy và quét tiếp đúng chỗ. Luôn giữ trang XA NHẤT đã quét.
 */
data class ScanProgressBook(val entries: Map<String, ScanProgress> = emptyMap()) {

    fun get(url: String): ScanProgress? = runCatching { entries[ListingPolicy.listKey(url)] }.getOrNull()

    val all: List<ScanProgress> get() = entries.values.sortedByDescending { it.updatedAt }

    /** Ghi nhận vừa quét xong trang [pageNo] (1-based). */
    fun record(url: String, pageNo: Int, totalPages: Int?, nextUrl: String?, reachedEnd: Boolean, now: Long): ScanProgressBook {
        val key = ListingPolicy.listKey(url)
        val old = entries[key]
        val further = old == null || pageNo >= old.lastScanned
        val p = ScanProgress(
            listKey = key,
            url = old?.url ?: ListingPolicy.withPage(url, 0),
            lastScanned = maxOf(pageNo, old?.lastScanned ?: 0),
            totalPages = totalPages ?: old?.totalPages,
            resumeUrl = if (further) (if (reachedEnd) null else nextUrl) else old?.resumeUrl,
            reachedEnd = (further && reachedEnd) || (old?.reachedEnd == true),
            updatedAt = now,
        )
        return copy(entries = entries + (key to p))
    }

    fun remove(listKey: String) = copy(entries = entries - listKey)

    /** Gộp với bản sao lưu: mỗi danh sách giữ bản đã quét xa hơn (bằng nhau thì bản mới hơn). */
    fun merge(other: ScanProgressBook): ScanProgressBook {
        val out = entries.toMutableMap()
        for ((k, p) in other.entries) {
            val mine = out[k]
            if (mine == null || p.lastScanned > mine.lastScanned ||
                (p.lastScanned == mine.lastScanned && p.updatedAt > mine.updatedAt)) out[k] = p
        }
        return ScanProgressBook(out)
    }

    fun serialize(): String = buildString {
        append(HEADER).append('\n')
        for (p in all) append(listOf(p.listKey, p.url, p.lastScanned, p.totalPages ?: "", p.resumeUrl ?: "",
            if (p.reachedEnd) 1 else 0, p.updatedAt).joinToString("\t")).append('\n')
    }

    companion object {
        const val HEADER = "#chord-offline-scan v1"
        const val FILE_NAME = "_tien_do_quet.tsv"

        fun parse(text: String): ScanProgressBook {
            val map = linkedMapOf<String, ScanProgress>()
            for (line in text.lines()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val f = line.split('\t')
                if (f.size < 7) continue
                val p = ScanProgress(f[0], f[1], f[2].toIntOrNull() ?: continue, f[3].toIntOrNull(), f[4].ifEmpty { null },
                    f[5] == "1", f[6].toLongOrNull() ?: 0)
                map[p.listKey] = p
            }
            return ScanProgressBook(map)
        }
    }
}
