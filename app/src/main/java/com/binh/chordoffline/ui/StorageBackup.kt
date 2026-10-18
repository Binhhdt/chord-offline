package com.binh.chordoffline.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
        invalidateCache()
    }

    // ---- Bộ nhớ đệm thư mục. Liệt kê bằng MỘT truy vấn DocumentsContract (tên, loại, kích thước cùng lúc).
    // DocumentFile.listFiles() + .name/.isDirectory hỏi Android riêng từng mục -> với ~47.000 thư mục bài mất
    // hàng chục phút; trong lúc đó hàng việc nền (trước v0.11.1 gồm cả ảnh YouTube) bị kẹt.
    private data class Child(val name: String, val uri: Uri, val isDir: Boolean, val size: Long)

    @Volatile private var cachedApp: DocumentFile? = null
    @Volatile private var cachedTree: Uri? = null
    @Volatile private var childDirs: MutableMap<String, Uri>? = null
    @Volatile private var childFiles: MutableMap<String, Uri>? = null

    /** Không khoá: có thể gọi từ luồng giao diện (chọn thư mục) trong lúc sao lưu nền đang chạy. */
    fun invalidateCache() { cachedApp = null; cachedTree = null; childDirs = null; childFiles = null }

    /** Các mục con của một thư mục (uri dạng document-trong-tree) — một lần truy vấn. */
    private fun listChildren(dirUri: Uri): List<Child> {
        val tree = treeUri ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dirUri))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        val out = ArrayList<Child>()
        context.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                out += Child(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), isDir, if (c.isNull(3)) -1L else c.getLong(3))
            }
        }
        return out
    }

    private fun createIn(dirUri: Uri, mime: String, name: String): Uri =
        DocumentsContract.createDocument(context.contentResolver, dirUri, mime, name) ?: error("Không tạo được $name")

    private fun cachedAppDir(): DocumentFile {
        val t = treeUri
        cachedApp?.takeIf { cachedTree == t && childDirs != null }?.let { return it }
        val app = appDir(create = true) ?: error("Chưa chọn thư mục lưu hoặc mất quyền truy cập")
        val all = listChildren(app.uri)
        childDirs = all.filter { it.isDir }.associateTo(HashMap()) { it.name to it.uri }
        childFiles = all.filter { !it.isDir }.associateTo(HashMap()) { it.name to it.uri }
        cachedApp = app; cachedTree = t
        return app
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
    @Synchronized fun writeExtra(name: String, text: String) = try {
        writeExtraCached(name, text)
    } catch (e: Exception) {
        invalidateCache()          // thư mục/file bị đổi bên ngoài -> liệt kê lại rồi thử một lần nữa
        writeExtraCached(name, text)
    }

    private fun writeExtraCached(name: String, text: String) {
        val app = cachedAppDir()
        val files = childFiles!!
        val f = files[name] ?: createIn(app.uri, "application/octet-stream", name).also { files[name] = it }
        context.contentResolver.openOutputStream(f, "wt")!!.use { it.write(text.toByteArray()) }
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

    /**
     * Sao lưu một bài; chỉ ghi file mới hoặc đã thay đổi kích thước. Trả về số file đã ghi.
     * Dùng bộ nhớ đệm thư mục; nếu lỗi (thư mục bị xoá/đổi bên ngoài) thì làm mới bộ nhớ đệm và thử lại một lần.
     */
    @Synchronized fun backupKey(key: String): Int = try {
        backupKeyCached(key)
    } catch (e: Exception) {
        invalidateCache()
        backupKeyCached(key)
    }

    private fun backupKeyCached(key: String): Int {
        val app = cachedAppDir()
        val dirs = childDirs!!
        val dirNew = dirs[key] == null
        val dir = dirs[key] ?: createIn(app.uri, DocumentsContract.Document.MIME_TYPE_DIR, key).also { dirs[key] = it }
        val existingFiles = if (dirNew) emptyMap() else listChildren(dir).filter { !it.isDir }.associateBy { it.name }
        var written = 0
        for (f in store.filesOf(key)) {
            val existing = existingFiles[f.name]
            if (existing != null && existing.size == f.length()) continue
            val target = existing?.uri ?: createIn(dir, mime(f.name), f.name)
            context.contentResolver.openOutputStream(target, "wt")!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            written++
        }
        return written
    }

    data class Result(val songs: Int, val files: Int, val errors: List<String>)

    @Synchronized fun backupAll(): Result {
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
        for (dir in listChildren(app.uri).filter { it.isDir }) {      // một truy vấn cho cả thư mục
            val key = dir.name
            if (!SongStore.isValidKey(key)) continue
            var n = 0
            for (f in listChildren(dir.uri).filter { !it.isDir }) {
                val name = f.name
                if (!SongStore.isBackupName(name) || f.size > 16L * 1024 * 1024) continue
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
