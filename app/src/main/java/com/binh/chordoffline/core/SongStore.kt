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
)

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

    fun savePage(key: String, title: String?, html: String, meta: FetchMeta): VersionStatus = guarded(key, Version.PAGE) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        writeAtomic(File(dir(key), Version.PAGE.dataFile), bytes)
        writeInfo(key, title, meta.finalUrl)
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
        SavedSong(key, info["title"]?.ifBlank { null } ?: key, info["sourceUrl"]?.ifBlank { null }, page, compact)
    }.sortedByDescending { maxOf(it.page.savedAtMillis ?: 0, it.compact.savedAtMillis ?: 0) }

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
