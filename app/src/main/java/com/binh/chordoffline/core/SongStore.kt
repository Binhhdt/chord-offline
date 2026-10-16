package com.binh.chordoffline.core

import java.io.File
import java.security.MessageDigest
import java.util.Properties

enum class SaveState(val label: String) { NOT_SAVED("Chưa lưu"), SAVING("Đang lưu…"), SAVED("Đã lưu"), ERROR("Lỗi") }

/** Hai bản lưu độc lập của một bài. */
enum class Version(val label: String, val dataFile: String, val metaFile: String) {
    PAGE("Nguyên trang (HTML server)", "page.html", "page.properties"),
    COMPACT("Lời/hợp âm gọn", "song.cho", "compact.properties"),
}

data class VersionStatus(
    val state: SaveState,
    val savedAtMillis: Long? = null,
    val bytes: Long? = null,
    val error: String? = null,
) {
    companion object { val NONE = VersionStatus(SaveState.NOT_SAVED) }
}

data class SavedSong(
    val key: String,
    val title: String,
    val sourceUrl: String?,
    val page: VersionStatus,
    val compact: VersionStatus,
    /** Dùng cho tìm kiếm/sắp xếp; có thể null với bài lưu từ bản cũ chưa có thông tin này. */
    val composers: String? = null,
    val singers: String? = null,
    val tone: String? = null,
) {
    val lastSavedMillis: Long get() = maxOf(page.savedAtMillis ?: 0, compact.savedAtMillis ?: 0)
}

/** Thông tin phản hồi HTTP được lưu kèm bản nguyên trang (không có cookie/header nhạy cảm). */
data class FetchMeta(
    val requestedUrl: String,
    val finalUrl: String,
    val httpStatus: Int,
    val contentType: String?,
    val fetchedAtMillis: Long,
)

/**
 * Lưu trữ cục bộ, mỗi bài một thư mục: root/<key>/{page.html,page.properties,song.cho,compact.properties,info.properties}.
 * Không đồng bộ, không tải lên đâu cả. Ghi file theo kiểu tạm-rồi-đổi-tên để không hỏng file khi lỗi.
 */
class SongStore(private val root: File) {

    init { root.mkdirs() }

