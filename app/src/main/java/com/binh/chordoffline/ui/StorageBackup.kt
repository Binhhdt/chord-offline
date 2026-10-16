package com.binh.chordoffline.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.binh.chordoffline.core.SongStore

/**
 * Thư mục lưu file do người dùng chọn (Storage Access Framework), ví dụ Download/HopAm.
 * Bản sao nằm ngoài bộ nhớ riêng của app nên vẫn còn khi gỡ app; mở lại bằng "Khôi phục".
 * Cấu trúc: <thư mục đã chọn>/ChordOffline/<mã bài>/{song.cho, page.html, info.properties, ..., yt_*.jpg}
 */
class StorageBackup(private val context: Context, private val store: SongStore) {
    private val prefs = context.getSharedPreferences("storage", Context.MODE_PRIVATE)

    val treeUri: Uri? get() = prefs.getString("tree", null)?.let(Uri::parse)
    var autoBackup: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    fun folderName(): String? = treeUri?.let { DocumentFile.fromTreeUri(context, it)?.name ?: it.lastPathSegment }

    fun hasValidFolder(): Boolean = treeUri?.let { u ->
        context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } &&
            DocumentFile.fromTreeUri(context, u)?.canWrite() == true
    } ?: false

    fun setFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        treeUri?.takeIf { it != uri }?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) } }
        prefs.edit().putString("tree", uri.toString()).apply()
    }

    /**
     * Thư mục chứa các bài. Chấp nhận cả khi người dùng chọn:
     *  - thư mục cha (ví dụ Download/Hopamviet) -> dùng <cha>/ChordOffline;
     *  - chính thư mục ChordOffline, hoặc thư mục đang chứa trực tiếp các thư mục bài (W8IU…) -> dùng luôn.
     */
    private fun appDir(create: Boolean): DocumentFile? {
        val root = treeUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return null
        if (root.name.equals(DIR, ignoreCase = true)) return root
        root.findFile(DIR)?.takeIf { it.isDirectory }?.let { return it }
        if (looksLikeSongDir(root)) return root
        return if (create) root.createDirectory(DIR) else null
    }

    private fun looksLikeSongDir(dir: DocumentFile): Boolean = dir.listFiles().asSequence()
        .filter { it.isDirectory && SongStore.isValidKey(it.name ?: "") }.take(5)
        .any { d -> d.findFile("song.cho") != null || d.findFile("page.html") != null }

    /** Ghi/đọc file phụ (tiến độ quét) ở thư mục gốc của bản sao lưu. */
    fun writeExtra(name: String, text: String) {
        val app = appDir(create = true) ?: return
        val f = app.findFile(name) ?: app.createFile("application/octet-stream", name) ?: return
        context.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(text.toByteArray()) }
    }

    fun readExtra(name: String): String? = runCatching {
        val f = appDir(create = false)?.findFile(name) ?: return null
        context.contentResolver.openInputStream(f.uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    private fun mime(name: String) = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".jpg") -> "image/jpeg"
        else -> "application/octet-stream"
    }

    /** Sao lưu một bài; chỉ ghi file mới hoặc đã thay đổi kích thước. Trả về số file đã ghi. */
    fun backupKey(key: String): Int {
        val app = appDir(create = true) ?: error("Chưa chọn thư mục lưu hoặc mất quyền truy cập")
        val dir = app.findFile(key) ?: app.createDirectory(key) ?: error("Không tạo được thư mục $key")
        var written = 0
        for (f in store.filesOf(key)) {
            val existing = dir.findFile(f.name)
            if (existing != null && existing.length() == f.length()) continue
            val target = existing ?: dir.createFile(mime(f.name), f.name) ?: error("Không tạo được ${f.name}")
            context.contentResolver.openOutputStream(target.uri, "wt")!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            written++
        }
        return written
    }

    data class Result(val songs: Int, val files: Int, val errors: List<String>)

    fun backupAll(): Result {
        var songs = 0; var files = 0; val errors = mutableListOf<String>()
        for (k in store.keys()) {
            runCatching { files += backupKey(k); songs++ }.onFailure { errors += "$k: ${it.message}" }
        }
        return Result(songs, files, errors)
    }

    /** Khôi phục bài từ thư mục lưu vào app (không ghi đè bài đang có). */
    fun restoreAll(): Result {
        val app = appDir(create = false) ?: return Result(0, 0, listOf(
            "Không thấy bài nào: hãy chọn thư mục ChordOffline hoặc thư mục chứa nó (ví dụ Download/Hopamviet)"))
        var songs = 0; var files = 0; val errors = mutableListOf<String>()
        for (dir in app.listFiles().filter { it.isDirectory }) {
            val key = dir.name ?: continue
            if (!SongStore.isValidKey(key)) continue
            var n = 0
            for (f in dir.listFiles().filter { it.isFile }) {
                val name = f.name ?: continue
                if (!SongStore.isBackupName(name) || f.length() > 16L * 1024 * 1024) continue
                runCatching {
                    val bytes = context.contentResolver.openInputStream(f.uri)!!.use { it.readBytes() }
                    if (store.importFile(key, name, bytes)) n++
                }.onFailure { errors += "$key/$name: ${it.message}" }
            }
            if (n > 0) { songs++; files += n }
        }
        return Result(songs, files, errors)
    }

    companion object { const val DIR = "ChordOffline" }
}
