package com.binh.chordoffline.core

enum class ItemState(val label: String) {
    PENDING("Chờ"), RUNNING("Đang tải"), DONE("Xong"), ERROR("Lỗi"), CANCELLED("Đã huỷ")
}

data class BatchItem(
    val url: String,
    val state: ItemState = ItemState.PENDING,
    val attempts: Int = 0,
    val message: String? = null,
    val title: String? = null,
    val updatedAt: Long = 0,
    /** Thư mục lưu trong SongStore (để mở bài offline sau khi tải xong). */
    val key: String? = null,
)

data class AddResult(val added: Int, val duplicates: Int, val invalid: List<Pair<String, String>>)

/**
 * Hàng đợi tải nhiều URL — dữ liệu thuần, bất biến, lưu được ra file (TSV) để giữ trạng thái khi tắt app.
 * Việc tải thật (tuần tự, nghỉ giữa các request) do ViewModel thực hiện.
 */
data class BatchQueue(
    val items: List<BatchItem> = emptyList(),
    val saveCompact: Boolean = true,
    val savePage: Boolean = false,
) {
    val total get() = items.size
    fun count(s: ItemState) = items.count { it.state == s }
    val finished get() = items.count { it.state == ItemState.DONE || it.state == ItemState.ERROR || it.state == ItemState.CANCELLED }
    val hasPending get() = items.any { it.state == ItemState.PENDING }

    /** Thêm các URL (mỗi dòng một link). Bỏ dòng trống/ghi chú (#), bỏ trùng (kể cả với hàng đợi hiện có). */
    fun add(text: String): Pair<BatchQueue, AddResult> {
        val seen = items.map { dedupeKey(it.url) }.toMutableSet()
        val added = mutableListOf<BatchItem>()
        val invalid = mutableListOf<Pair<String, String>>()
        var dup = 0
        for (raw in text.lines()) {
            val line = raw.trim().removePrefix("<").removeSuffix(">").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val url = normalizeUrl(line)
            val reason = UrlPolicy.validateSongUrl(url)
            if (reason != null) { invalid += line to reason; continue }
            if (!seen.add(dedupeKey(url))) { dup++; continue }
            added += BatchItem(url)
        }
        return copy(items = items + added) to AddResult(added.size, dup, invalid)
    }

    fun next(): BatchItem? = items.firstOrNull { it.state == ItemState.PENDING }

    fun update(url: String, f: (BatchItem) -> BatchItem) = copy(items = items.map { if (it.url == url) f(it) else it })

    /** Thử lại các mục lỗi và đã huỷ. */
    fun retryFailed() = copy(items = items.map {
        if (it.state == ItemState.ERROR || it.state == ItemState.CANCELLED) it.copy(state = ItemState.PENDING, message = null) else it
    })

    /** Huỷ: mọi mục đang chờ/đang tải chuyển sang "Đã huỷ"; mục đã xong giữ nguyên. */
    fun cancelRemaining(now: Long) = copy(items = items.map {
        if (it.state == ItemState.PENDING || it.state == ItemState.RUNNING) it.copy(state = ItemState.CANCELLED, updatedAt = now) else it
    })

    /** Sau khi app bị tắt giữa chừng: mục "Đang tải" quay về "Chờ". */
    fun recoverAfterRestart() = copy(items = items.map { if (it.state == ItemState.RUNNING) it.copy(state = ItemState.PENDING) else it })

    fun clearDone() = copy(items = items.filter { it.state != ItemState.DONE })
    fun clearAll() = copy(items = emptyList())

    fun serialize(): String = buildString {
        append(HEADER).append('\n')
        append("#opts\tcompact=").append(if (saveCompact) 1 else 0).append("\tpage=").append(if (savePage) 1 else 0).append('\n')
        for (i in items) {
            append(listOf(i.url, i.state.name, i.attempts.toString(), i.message.orEmpty(), i.title.orEmpty(), i.updatedAt.toString(), i.key.orEmpty())
                .joinToString("\t") { esc(it) }).append('\n')
        }
    }

    companion object {
        const val HEADER = "#chord-offline-batch v1"

        fun parse(text: String): BatchQueue {
            var q = BatchQueue()
            val items = mutableListOf<BatchItem>()
            for (line in text.lines()) {
                if (line.isBlank() || line == HEADER) continue
                if (line.startsWith("#opts")) {
                    val opts = line.split('\t').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
                    q = q.copy(saveCompact = opts["compact"] != "0", savePage = opts["page"] == "1")
                    continue
                }
                val f = line.split('\t').map { unesc(it) }
                if (f.size < 2) continue
                val state = runCatching { ItemState.valueOf(f[1]) }.getOrDefault(ItemState.PENDING)
                items += BatchItem(f[0], state, f.getOrNull(2)?.toIntOrNull() ?: 0,
                    f.getOrNull(3)?.ifEmpty { null }, f.getOrNull(4)?.ifEmpty { null }, f.getOrNull(5)?.toLongOrNull() ?: 0, f.getOrNull(6)?.ifEmpty { null })
            }
            return q.copy(items = items)
        }

        /** Chuẩn hoá: bỏ #fragment, http -> https, bỏ "www." để so trùng. */
        fun normalizeUrl(u: String): String {
            var s = u.trim().substringBefore('#')
            if (s.startsWith("http://", ignoreCase = true)) s = "https://" + s.substring(7)
            if (!s.contains("://")) s = "https://$s"
            return s.replaceFirst(Regex("""^https://www\.""", RegexOption.IGNORE_CASE), "https://")
        }

        fun dedupeKey(u: String) = normalizeUrl(u).lowercase().trimEnd('/')

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        private fun unesc(s: String): String {
            val sb = StringBuilder(); var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }

        /** Phản hồi cho thấy website đang giới hạn/chặn -> phải dừng cả hàng đợi, không thử vượt qua. */
        fun isBlockingResponse(status: Int?, html: String?): Boolean {
            if (status == 403 || status == 429 || status == 503) return true
            val lower = html?.take(20_000)?.lowercase() ?: return false
            return listOf("cf-chl", "challenge-platform", "captcha", "just a moment", "attention required").any { it in lower }
        }
    }
}