    fun keyFor(url: String, slugId: String?): String {
        slugId?.let { s -> s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.takeIf { it.isNotEmpty() }?.let { return it } }
        val md = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return "u" + md.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun dir(key: String) = File(root, key)

    /** Khoá thư mục suy ra trực tiếp từ URL bài (…/W8IUDI0D.html -> W8IUDI0D), không cần tải trang. */
    fun keyForUrl(url: String): String = keyFor(url, slugFromUrl(url))

    /** Bài đã có đủ các bản được yêu cầu chưa (để không tải lại bài đã lưu thành công). */
    fun isSaved(key: String, compact: Boolean, page: Boolean): Boolean =
        (compact || page) &&
            (!compact || status(key, Version.COMPACT).state == SaveState.SAVED) &&
            (!page || status(key, Version.PAGE).state == SaveState.SAVED)

    fun hasAny(key: String): Boolean =
        status(key, Version.COMPACT).state == SaveState.SAVED || status(key, Version.PAGE).state == SaveState.SAVED

    fun info(key: String): Map<String, String> = readPropsFile(File(dir(key), "info.properties"))

    // ---------- Ảnh thu nhỏ video YouTube (xem offline để nhận biết ca sĩ) ----------
    fun thumbFile(key: String, youtubeId: String): File = File(dir(key), "yt_" + youtubeId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".jpg")
    fun hasThumb(key: String, youtubeId: String) = thumbFile(key, youtubeId).let { it.exists() && it.length() > 0 }
    fun saveThumb(key: String, youtubeId: String, bytes: ByteArray) {
        require(bytes.size in 100..2_000_000) { "Ảnh không hợp lệ (${bytes.size} byte)" }
        writeAtomic(thumbFile(key, youtubeId), bytes)
    }

    // ---------- Sao lưu / khôi phục (thư mục ngoài do người dùng chọn) ----------
    /** Danh sách bài (thư mục) đang có. */
    fun keys(): List<String> = root.listFiles()?.filter { it.isDirectory && hasAny(it.name) }?.map { it.name }?.sorted() ?: emptyList()

    /** Các file của một bài cần sao lưu (bỏ file tạm). */
    fun filesOf(key: String): List<File> = dir(key).listFiles()?.filter { it.isFile && isBackupName(it.name) }?.sortedBy { it.name } ?: emptyList()

    /**
     * Nhập một file từ bản sao lưu. Chỉ nhận tên file app tự tạo (chống ghi đè file lạ / đường dẫn ../).
     * overwrite = false: giữ file đang có trong máy. Trả về true nếu đã ghi.
     */
    fun importFile(key: String, name: String, bytes: ByteArray, overwrite: Boolean = false): Boolean {
        if (!isValidKey(key) || !isBackupName(name)) return false
        val f = File(dir(key), name)
        if (f.exists() && !overwrite) return false
        writeAtomic(f, bytes)
        return true
    }

    companion object {
        private val BACKUP_NAMES = setOf("page.html", "page.properties", "song.cho", "compact.properties", "info.properties")
        private val THUMB_NAME = Regex("^yt_[A-Za-z0-9_\\-]{11}\\.jpg$")
        fun isBackupName(name: String) = name in BACKUP_NAMES || THUMB_NAME.matches(name)
        fun isValidKey(key: String) = key.isNotEmpty() && key.length <= 64 && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        fun slugFromUrl(url: String): String? =
            url.substringBefore('?').substringBefore('#').substringAfterLast('/').removeSuffix(".html").takeIf { it.isNotBlank() }
    }

    fun savePage(key: String, title: String?, html: String, meta: FetchMeta, song: ParsedSong? = null): VersionStatus = guarded(key, Version.PAGE) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        writeAtomic(File(dir(key), Version.PAGE.dataFile), bytes)
        writeInfo(key, title, meta.finalUrl)
        song?.let { writeSearchInfo(key, it) }
        writeProps(key, Version.PAGE, mapOf(
            "state" to SaveState.SAVED.name,
            "savedAt" to System.currentTimeMillis().toString(),
            "bytes" to bytes.size.toString(),
            "requestedUrl" to meta.requestedUrl,
            "finalUrl" to meta.finalUrl,
            "httpStatus" to meta.httpStatus.toString(),
            "contentType" to (meta.contentType ?: ""),
            "fetchedAt" to meta.fetchedAtMillis.toString(),
        ))
    }

    fun saveCompact(key: String, song: ParsedSong, chordPro: String): VersionStatus = guarded(key, Version.COMPACT) {
        require(song.hasLyrics) { "Không có lời/hợp âm để lưu." }
        val bytes = chordPro.toByteArray(Charsets.UTF_8)
        writeAtomic(File(dir(key), Version.COMPACT.dataFile), bytes)
        writeInfo(key, song.title, song.sourceUrl)
        writeSearchInfo(key, song)
        writeProps(key, Version.COMPACT, mapOf(
            "state" to SaveState.SAVED.name,
            "savedAt" to System.currentTimeMillis().toString(),
            "bytes" to bytes.size.toString(),
            "sourceUrl" to (song.sourceUrl ?: ""),
            "lines" to song.lyricLineCount.toString(),
            "chords" to song.inlineChordCount.toString(),
        ))
    }

    private inline fun guarded(key: String, v: Version, block: () -> Unit): VersionStatus =
        try { block(); status(key, v) } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            // Ghi trạng thái lỗi nhưng giữ nguyên dữ liệu đã lưu trước đó (nếu có).
            runCatching {
                val old = readProps(key, v)
                writeProps(key, v, old + mapOf("lastError" to msg, "lastErrorAt" to System.currentTimeMillis().toString(),
                    "state" to (if (File(dir(key), v.dataFile).exists()) SaveState.SAVED.name else SaveState.ERROR.name)))
            }
            VersionStatus(SaveState.ERROR, error = msg)
        }

    fun status(key: String, v: Version): VersionStatus {
        val p = readProps(key, v)
        val file = File(dir(key), v.dataFile)
        if (p.isEmpty() && !file.exists()) return VersionStatus.NONE
        val state = when {
            file.exists() -> SaveState.SAVED
            p["state"] == SaveState.ERROR.name -> SaveState.ERROR
            else -> SaveState.NOT_SAVED
        }
        return VersionStatus(state, p["savedAt"]?.toLongOrNull(), if (file.exists()) file.length() else null, p["lastError"])
    }

    fun list(): List<SavedSong> = (root.listFiles()?.filter { it.isDirectory } ?: emptyList()).mapNotNull { d ->
        val key = d.name
        val page = status(key, Version.PAGE)
        val compact = status(key, Version.COMPACT)
        if (page.state == SaveState.NOT_SAVED && compact.state == SaveState.NOT_SAVED) return@mapNotNull null
        val info = readPropsFile(File(d, "info.properties"))
        // Bài lưu bằng bản cũ chưa có composer/singers trong info -> đọc từ phần đầu file .cho
        val cho = if (info["composer"] == null && compact.state == SaveState.SAVED)
            runCatching { ChordText.parseChordPro(File(d, Version.COMPACT.dataFile).readText()).meta }.getOrNull() else null
        SavedSong(
            key, info["title"]?.ifBlank { null } ?: cho?.get("title") ?: key, info["sourceUrl"]?.ifBlank { null }, page, compact,
            composers = (info["composer"] ?: cho?.get("composer"))?.ifBlank { null },
            singers = (info["singers"] ?: cho?.get("singer_tones")?.let(::singerNames))?.ifBlank { null },
            tone = (info["key"] ?: cho?.get("key"))?.ifBlank { null },
        )
    }.sortedByDescending { it.lastSavedMillis }

    private fun singerNames(singerTones: String) =
        singerTones.split(";").map { it.trim().substringBeforeLast(":").trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    private fun writeSearchInfo(key: String, song: ParsedSong) {
        val f = File(dir(key), "info.properties")
        writePropsFile(f, readPropsFile(f) + mapOf(
            "composer" to song.composers.joinToString(", "),
            "singers" to song.singerTones.joinToString(", ") { it.first },
            "key" to (song.tone ?: ""),
        ))
    }

    fun read(key: String, v: Version): String? = File(dir(key), v.dataFile).takeIf { it.exists() }?.readText(Charsets.UTF_8)

    fun meta(key: String, v: Version): Map<String, String> = readProps(key, v)

    fun delete(key: String, v: Version) {
        File(dir(key), v.dataFile).delete()
        File(dir(key), v.metaFile).delete()
        val others = Version.entries.filter { it != v }.any { File(dir(key), it.dataFile).exists() }
        if (!others) dir(key).deleteRecursively()
    }

    fun deleteAll(key: String) { dir(key).deleteRecursively() }

    // ---- tiện ích file ----
    private fun writeInfo(key: String, title: String?, url: String?) {
        val f = File(dir(key), "info.properties")
        val old = readPropsFile(f)
        writePropsFile(f, old + listOfNotNull(title?.let { "title" to it }, url?.let { "sourceUrl" to it }).toMap())
    }

    private fun readProps(key: String, v: Version) = readPropsFile(File(dir(key), v.metaFile))
    private fun writeProps(key: String, v: Version, m: Map<String, String>) = writePropsFile(File(dir(key), v.metaFile), m)

    private fun readPropsFile(f: File): Map<String, String> {
        if (!f.exists()) return emptyMap()
        val p = Properties()
        f.reader(Charsets.UTF_8).use { p.load(it) }
        return p.stringPropertyNames().associateWith { p.getProperty(it) }
    }

    private fun writePropsFile(f: File, m: Map<String, String>) {
        val p = Properties(); m.forEach { (k, v) -> p.setProperty(k, v) }
        val out = java.io.StringWriter(); p.store(out, null)
        writeAtomic(f, out.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomic(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("Không đổi tên được ${tmp.name}") }
    }
}
