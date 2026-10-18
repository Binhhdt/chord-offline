package com.binh.chordoffline.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.binh.chordoffline.CrashLog
import com.binh.chordoffline.DownloadService
import com.binh.chordoffline.NewSongs
import com.binh.chordoffline.core.*
import com.binh.chordoffline.onBigStack
import com.binh.chordoffline.net.FetchResult
import com.binh.chordoffline.net.HttpFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CheckState(
    val url: String = "https://hopamviet.vn/chord/song/nguoi-dung/W8IUDI0D.html",
    val confirmed: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val robotsInfo: String? = null,
    val fetch: FetchResult? = null,
    val selectors: List<SelectorResult> = emptyList(),
    val parsed: ParsedSong? = null,
    val missingReason: String? = null,
    val savedFileName: String? = null,
    val comparison: ComparisonReport? = null,
    val comparing: Boolean = false,
    val pageStatus: VersionStatus = VersionStatus.NONE,
    val compactStatus: VersionStatus = VersionStatus.NONE,
    val songKey: String? = null,
    /** Chi tiết lỗi nội bộ (stack trace) nếu khâu tải/phân tích gặp lỗi. */
    val errorDetail: String? = null,
)

/** Trạng thái màn "Tải nhiều". Hàng đợi được lưu ra file sau mỗi thay đổi. */
data class BatchUi(
    val queue: BatchQueue = BatchQueue(),
    val running: Boolean = false,
    val pauseRequested: Boolean = false,
    /** Các bài đang tải cùng lúc (tối đa = số luồng). */
    val currentUrls: List<String> = emptyList(),
    val waitSeconds: Int = 0,
    val message: String? = null,
    val lastAdd: AddResult? = null,
    /** Số luồng đang được phép chạy (giảm khi website báo bận). */
    val workerLimit: Int = 0,
    /** Tốc độ thực tế (bài/giây) và thời gian còn lại (giây), đo trong 60 giây gần nhất. */
    val rate: Double? = null,
    val etaSec: Long? = null,
    /** Số lần website báo bận (429/502/503/504) trong lượt tải này. */
    val throttles: Int = 0,
    /** Đang ở chế độ "Thử lại lỗi": số bài lỗi được thử lại (null = tải mọi mục chờ). */
    val retryOnly: Int? = null,
    /** Việc phụ còn chờ (ảnh YouTube, sao lưu) — chạy nền sau khi lưu. */
    val postPending: Int = 0,
    /** Đang đọc file hàng đợi lúc mở app. */
    val queueLoading: Boolean = false,
)

/** Một bài tìm thấy khi quét trang danh mục, kèm trạng thái chọn để xem trước. */
data class FoundItem(val song: FoundSong, val selected: Boolean, val alreadySaved: Boolean, val inQueue: Boolean)

/** Thẻ "Bài mới trên Hợp Âm Việt": kết quả lần kiểm tra gần nhất + lựa chọn tải. */
data class NewSongsUi(
    val checking: Boolean = false,
    val result: NewSongCheck.Result? = null,
    /** Chỉ các bài còn CHƯA lưu & chưa trong hàng đợi. */
    val items: List<FoundItem> = emptyList(),
    val message: String? = null,
    val isError: Boolean = false,
    /** Tự kiểm tra mỗi ngày (khi có Wi-Fi) và báo bằng thông báo. */
    val daily: Boolean = true,
)

/** Trạng thái phần "Lấy danh sách từ trang danh mục". */
data class DiscoverUi(
    val url: String = "https://hopamviet.vn/chord/category/3/nhac-tre.html",
    val maxPages: Int = 2,
    val running: Boolean = false,
    val waitSeconds: Int = 0,
    val currentPage: String? = null,
    val log: List<String> = emptyList(),
    val items: List<FoundItem> = emptyList(),
    val message: String? = null,
    val isError: Boolean = false,
    /** Trang để "Quét tiếp" (null nếu chưa quét, hết trang hoặc dừng vì lỗi/lặp). */
    val resumeUrl: String? = null,
    /** Đã quét tới trang cuối của danh sách. */
    val reachedEnd: Boolean = false,
    /** Số trang cuối cùng đã quét (1-based) và tổng số trang đọc từ thanh phân trang. */
    val lastScanned: Int = 0,
    val totalPages: Int? = null,
) {
    val selectedCount: Int get() = items.count { it.selected }
    val resumePage: Int? get() = resumeUrl?.let { ListingPolicy.pageIndex(it) + 1 }
}

/** Chế độ tự động: quét N trang -> thêm bài chưa lưu -> tải xong -> quét tiếp, lặp tới hết danh mục. */
data class AutoUi(
    val active: Boolean = false,
    val round: Int = 0,
    val phase: String = "",
    val totalQueued: Int = 0,
    val message: String? = null,
    val isError: Boolean = false,
)

/** Trạng thái phần "Thư mục lưu file". */
data class StorageUi(
    val folder: String? = null,
    val valid: Boolean = false,
    val auto: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val fetcher = HttpFetcher()
    val store = SongStore(File(app.filesDir, "songs"))

    private val _check = MutableStateFlow(CheckState())
    val check: StateFlow<CheckState> = _check

    private val _library = MutableStateFlow<List<SavedSong>>(emptyList())
    val library: StateFlow<List<SavedSong>> = _library
    /** true cho tới khi đọc xong thư viện lần đầu (từ sổ mục lục hoặc quét thư mục). */
    private val _libraryLoading = MutableStateFlow(true)
    val libraryLoading: StateFlow<Boolean> = _libraryLoading
    /** Chỉ mục tìm kiếm (bản bỏ dấu tính sẵn) — dựng ở luồng nền mỗi khi thư viện đổi. */
    private val _searchIndex = MutableStateFlow<List<LibrarySearch.Entry>>(emptyList())
    val searchIndex: StateFlow<List<LibrarySearch.Entry>> = _searchIndex
    /** Sổ mục lục thư viện (v0.10): mở app chỉ đọc file này thay vì ~5 file của từng bài. */
    private val indexFile = File(app.filesDir, "library_index.tsv")

    private var robots: RobotsRules? = null
    @Volatile private var lastRequestAt = 0L
    private val minIntervalMs = 5_000L

    // ---------- Tải nhiều ----------
    private val batchFile = File(app.filesDir, "batch_queue.tsv")
    // Hàng đợi được đọc ở luồng nền lúc mở app (file có thể hàng chục nghìn dòng) — xem init ở cuối class.
    private val _batch = MutableStateFlow(BatchUi(queueLoading = true))
    /** Luồng riêng, tuần tự để ghi file hàng đợi (không ghi trên luồng giao diện, không ghi lẫn thứ tự). */
    private val queueWriter = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val batch: StateFlow<BatchUi> = _batch
    private var batchJob: Job? = null

    val backup = StorageBackup(app, store)

    // ---------- Màu hợp âm (lưu cho mọi bài, chỉ đổi hiển thị) ----------
    private val displayPrefs = app.getSharedPreferences("display", android.content.Context.MODE_PRIVATE)
    private val _chordStyle = MutableStateFlow(ChordStyle(
        color = ChordColors.sanitize(displayPrefs.getLong("chord_color", ChordColors.DEFAULT)),
        highlight = displayPrefs.getBoolean("chord_highlight", false),
    ))
    val chordStyle: StateFlow<ChordStyle> = _chordStyle

    fun setChordColor(argb: Long) {
        _chordStyle.update { it.copy(color = argb) }
        displayPrefs.edit().putLong("chord_color", argb).apply()
    }

    fun setChordHighlight(v: Boolean) {
        _chordStyle.update { it.copy(highlight = v) }
        displayPrefs.edit().putBoolean("chord_highlight", v).apply()
    }

    // ---------- Khoảng nghỉ giữa các request (người dùng chọn, thấp nhất 1 giây; website chậm thì tự nghỉ lâu hơn) ----------
    private val _delayMs = MutableStateFlow(PacePolicy.clamp(displayPrefs.getLong("batch_delay_ms", PacePolicy.DEFAULT)))
    val delayMs: StateFlow<Long> = _delayMs
    @Volatile private var lastResponseMs = 0L
    fun setDelay(ms: Long) { val v = PacePolicy.clamp(ms); _delayMs.value = v; displayPrefs.edit().putLong("batch_delay_ms", v).apply() }
    private fun pace(): Long = PacePolicy.effectiveDelay(_delayMs.value, lastResponseMs)
    private val _storage = MutableStateFlow(StorageUi())
    val storage: StateFlow<StorageUi> = _storage

    private val _discover = MutableStateFlow(DiscoverUi())
    val discover: StateFlow<DiscoverUi> = _discover
    private var discoverJob: Job? = null

    fun setUrl(v: String) = _check.update { it.copy(url = v) }
    fun setConfirmed(v: Boolean) = _check.update { it.copy(confirmed = v) }

    /** Tải trực tiếp URL (một request cho mỗi lần bấm, cách nhau tối thiểu 5 giây). */
    fun fetchAndInspect() {
        val s = _check.value
        if (!s.confirmed) { _check.update { it.copy(message = "Hãy xác nhận bạn có quyền lưu nội dung này trước.") }; return }
        UrlPolicy.validateSongUrl(s.url)?.let { reason -> _check.update { it.copy(message = reason) }; return }
        if (_batch.value.running) { _check.update { it.copy(message = "Đang tải hàng loạt — hãy tạm dừng ở tab \"Tải nhiều\" trước.") }; return }
        val wait = minIntervalMs - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) { _check.update { it.copy(message = "Vui lòng đợi ${(wait + 999) / 1000} giây trước lần tải tiếp theo.") }; return }

        val url = s.url.trim()
        _check.update { CheckState(url = it.url, confirmed = it.confirmed, loading = true, savedFileName = null) }
        viewModelScope.launch {
          try {
            val result = withContext<Triple<RobotsRules, FetchResult?, String?>>(Dispatchers.IO) {
                val r = robots ?: loadRobots().also { robots = it }
                val path = UrlPolicy.path(url)
                if (!r.isAllowed(path)) return@withContext Triple<RobotsRules, FetchResult?, String?>(r, null,
                    "robots.txt (${r.source}) không cho phép đường dẫn $path — không tải.")
                lastRequestAt = System.currentTimeMillis()
                Triple(r, fetcher.fetch(url), null)
            }
            val (r, fetch, blocked) = result
            if (fetch == null) {
                _check.update { it.copy(loading = false, message = blocked, robotsInfo = "robots.txt: ${r.source}") }
                return@launch
            }
            val analysis = onBigStack("analyze-html") { analyze(fetch) }
            _check.update {
                it.copy(
                    loading = false,
                    robotsInfo = "robots.txt: ${r.source} — đường dẫn được phép",
                    fetch = fetch,
                    selectors = analysis.first,
                    parsed = analysis.second,
                    missingReason = analysis.third,
                    songKey = analysis.second?.let { p -> store.keyFor(fetch.finalUrl ?: url, p.slugId) },
                    message = if (fetch.truncated) "Phản hồi vượt ${HttpFetcher.MAX_BYTES / 1024 / 1024} MB, đã cắt bớt." else null,
                )
            }
            refreshStatuses()
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Throwable) {
            _check.update {
                it.copy(loading = false, message = "Lỗi nội bộ khi tải/phân tích: ${e.javaClass.simpleName}: ${e.message}",
                    errorDetail = CrashLog.stackTrace(e))
            }
          }
        }
    }

    private fun loadRobots(): RobotsRules {
        val r = fetcher.fetch("https://hopamviet.vn/robots.txt")
        lastRequestAt = System.currentTimeMillis()
        return if (r.ok && r.body != null && (r.contentType?.contains("text/plain") == true || r.body.contains("User-agent", true)))
            RobotsRules.parse(r.body, "ChordOffline", "robots.txt tải lúc ${fmt(r.fetchedAtMillis)}")
        else RobotsRules.FALLBACK_HOPAMVIET
    }

    private fun analyze(f: FetchResult): Triple<List<SelectorResult>, ParsedSong?, String?> {
        val html = f.body
        if (!f.ok || html == null) return Triple(emptyList(), null,
            Diagnosis.explainMissingLyrics(f.status, f.contentType, html, f.error))
        val sel = SelectorCheck.check(html)
        val parsed = SongParser.parse(html, f.finalUrl)
        val reason = if (parsed.hasLyrics) null else Diagnosis.explainMissingLyrics(f.status, f.contentType, html, null)
        return Triple(sel, parsed, reason)
    }

    /** So sánh HTML server vừa tải với file HTML bạn đã lưu từ trình duyệt (chọn qua bộ chọn file). */
    fun compareWithSaved(uri: Uri, displayName: String?) {
        val server = _check.value.fetch?.body
        if (server == null) { _check.update { it.copy(message = "Cần tải trực tiếp thành công trước khi so sánh.") }; return }
        _check.update { it.copy(comparing = true, savedFileName = displayName ?: uri.lastPathSegment) }
        viewModelScope.launch {
            val report = runCatching {
                val saved = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }
                onBigStack("compare-html") { HtmlComparison.compare(server, saved) }
            }
            _check.update {
                it.copy(comparing = false, comparison = report.getOrNull(),
                    message = report.exceptionOrNull()?.let { e -> "Không so sánh được: ${e.javaClass.simpleName}: ${e.message}" },
                    errorDetail = report.exceptionOrNull()?.let { e -> CrashLog.stackTrace(e) })
            }
        }
    }

    fun savePage() {
        val s = _check.value
        val f = s.fetch ?: return
        val html = f.body ?: return
        val key = s.songKey ?: store.keyFor(f.finalUrl ?: f.requestedUrl, null)
        _check.update { it.copy(pageStatus = VersionStatus(SaveState.SAVING)) }
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) {
                store.savePage(key, s.parsed?.title, html,
                    FetchMeta(f.requestedUrl, f.finalUrl ?: f.requestedUrl, f.status ?: 0, f.contentType, f.fetchedAtMillis),
                    song = s.parsed)
            }
            _check.update { it.copy(pageStatus = st, songKey = key) }
            if (st.state == SaveState.SAVED) withContext(Dispatchers.IO) { afterSaved(key, s.parsed) }
            libraryChanged(key); applyLibraryChanges()
        }
    }

    fun saveCompact() {
        val s = _check.value
        val f = s.fetch ?: return
        val song = s.parsed ?: return
        val key = s.songKey ?: return
        _check.update { it.copy(compactStatus = VersionStatus(SaveState.SAVING)) }
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) {
                val withSource = if (song.sourceUrl == null) song.copy(sourceUrl = f.finalUrl) else song
                store.saveCompact(key, withSource, ChordText.toChordPro(withSource, iso(f.fetchedAtMillis)))
            }
            _check.update { it.copy(compactStatus = st) }
            if (st.state == SaveState.SAVED) withContext(Dispatchers.IO) { afterSaved(key, song) }
            libraryChanged(key); applyLibraryChanges()
        }
    }

    private fun refreshStatuses() {
        val key = _check.value.songKey ?: return
        viewModelScope.launch {
            val (p, c) = withContext(Dispatchers.IO) { store.status(key, Version.PAGE) to store.status(key, Version.COMPACT) }
            _check.update { it.copy(pageStatus = p, compactStatus = c) }
        }
    }

    /**
     * Đọc lại toàn bộ thư viện. Chỉ một lượt chạy tại một thời điểm: gọi lúc đang đọc thì đánh dấu đọc lại
     * MỘT lần sau đó (trước đây các lượt chồng lên nhau khi thư viện có hàng chục nghìn bài -> app đơ).
     */
    fun refreshLibrary() {
        synchronized(libLock) {
            if (libFullRunning) { libFullAgain = true; return }
            libFullRunning = true
        }
        viewModelScope.launch {
            try {
                while (true) {
                    val before = synchronized(libLock) { libChanged.toSet() }
                    val list = withContext(Dispatchers.IO) { store.list() }
                    val again = synchronized(libLock) {
                        _library.value = list
                        _libraryLoading.value = false
                        saveIndexNow()                 // vừa quét toàn bộ -> ghi mục lục ngay
                        libChanged.removeAll(before)   // đã nằm trong lượt đọc toàn bộ; bài lưu trong lúc đọc vẫn được ghép sau
                        val a = libFullAgain; libFullAgain = false; a
                    }
                    if (!again) break
                }
            } finally {
                synchronized(libLock) { libFullRunning = false }
            }
        }
    }

    private val libLock = Any()
    private var libFullRunning = false
    private var libFullAgain = false
    private val libChanged = LinkedHashSet<String>()

    /** Mở tab "Đã lưu": thư viện đã được nạp lúc mở app -> chỉ ghép các bài vừa thay đổi. */
    fun refreshLibraryIfNeeded() {
        if (!_libraryLoading.value) viewModelScope.launch { applyLibraryChanges() }
    }

    /**
     * Mở app: đọc sổ mục lục (1 file) rồi hiện ngay; sau đó đối chiếu với danh sách thư mục bài (1 lần liệt kê)
     * để thêm bài mới / bỏ bài đã xoá. Chưa có hoặc hỏng mục lục -> quét toàn bộ như trước rồi tạo mục lục.
     */
    private fun loadLibraryAtStart() {
        viewModelScope.launch {
            // Mục lục rỗng thì KHÔNG tin (v0.10.0 có thể đã ghi mục lục rỗng) -> quét toàn bộ rồi ghi lại ngay.
            val idx = withContext(Dispatchers.IO) { LibraryIndex.read(indexFile) }?.takeIf { it.isNotEmpty() }
            if (idx == null) { refreshLibrary(); return@launch }
            indexSavedRef = idx                       // vừa đọc từ mục lục -> không cần ghi lại
            // Đang có lượt quét toàn bộ (vd. vừa Khôi phục) -> để lượt đó báo xong; không hiện "0 bài" trong lúc chờ.
            val applied = synchronized(libLock) { if (!libFullRunning) { _library.value = idx; true } else false }
            if (!applied) return@launch
            _libraryLoading.value = false
            applyLibraryChanges()
            val (kept, missing) = withContext(Dispatchers.IO) { LibraryIndex.reconcile(idx, store.dirKeys()) }
            val added = withContext(Dispatchers.IO) { missing.mapNotNull { store.load(it) } }
            if (kept.size == idx.size && added.isEmpty()) return@launch
            val keptKeys = kept.mapTo(HashSet()) { it.key }
            val gone = idx.filter { it.key !in keptKeys }.mapTo(HashSet()) { it.key }
            val addedKeys = added.mapTo(HashSet()) { it.key }
            synchronized(libLock) {
                if (libFullRunning) return@launch
                _library.value = added.sortedByDescending { it.lastSavedMillis } +
                    _library.value.filter { it.key !in gone && it.key !in addedKeys }
            }
            saveIndexNow()                            // ghi ngay: tắt app sớm cũng không phải đối chiếu lại lần sau
        }
    }

    private val indexWriteLock = Any()
    /** Ghi sổ mục lục (một luồng ghi tại một thời điểm). Không bao giờ ghi khi thư viện còn đang nạp. */
    private fun writeIndex(list: List<SavedSong>) {
        if (_libraryLoading.value || list.isEmpty()) return     // không bao giờ ghi mục lục rỗng
        synchronized(indexWriteLock) {
            if (list === indexSavedRef) return
            runCatching { LibraryIndex.write(indexFile, list); indexSavedRef = list }
        }
    }

    private fun saveIndexNow() {
        viewModelScope.launch(Dispatchers.IO) { writeIndex(_library.value) }
    }

    @Volatile private var indexSaveScheduled = false
    @Volatile private var indexSavedRef: List<SavedSong>? = null
    /** Ghi sổ mục lục gộp (sau 2 giây) mỗi khi thư viện thay đổi. */
    private fun scheduleIndexSave() {
        if (indexSaveScheduled) return
        indexSaveScheduled = true
        viewModelScope.launch(Dispatchers.IO) {
            delay(2_000)
            indexSaveScheduled = false
            writeIndex(_library.value)
        }
    }

    /** Thư viện đổi -> dựng lại chỉ mục tìm kiếm (dùng lại phần không đổi) và hẹn ghi sổ mục lục. */
    private fun observeLibrary() {
        viewModelScope.launch {
            var first = true
            _library.collectLatest { list ->
                val prev = _searchIndex.value
                _searchIndex.value = withContext(Dispatchers.Default) { LibrarySearch.index(list, prev) }
                if (!first && !_libraryLoading.value && list !== indexSavedRef) scheduleIndexSave()
                first = false
            }
        }
    }

    /** Ghi nhận bài vừa lưu; thư viện được cập nhật từng phần (chỉ đọc các bài này) theo nhịp. */
    private fun libraryChanged(key: String) = synchronized(libLock) { libChanged.add(key) }

    /** Đọc các bài vừa thay đổi rồi ghép vào danh sách hiện có (bài mới lưu lên đầu, đúng thứ tự "Mới lưu"). */
    private suspend fun applyLibraryChanges() {
        if (_libraryLoading.value) return          // chưa nạp xong thư viện: giữ lại các thay đổi để ghép sau
        val keys = synchronized(libLock) { if (libFullRunning || libChanged.isEmpty()) return; libChanged.toList().also { libChanged.clear() } }
        val loaded = withContext(Dispatchers.IO) { keys.mapNotNull { k -> store.load(k) } }
        synchronized(libLock) {
            if (libFullRunning) { libChanged.addAll(keys); return }   // lượt đọc toàn bộ đang chạy sẽ có đủ
            val set = keys.toHashSet()
            _library.value = loaded.sortedByDescending { it.lastSavedMillis } + _library.value.filter { it.key !in set }
        }
    }

    fun delete(key: String, v: Version?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { if (v == null) store.deleteAll(key) else store.delete(key, v) }
            libraryChanged(key); applyLibraryChanges(); refreshStatuses()
        }
    }

    suspend fun load(key: String, v: Version): String? = withContext(Dispatchers.IO) { store.read(key, v) }

    // =====================================================================
    // Tải nhiều URL — SONG SONG CÓ KIỂM SOÁT (v0.8):
    //  - N worker (mặc định 3) cùng lấy bài từ hàng đợi; mỗi bài chỉ được một worker nhận (claim trong vùng khoá).
    //  - Cổng tốc độ chung: tổng tối đa ~3 request/giây cho cả pool, dù có bao nhiêu worker.
    //  - 429/502/503/504/lỗi mạng: giảm một nửa số worker, giãn khoảng cách request, thử lại tối đa 3 lần
    //    với backoff 1 s, 2 s, 4 s (tôn trọng Retry-After). Ổn định lại thì tăng dần.
    //  - 403 / trang xác minh (CAPTCHA…): dừng toàn bộ — app KHÔNG vượt qua.
    //  - Việc phụ (ảnh YouTube, tự sao lưu) chạy ở hàng riêng, không chặn đường tải; file hàng đợi và
    //    thư viện được cập nhật theo nhịp (mỗi vài giây) thay vì sau mỗi bài.
    // =====================================================================

    private fun loadBatch(): BatchQueue =
        runCatching { BatchQueue.parse(batchFile.readText()) }.getOrDefault(BatchQueue()).recoverAfterRestart()

    private val batchLock = Any()
    @Volatile private var queueDirty = false

    /**
     * Đổi hàng đợi. [persist] = true: ghi ngay ra file (thao tác của người dùng).
     * Trong lúc tải: persist = false, chỉ đánh dấu "bẩn" — bộ đếm nhịp ghi gộp mỗi 3 giây và khi dừng.
     */
    private fun setQueue(persist: Boolean = true, f: (BatchQueue) -> BatchQueue): BatchQueue {
        val q = synchronized(batchLock) {
            val q = f(_batch.value.queue)
            _batch.update { it.copy(queue = q) }
            queueDirty = true
            q
        }
        // thao tác của người dùng: ghi ngay nhưng ở luồng ghi riêng (file hàng chục nghìn dòng không ghi trên luồng giao diện)
        if (persist) viewModelScope.launch(queueWriter) { flushQueue() }
        return q
    }

    private fun writeQueue(q: BatchQueue) {
        queueDirty = false
        if (_batch.value.queueLoading) return      // chưa đọc xong file cũ -> tuyệt đối không ghi đè
        runCatching {
            val tmp = File(batchFile.parentFile, batchFile.name + ".tmp")
            tmp.writeText(q.serialize())
            if (!tmp.renameTo(batchFile)) { batchFile.delete(); tmp.renameTo(batchFile) }
        }
    }

    /** Ghi hàng đợi nếu có thay đổi: chụp trạng thái trong khoá, ghi file ngoài khoá. */
    private fun flushQueue() {
        val q = synchronized(batchLock) {
            if (!queueDirty || _batch.value.queueLoading) return
            queueDirty = false
            _batch.value.queue
        }
        writeQueue(q)
    }

    /** Hàng đợi chưa đọc xong lúc mở app -> chưa cho thao tác (tránh ghi đè hàng đợi cũ). */
    private fun queueNotReady(): Boolean {
        if (!_batch.value.queueLoading) return false
        _batch.update { it.copy(message = "Đang đọc hàng đợi… thử lại sau giây lát.") }
        return true
    }

    // ---------- Cài đặt tải song song ----------
    private val _workers = MutableStateFlow(DownloadConfig.clampWorkers(displayPrefs.getInt("batch_workers", DownloadConfig.MAX_WORKERS)))
    val workers: StateFlow<Int> = _workers
    fun setWorkers(n: Int) { val v = DownloadConfig.clampWorkers(n); _workers.value = v; displayPrefs.edit().putInt("batch_workers", v).apply() }

    private val _extraDelay = MutableStateFlow(DownloadConfig.clampDelay(displayPrefs.getLong("batch_extra_delay", DownloadConfig.DELAY)))
    val extraDelay: StateFlow<Long> = _extraDelay
    fun setExtraDelay(ms: Long) { val v = DownloadConfig.clampDelay(ms); _extraDelay.value = v; displayPrefs.edit().putLong("batch_extra_delay", v).apply() }

    /** "Thử lại lỗi": chỉ tải lại đúng các URL này (null = mọi mục đang chờ). Giữ qua Tạm dừng → Tiếp tục. */
    private var retryScope: Set<String>? = null

    fun batchAdd(text: String): AddResult {
        if (queueNotReady()) return AddResult(0, 0, listOf("(hàng đợi)" to "Đang đọc hàng đợi lúc mở app — thử lại sau giây lát"))
        var result = AddResult(0, 0, emptyList())
        setQueue { q -> q.add(text).also { result = it.second }.first }
        if (!_batch.value.running && result.added > 0) retryScope = null
        _batch.update { it.copy(lastAdd = result, message = null, retryOnly = retryScope?.size) }
        return result
    }

    fun batchOptions(compact: Boolean, page: Boolean) {
        if (_batch.value.running || queueNotReady()) return
        setQueue { it.copy(saveCompact = compact, savePage = page) }
    }

    fun batchStart() = batchStart(fromAuto = false)

    /** [fromAuto]: chế độ Tự động được phép tải trong lúc đang quét vòng sau (dùng chung cổng tốc độ). */
    private fun batchStart(fromAuto: Boolean) {
        if (queueNotReady()) return
        val b = _batch.value
        retryScope?.let { s -> if (b.queue.pendingCount(s) == 0) retryScope = null }
        when {
            b.running -> return
            !_check.value.confirmed -> { _batch.update { it.copy(message = "Hãy xác nhận bạn có quyền lưu nội dung trước.") }; return }
            !b.queue.saveCompact && !b.queue.savePage -> { _batch.update { it.copy(message = "Chọn ít nhất một kiểu lưu.") }; return }
            !b.queue.hasPending -> { _batch.update { it.copy(message = "Không còn mục nào đang chờ.") }; return }
            _check.value.loading -> { _batch.update { it.copy(message = "Đang tải ở tab Kiểm tra URL, thử lại sau.") }; return }
            _discover.value.running && !fromAuto -> { _batch.update { it.copy(message = "Đang quét danh mục — đợi quét xong hoặc bấm Dừng quét.") }; return }
        }
        val scope = retryScope
        _batch.update { it.copy(running = true, pauseRequested = false, message = null, retryOnly = scope?.size) }
        batchJob = viewModelScope.launch { runBatch(scope) }
    }

    fun batchPause() {
        if (_batch.value.running) _batch.update { it.copy(pauseRequested = true, message = "Sẽ tạm dừng sau các bài đang tải…") }
    }

    fun batchCancel() {
        if (queueNotReady()) return
        batchJob?.cancel()
        batchJob = null
        retryScope = null
        setQueue { it.cancelRemaining(System.currentTimeMillis()) }
        _batch.update { it.copy(running = false, pauseRequested = false, currentUrls = emptyList(), waitSeconds = 0, retryOnly = null,
            message = "Đã huỷ các mục chưa tải. Mục đã xong vẫn được giữ.") }
    }

    /** Thử lại CHỈ các mục lỗi/đã huỷ — không đụng tới các mục đang chờ khác. */
    fun batchRetryFailed() {
        if (_batch.value.running || queueNotReady()) return
        val failed = _batch.value.queue.failedUrls()
        if (failed.isEmpty()) return
        setQueue { it.retryFailed() }
        retryScope = failed
        batchStart()
    }

    fun batchClearDone() { if (!_batch.value.running && !queueNotReady()) setQueue { it.clearDone() } }
    fun batchClearAll() {
        if (!_batch.value.running && !queueNotReady()) { retryScope = null; setQueue { it.clearAll() }; _batch.update { it.copy(lastAdd = null, message = null, retryOnly = null) } }
    }

    // ---------- Cổng tốc độ DÙNG CHUNG cho tải bài và quét danh mục (tổng ≤ ~3 request/giây) ----------
    private val sharedGate = RateGate(DownloadConfig.minIntervalMs)
    private val gateLock = Mutex()

    /** Xin một khe gửi request từ cổng chung (dùng cho trang danh mục khi đang tải song song). */
    private suspend fun acquireShared() {
        val wait = gateLock.withLock { sharedGate.reserve(System.currentTimeMillis()) }
        if (wait > 0) delay(wait)
    }

    /** Lượt tải gần nhất bị ngắt (tạm dừng / website giới hạn / huỷ) chứ không phải hết bài. */
    @Volatile private var batchInterrupted = false

    private var lastLibraryRefresh = 0L
    /** Khi đang tải: tối đa mỗi 5 giây cập nhật thư viện TỪNG PHẦN (chỉ các bài vừa lưu), không đọc lại cả thư viện. */
    private fun refreshLibrarySoon() {
        val now = System.currentTimeMillis()
        if (now - lastLibraryRefresh < 5_000) return
        lastLibraryRefresh = now
        viewModelScope.launch { applyLibraryChanges() }
    }

    private suspend fun runBatch(only: Set<String>?) {
        val n = _workers.value
        val adapt = AdaptiveConcurrency(n)
        val gate = sharedGate
        var completed = false
        val meter = ThroughputMeter().also { it.start(System.currentTimeMillis()) }
        val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val stop = AtomicReference<String?>(null)
        val throttledOutInRow = AtomicInteger(0)
        val extra = _extraDelay.value
        _batch.update { it.copy(workerLimit = n, rate = null, etaSec = null, throttles = 0, currentUrls = emptyList()) }

        /** Xin một khe gửi request từ cổng tốc độ chung (trần ~3 request/giây; giãn ra khi bị giới hạn). */
        suspend fun acquireSlot() {
            val wait = gateLock.withLock {
                gate.intervalMs = DownloadConfig.minIntervalMs * n / adapt.limit
                gate.reserve(System.currentTimeMillis())
            }
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }

        suspend fun processWithRetry(url: String, opts: BatchQueue): BatchOutcome {
            var attempt = 0
            while (true) {
                val out = try {
                    processBatchItem(url, opts) { acquireSlot() }
                } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    BatchOutcome(false, null, null, "Lỗi nội bộ: ${e.javaClass.simpleName}: ${e.message}", ResponseClass.FAIL)
                }
                if (out.cls != ResponseClass.THROTTLE) {
                    if (out.cls == ResponseClass.OK) gateLock.withLock { adapt.onSuccess(System.currentTimeMillis()) }
                    return out
                }
                attempt++
                val now = System.currentTimeMillis()
                val limit = gateLock.withLock {
                    out.retryAfterSec?.let { gate.holdUntil(now + it * 1000) }   // cả pool chờ theo yêu cầu của server
                    adapt.onThrottle(now)
                }
                _batch.update { it.copy(throttles = it.throttles + 1, workerLimit = limit) }
                if (attempt > DownloadConfig.MAX_RETRIES || stop.get() != null)
                    return out.copy(message = "${out.message} — đã thử lại ${attempt - 1} lần", throttledOut = true)
                val wait = Backoff.delayMs(attempt, out.retryAfterSec, jitter = kotlin.random.Random.nextDouble(0.0, 0.3))
                setQueue(persist = false) { q -> q.update(url) {
                    it.copy(message = "Website bận (${out.message}) — thử lại lần $attempt sau ${(wait + 999) / 1000} giây") } }
                delay(wait)
            }
        }

        suspend fun worker(idx: Int) {
            while (true) {
                if (stop.get() != null || _batch.value.pauseRequested) return
                // Bị giới hạn -> chỉ worker có số thứ tự < limit được chạy; các worker khác chờ.
                if (idx >= gateLock.withLock { adapt.limit }) {
                    if (_batch.value.queue.pendingCount(only) == 0) return
                    delay(500); continue
                }
                var item: BatchItem? = null
                setQueue(persist = false) { q -> val (nq, got) = q.claimNext(only); item = got; nq }
                val url = item?.url ?: return
                inFlight.add(url)
                val out = processWithRetry(url, _batch.value.queue)
                inFlight.remove(url)
                setQueue(persist = false) { q -> q.update(url) {
                    it.copy(state = if (out.ok) ItemState.DONE else ItemState.ERROR, message = out.message,
                        title = out.title ?: it.title, key = out.key ?: it.key, updatedAt = System.currentTimeMillis())
                } }
                synchronized(meter) { meter.record(System.currentTimeMillis()) }
                if (out.ok && out.cls == ResponseClass.OK) out.key?.let { libraryChanged(it) }
                refreshLibrarySoon()
                if (out.cls == ResponseClass.BLOCKED) stop.compareAndSet(null,
                    "Website đang chặn/yêu cầu xác minh (${out.message}). Đã dừng cả hàng đợi — app không thử vượt qua. " +
                        "Hãy đợi một thời gian rồi mới bấm Tiếp tục.")
                if (out.throttledOut) {
                    if (throttledOutInRow.incrementAndGet() >= 3) stop.compareAndSet(null,
                        "Website đang giới hạn truy cập liên tục (${out.message}). Đã tạm dừng cả hàng đợi để không dồn tải — " +
                            "hãy đợi một lúc rồi bấm Tiếp tục.")
                } else throttledOutInRow.set(0)
                if (extra > 0) delay(extra)
            }
        }

        try {
            // robots.txt: tải một lần, qua cùng cổng tốc độ
            if (robots == null) { acquireSlot(); robots = withContext(Dispatchers.IO) { loadRobots() } }
            coroutineScope {
                val ticker = launch {
                    var ticks = 0
                    while (true) {
                        delay(1000)
                        val now = System.currentTimeMillis()
                        val q = _batch.value.queue
                        val left = q.pendingCount(only) + inFlight.size
                        val (rate, eta) = synchronized(meter) { meter.rate(now) to meter.etaSeconds(left, now) }
                        val limit = gateLock.withLock { adapt.limit }
                        _batch.update { it.copy(rate = rate, etaSec = eta, workerLimit = limit, currentUrls = inFlight.toList()) }
                        if (++ticks % 3 == 0) withContext(queueWriter) { flushQueue() }
                    }
                }
                (0 until n).map { idx -> launch(Dispatchers.IO) { worker(idx) } }.joinAll()
                ticker.cancel()
            }
            completed = true
        } finally {
            gate.intervalMs = DownloadConfig.minIntervalMs
            batchInterrupted = !completed || stop.get() != null || _batch.value.pauseRequested
            withContext(NonCancellable + queueWriter) { flushQueue() }
            withContext(NonCancellable) { applyLibraryChanges() }
            val stopMessage = stop.get() ?: if (_batch.value.pauseRequested) "Đã tạm dừng. Bấm Tiếp tục để tải tiếp." else null
            retryScope?.let { s -> if (_batch.value.queue.pendingCount(s) == 0) retryScope = null }
            _batch.update {
                it.copy(running = false, pauseRequested = false, currentUrls = emptyList(), waitSeconds = 0, retryOnly = retryScope?.size,
                    message = it.message.takeIf { m -> m?.startsWith("Đã huỷ") == true } ?: stopMessage
                        ?: if (only != null) "Thử lại xong: ${it.queue.items.count { i -> i.url in only && i.state == ItemState.DONE }}/${only.size} bài thành công."
                        else if (it.queue.hasPending) "Đã dừng."
                        else "Hoàn tất: ${it.queue.count(ItemState.DONE)} bài đã lưu, ${it.queue.count(ItemState.ERROR)} lỗi.")
            }
        }
    }

    private data class BatchOutcome(
        val ok: Boolean, val title: String?, val key: String?, val message: String?,
        val cls: ResponseClass, val retryAfterSec: Long? = null, val throttledOut: Boolean = false,
    )

    /** Tải + phân tích + lưu một bài. [acquire] được gọi ngay trước request (xin khe từ cổng tốc độ chung). */
    private suspend fun processBatchItem(url: String, opts: BatchQueue, acquire: suspend () -> Unit): BatchOutcome {
        // Bài đã có đủ các bản được yêu cầu (file tồn tại, không rỗng) -> không tải lại, không gửi request.
        val knownKey = store.keyForUrl(url)
        if (withContext(Dispatchers.IO) { store.isSaved(knownKey, opts.saveCompact, opts.savePage) }) {
            val title = withContext(Dispatchers.IO) { store.info(knownKey)["title"] }
            return BatchOutcome(true, title, knownKey, "Đã có trong máy — bỏ qua, không tải lại", ResponseClass.SKIPPED)
        }
        val r = robots ?: RobotsRules.FALLBACK_HOPAMVIET
        val path = UrlPolicy.path(url)
        if (!r.isAllowed(path)) return BatchOutcome(false, null, null, "robots.txt không cho phép $path", ResponseClass.FAIL)

        acquire()
        val f = withContext(Dispatchers.IO) { fetcher.fetch(url) }
        lastResponseMs = f.elapsedMs
        lastRequestAt = System.currentTimeMillis()
        coroutineContext.ensureActive()   // đã huỷ trong lúc tải -> không lưu gì

        val html = f.body
        val cls = ResponsePolicy.classify(f.status, html, networkError = f.error != null && f.status == null)
        if (cls != ResponseClass.OK || html == null)
            return BatchOutcome(false, null, null,
                Diagnosis.explainMissingLyrics(f.status, f.contentType, html, f.error),
                if (cls == ResponseClass.OK) ResponseClass.FAIL else cls, f.retryAfterSec)

        val parsed0 = onBigStack("batch-parse") { SongParser.parse(html, f.finalUrl) }
        val parsed = if (parsed0.sourceUrl == null) parsed0.copy(sourceUrl = f.finalUrl ?: url) else parsed0
        val key = store.keyFor(f.finalUrl ?: url, parsed.slugId)
        coroutineContext.ensureActive()

        val problems = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            if (opts.saveCompact) {
                if (!parsed.hasLyrics) {
                    problems.add("Không có lời: " + Diagnosis.explainMissingLyrics(f.status, f.contentType, html, null))
                } else {
                    val st = store.saveCompact(key, parsed, ChordText.toChordPro(parsed, iso(f.fetchedAtMillis)))
                    if (st.error != null) problems.add("Lưu bản gọn lỗi: ${st.error}")
                }
            }
            if (opts.savePage) {
                val st = store.savePage(key, parsed.title, html,
                    FetchMeta(f.requestedUrl, f.finalUrl ?: f.requestedUrl, f.status ?: 0, f.contentType, f.fetchedAtMillis), song = parsed)
                if (st.error != null) problems.add("Lưu nguyên trang lỗi: ${st.error}")
            }
            Unit
        }
        if (problems.size < (if (opts.saveCompact) 1 else 0) + (if (opts.savePage) 1 else 0))
            enqueueAfterSaved(key, parsed)        // ảnh YouTube + sao lưu: chạy nền, không chặn worker
        val info = "${parsed.lyricLineCount} dòng, ${parsed.inlineChordCount} hợp âm · ${f.elapsedMs} ms"
        return if (problems.isEmpty()) BatchOutcome(true, parsed.title, key, info, ResponseClass.OK)
        else BatchOutcome(false, parsed.title, key, problems.joinToString("; "), ResponseClass.FAIL)
    }

    // ---------- Hàng việc phụ sau khi lưu (1 coroutine, tuần tự, không chặn đường tải) ----------
    private val postChannel = Channel<Pair<String, ParsedSong?>>(Channel.UNLIMITED)
    private val postPending = AtomicInteger(0)
    @Volatile private var postStarted = false

    private fun enqueueAfterSaved(key: String, song: ParsedSong?) {
        val hasThumbs = song?.singers?.any { it.youtubeId != null } == true
        if (!hasThumbs && !backup.autoBackup) return
        if (!postStarted) synchronized(this) {
            if (!postStarted) {
                postStarted = true
                viewModelScope.launch(Dispatchers.IO) {
                    for ((k, s) in postChannel) {
                        runCatching { afterSaved(k, s) }
                        _batch.update { it.copy(postPending = postPending.decrementAndGet().coerceAtLeast(0)) }
                    }
                }
            }
        }
        _batch.update { it.copy(postPending = postPending.incrementAndGet()) }
        postChannel.trySend(key to song)
    }

    // =====================================================================
    // Lấy danh sách bài từ trang danh mục (thể loại/nhạc sĩ/ca sĩ/điệu/hot/mới).
    // Tải tuần tự, nghỉ BATCH_DELAY_MS giữa các trang, giới hạn số trang, chống vòng lặp.
    // Chỉ lấy link; KHÔNG tải bài cho tới khi người dùng xác nhận.
    // =====================================================================

    // ---------- Tiến độ quét (lưu file + sao lưu cùng thư mục lưu file) ----------
    private val progressFile = File(app.filesDir, "scan_progress.tsv")
    private val _progress = MutableStateFlow(runCatching { ScanProgressBook.parse(progressFile.readText()) }.getOrDefault(ScanProgressBook()))
    val progress: StateFlow<ScanProgressBook> = _progress

    /**
     * Lưu tiến độ quét. Bản trong máy ghi ngay; bản sao trong thư mục sao lưu ([mirror] = true) ghi qua SAF —
     * khi đang quét chỉ ghi một lần lúc kết thúc đợt quét (SAF chậm khi thư mục có hàng nghìn bài).
     */
    private fun saveProgress(b: ScanProgressBook, mirror: Boolean = true) {
        _progress.value = b
        runCatching { progressFile.writeText(b.serialize()) }
        if (mirror) mirrorProgress()
    }

    private fun mirrorProgress() {
        if (backup.autoBackup) runCatching { if (backup.hasValidFolder()) backup.writeExtra(ScanProgressBook.FILE_NAME, _progress.value.serialize()) }
    }

    /** Đổi URL: nếu danh sách này đã quét trước đó (kể cả trước khi tắt/cài lại app) thì nạp lại điểm "Quét tiếp". */
    fun setDiscoverUrl(v: String) {
        val p = _progress.value.get(v.trim())
        _discover.update {
            it.copy(url = v, resumeUrl = p?.resumeUrl, reachedEnd = p?.reachedEnd ?: false,
                lastScanned = p?.lastScanned ?: 0, totalPages = p?.totalPages)
        }
    }

    fun forgetProgress(listKey: String) = saveProgress(_progress.value.remove(listKey))
    private var discoverNext: String? = null

    init { setDiscoverUrl(_discover.value.url) }

    /**
     * Khi đang quét/tải/tự động: chạy dịch vụ tiền cảnh + thông báo tiến độ (tắt màn hình vẫn tải tiếp).
     * Cập nhật thông báo tối đa mỗi giây; hết việc thì tắt dịch vụ.
     */
    private fun observeForeground() {
        val ctx = getApplication<Application>()
        var lastShown = 0L
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(_batch, _discover, _auto, _delayMs) { b, d, a, ms -> listOf(b, d, a, ms) }.collect { l ->
                val b = l[0] as BatchUi; val d = l[1] as DiscoverUi; val a = l[2] as AutoUi; val ms = l[3] as Long
                val busy = b.running || d.running || a.active || b.postPending > 0
                if (!busy) { DownloadService.stop(ctx); return@collect }
                val now = System.currentTimeMillis()
                if (DownloadService.running && now - lastShown < 1000) return@collect
                lastShown = now
                val q = b.queue
                val left = q.count(ItemState.PENDING) + q.count(ItemState.RUNNING)
                val title = when {
                    a.active -> "Tự động · vòng ${a.round}"
                    d.running -> "Đang quét danh mục"
                    !b.running -> "Đang hoàn tất: ảnh YouTube & sao lưu (${b.postPending} bài)"
                    else -> "Đang tải ${q.finished}/${q.total}"
                }
                val text = when {
                    d.running -> "Trang ${d.lastScanned}${d.totalPages?.let { "/$it" } ?: ""} · ${d.items.size} bài tìm thấy"
                    b.running -> "✅ ${q.count(ItemState.DONE)}  ❌ ${q.count(ItemState.ERROR)}  ⏳ $left · " +
                        "${ThroughputMeter.formatRate(b.rate)} · còn ${ThroughputMeter.formatEta(b.etaSec)}"
                    else -> "✅ ${q.count(ItemState.DONE)}  ❌ ${q.count(ItemState.ERROR)}  ⏳ $left · còn ~${PacePolicy.etaMinutes(left, ms)} phút"
                } + if (a.active) " · đã thêm ${a.totalQueued} bài" else ""
                DownloadService.show(ctx, title, text, q.finished, if (d.running) 0 else q.total)
            }
        }
    }
    fun setDiscoverPages(n: Int) = _discover.update { it.copy(maxPages = n.coerceIn(1, ListingPolicy.MAX_PAGES_LIMIT)) }

    fun discoverStart() {
        if (queueNotReady()) { _discover.update { it.copy(message = "Đang đọc hàng đợi… thử lại sau giây lát.", isError = false) }; return }
        val d = _discover.value
        if (d.running) return
        ListingPolicy.validate(d.url)?.let { why -> _discover.update { it.copy(message = why, isError = true) }; return }
        if (_batch.value.running) { _discover.update { it.copy(message = "Hàng đợi đang tải — hãy tạm dừng trước khi quét.", isError = true) }; return }
        if (_check.value.loading) { _discover.update { it.copy(message = "Đang tải ở tab Kiểm tra URL, thử lại sau.", isError = true) }; return }
        _discover.update { it.copy(running = true, log = emptyList(), items = emptyList(), message = null, isError = false,
            resumeUrl = null, reachedEnd = false, lastScanned = 0, totalPages = null) }
        discoverJob = viewModelScope.launch { runDiscover(BatchQueue.normalizeUrl(d.url.trim()), d.maxPages) }
    }

    /**
     * Quét tiếp [DiscoverUi.maxPages] trang từ chỗ đã dừng (giữ danh sách xem trước & nhật ký cũ).
     * Bài đã lưu/đã trong hàng đợi vẫn được bỏ chọn sẵn.
     */
    fun discoverContinue() {
        if (queueNotReady()) { _discover.update { it.copy(message = "Đang đọc hàng đợi… thử lại sau giây lát.", isError = false) }; return }
        val d = _discover.value
        val from = d.resumeUrl ?: return
        if (d.running) return
        if (_batch.value.running) { _discover.update { it.copy(message = "Hàng đợi đang tải — hãy tạm dừng trước khi quét.", isError = true) }; return }
        if (_check.value.loading) { _discover.update { it.copy(message = "Đang tải ở tab Kiểm tra URL, thử lại sau.", isError = true) }; return }
        val startPage = ListingPolicy.pageIndex(from) + 1
        _discover.update { it.copy(running = true, message = null, isError = false, resumeUrl = null,
            log = it.log + "— Quét tiếp ${it.maxPages} trang từ trang $startPage —") }
        discoverJob = viewModelScope.launch { runDiscover(from, d.maxPages) }
    }

    // =====================================================================
    // TỰ ĐỘNG QUÉT & TẢI ĐẾN HẾT — quét vòng sau song song với tải, dùng chung cổng tốc độ, vẫn dừng khi website giới hạn.
    // =====================================================================
    private val _auto = MutableStateFlow(AutoUi())
    val auto: StateFlow<AutoUi> = _auto
    private var autoJob: Job? = null

    fun autoStart() {
        if (queueNotReady()) { _auto.update { it.copy(message = "Đang đọc hàng đợi… thử lại sau giây lát.", isError = false) }; return }
        val d = _discover.value
        when {
            _auto.value.active || d.running || _batch.value.running -> return
            !_check.value.confirmed -> { _auto.update { it.copy(message = "Hãy tick xác nhận quyền lưu trước.", isError = true) }; return }
            !_batch.value.queue.saveCompact && !_batch.value.queue.savePage -> {
                _auto.update { it.copy(message = "Chọn ít nhất một kiểu lưu.", isError = true) }; return }
        }
        ListingPolicy.validate(d.url)?.let { why -> _auto.update { it.copy(message = why, isError = true) }; return }
        val start = d.resumeUrl ?: if (d.reachedEnd) null else BatchQueue.normalizeUrl(d.url.trim())
        if (start == null) { _auto.update { it.copy(message = "Danh mục này đã quét tới trang cuối rồi.", isError = false) }; return }
        retryScope = null      // tự động: tải mọi bài chờ, không ở chế độ "Thử lại lỗi"
        _auto.value = AutoUi(active = true, phase = "Bắt đầu…")
        autoJob = viewModelScope.launch { runAuto(start) }
    }

    /** Dừng tự động: dừng quét ngay; nếu đang tải thì tạm dừng sau bài hiện tại (các bài chờ vẫn giữ để tải sau). */
    fun autoStop() {
        autoJob?.cancel(); autoJob = null      // huỷ luôn đợt quét đang chạy bên trong vòng tự động
        if (_discover.value.running) _discover.update { it.copy(running = false, waitSeconds = 0, currentPage = null,
            resumeUrl = discoverNext, message = "Đã dừng quét.", isError = false) }
        if (_batch.value.running) batchPause()
        _auto.update { it.copy(active = false, phase = "", message = "Đã dừng tự động. Bấm lại để chạy tiếp từ chỗ đã dừng.", isError = false) }
    }

    /**
     * Tự động (v0.9): quét vòng sau NGAY TRONG LÚC đang tải vòng trước.
     *  - Quét và tải dùng chung cổng tốc độ (tổng ≤ ~3 request/giây) → không tăng tải lên website.
     *  - Hàng đợi chỉ giữ trước tối đa ~1 vòng (maxPages × 30 bài) — nhiều hơn thì đợi tải bớt rồi mới quét tiếp.
     *  - Hàng đợi bị ngắt (website giới hạn/chặn, tạm dừng) → dừng cả quét lẫn tự động, như trước.
     */
    private suspend fun runAuto(startUrl: String) {
        var from: String? = startUrl
        var msg: String? = null
        var err = false
        val ahead = maxOf(30, _discover.value.maxPages * 30)

        /** Lý do nếu hàng đợi đã bị ngắt (không phải tải hết bài). */
        fun stoppedReason(): String? {
            val b = _batch.value
            return if (!b.running && b.queue.hasPending && batchInterrupted) (b.message ?: "hàng đợi đã dừng giữa chừng") else null
        }
        /** Còn bài chờ mà hàng đợi chưa chạy → chạy (song song với quét). false nếu không khởi động được. */
        fun ensureRunning(): Boolean {
            val b = _batch.value
            if (b.running || !b.queue.hasPending) return true
            batchStart(fromAuto = true)
            return _batch.value.running
        }
        fun progressText(): String { val q = _batch.value.queue; return "đang tải ${q.finished}/${q.total}" }

        try {
            while (true) {
                stoppedReason()?.let { why -> msg = "Dừng tự động: $why"; err = why.startsWith("Website") }
                if (msg != null) break
                val f = from
                if (f == null) {
                    // Đã quét tới trang cuối: chờ tải nốt hàng đợi
                    while (true) {
                        if (!_batch.value.running) {
                            if (!_batch.value.queue.hasPending || stoppedReason() != null || !ensureRunning()) break
                        }
                        _auto.update { it.copy(phase = "Đã quét hết · ${progressText()}") }
                        delay(1000)
                    }
                    val why = stoppedReason() ?: if (_batch.value.queue.hasPending) (_batch.value.message ?: "hàng đợi chưa tải xong") else null
                    val after = _discover.value
                    msg = if (why != null) "Dừng tự động: $why"
                        else "✅ Đã tới trang cuối (trang ${after.lastScanned}${after.totalPages?.let { "/$it" } ?: ""}) — hoàn tất."
                    err = why?.startsWith("Website") == true
                    break
                }

                // Giữ trước tối đa ~1 vòng trong hàng đợi
                while (_batch.value.running && _batch.value.queue.pendingCount(null) > ahead) {
                    _auto.update { it.copy(phase = "Vòng ${it.round}: ${progressText()} · đợi tải bớt rồi quét vòng sau") }
                    delay(1000)
                }
                if (stoppedReason() != null) continue      // vòng lặp đầu sẽ ghi lý do và dừng

                val round = _auto.value.round + 1
                val startPage = ListingPolicy.pageIndex(f) + 1
                _auto.update { it.copy(round = round,
                    phase = "Vòng $round: quét từ trang $startPage" + if (_batch.value.running) " (song song: ${progressText()})" else "") }
                // 1) quét một đợt trang (danh sách xem trước làm mới mỗi vòng cho nhẹ)
                _discover.update { it.copy(running = true, items = emptyList(), message = null, isError = false, resumeUrl = null,
                    log = (it.log + "— Tự động vòng $round: quét ${it.maxPages} trang từ trang $startPage —").takeLast(LOG_LINES)) }
                runDiscover(f, _discover.value.maxPages, abort = { stoppedReason() })
                if (stoppedReason() != null) continue
                val d = _discover.value
                if (d.isError) { msg = "Dừng tự động: " + (d.message ?: "lỗi khi quét"); err = true; break }

                // 2) thêm bài chưa lưu & chưa trong hàng đợi, rồi tải (song song với vòng quét sau)
                val urls = d.items.filter { it.selected }.map { it.song.url }
                if (urls.isNotEmpty()) {
                    val r = batchAdd(urls.joinToString("\n"))
                    _auto.update { it.copy(totalQueued = it.totalQueued + r.added, phase = "Vòng $round: thêm ${r.added} bài · ${progressText()}") }
                }
                if (!ensureRunning()) {
                    msg = "Dừng tự động: " + (_batch.value.message ?: "không bắt đầu tải được"); err = true; break
                }
                // 3) quét tiếp hay đã hết
                from = if (d.reachedEnd) null else d.resumeUrl
                if (from == null && !d.reachedEnd) { msg = "Dừng tự động: " + (d.message ?: "không còn trang để quét tiếp."); break }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            msg = "Lỗi chế độ tự động: ${e.javaClass.simpleName}: ${e.message}"; err = true
        } finally {
            if (_auto.value.active) _auto.update { it.copy(active = false, phase = "", message = msg, isError = err) }
        }
    }

    fun discoverStop() {
        if (_auto.value.active) { autoStop(); return }
        discoverJob?.cancel(); discoverJob = null
        _discover.update { it.copy(running = false, waitSeconds = 0, currentPage = null, resumeUrl = discoverNext,
            message = "Đã dừng quét. Danh sách tìm được đến lúc dừng vẫn được giữ.", isError = false) }
    }

    /** Lúc gửi request trang danh mục gần nhất (nhịp nghỉ giữa các trang khi quét song song với tải). */
    @Volatile private var lastScanAt = 0L

    private suspend fun waitForSlot(onWait: (Int) -> Unit) {
        while (true) {
            // Đang tải song song: nghỉ tính từ trang quét trước (việc tải bài đã có cổng tốc độ chung giới hạn tổng).
            val ref = if (_batch.value.running) lastScanAt else maxOf(lastRequestAt, lastScanAt)
            val wait = pace() - (System.currentTimeMillis() - ref)
            if (wait <= 0) return
            onWait(((wait + 999) / 1000).toInt())
            delay(minOf(wait, 250L))
        }
    }

    /** [abort]: trả về lý do khi cần dừng quét giữa chừng (chế độ Tự động: hàng đợi tải đã bị dừng). */
    private suspend fun runDiscover(startUrl: String, maxPages: Int, abort: (() -> String?)? = null) {
        val d = Discovery(maxPages)
        var url: String? = startUrl
        var finalMsg: String? = null
        var isError = false
        try {
            if (robots == null) {
                robots = withContext(Dispatchers.IO) { loadRobots() }
            }
            while (true) {
                val cur = url ?: break
                discoverNext = cur
                abort?.invoke()?.let { why -> finalMsg = "Dừng quét: $why"; isError = true }
                if (isError) break
                waitForSlot { s -> _discover.update { it.copy(waitSeconds = s, currentPage = null) } }
                acquireShared()
                val path = UrlPolicy.path(cur)
                if (robots?.isAllowed(path) == false) { finalMsg = "robots.txt không cho phép $path — dừng."; isError = true; break }
                _discover.update { it.copy(waitSeconds = 0, currentPage = cur) }
                lastRequestAt = System.currentTimeMillis()
                lastScanAt = lastRequestAt
                val f = withContext(Dispatchers.IO) { fetcher.fetch(cur) }
                lastResponseMs = f.elapsedMs
                lastRequestAt = System.currentTimeMillis()
                lastScanAt = lastRequestAt
                val pageNo = ListingPolicy.pageIndex(cur) + 1
                if (BatchQueue.isBlockingResponse(f.status, f.body)) {
                    finalMsg = "Website đang giới hạn/chặn (HTTP ${f.status ?: "?"}) ở trang $pageNo. Đã dừng quét — app không thử vượt qua."
                    isError = true; break
                }
                if (!f.ok || f.body == null) {
                    finalMsg = "Không tải được trang $pageNo: " + (f.error ?: "HTTP ${f.status}"); isError = true; break
                }
                val page = onBigStack("listing-parse") { ListingParser.parse(f.body, f.finalUrl ?: cur) }
                val added = d.add(cur, page)
                val total = page.lastPage
                val savedHere = withContext(Dispatchers.IO) {
                    page.songs.count { s -> store.isSaved(store.keyForUrl(s.url), _batch.value.queue.saveCompact, _batch.value.queue.savePage) }
                }
                val logLine = "Trang $pageNo${total?.let { "/$it" } ?: ""}: ${page.songs.size} bài (${added} mới" +
                    (if (savedHere > 0) ", $savedHere đã lưu" else "") + ") · HTTP ${f.status} · ${f.elapsedMs} ms" +
                    (if (page.method == "fallback") " · ⚠ không thấy cột chính, đã loại cột bên/menu" else "") +
                    (if (page.excluded > 0) " · bỏ ${page.excluded} link ngoài danh sách chính" else "")
                // Chỉ xét các bài MỚI (chưa có trong danh sách xem trước); tra hàng đợi bằng bảng băm dựng một lần.
                val known = _discover.value.items.mapTo(HashSet()) { BatchQueue.dedupeKey(it.song.url) }
                val fresh = d.found.filter { BatchQueue.dedupeKey(it.url) !in known }
                val items = withContext(Dispatchers.IO) {
                    val queued = _batch.value.queue.items.asSequence().filter { it.state != ItemState.CANCELLED }
                        .mapTo(HashSet()) { BatchQueue.dedupeKey(it.url) }
                    fresh.map { toFoundItem(it, queued) }
                }
                discoverNext = page.nextUrl
                val stopEnd = page.nextUrl == null
                withContext(Dispatchers.IO) {
                    saveProgress(_progress.value.record(cur, pageNo, total, page.nextUrl, stopEnd, System.currentTimeMillis()), mirror = false)
                }
                _discover.update { it.copy(log = (it.log + logLine).takeLast(LOG_LINES), items = mergeSelection(it.items, items),
                    lastScanned = pageNo, totalPages = total ?: it.totalPages) }
                if (d.pages == 1 && page.songs.isEmpty()) {
                    finalMsg = "Không tìm thấy link bài hát nào trên trang này. Có thể đây không phải trang danh sách bài hát, " +
                        "hoặc cấu trúc trang đã thay đổi."
                    isError = true; break
                }
                if (!d.shouldContinue(page.nextUrl, added)) {
                    finalMsg = if (d.reachedEnd) "✅ Đã tới trang cuối (trang $pageNo${total?.let { "/$it" } ?: ""}) — hết trang, không còn gì để quét tiếp."
                        else d.stopReason
                    _discover.update { it.copy(resumeUrl = d.resumeUrl, reachedEnd = d.reachedEnd) }
                    break
                }
                url = page.nextUrl
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            finalMsg = "Lỗi khi quét: ${e.javaClass.simpleName}: ${e.message}"; isError = true
        } finally {
            // Sao tiến độ quét sang thư mục sao lưu một lần cho cả đợt (thay vì sau mỗi trang)
            withContext(NonCancellable + Dispatchers.IO) { mirrorProgress() }
            _discover.update {
                if (!it.running) it   // đã dừng bằng nút Dừng quét
                else it.copy(running = false, waitSeconds = 0, currentPage = null, isError = isError,
                    // dừng vì lỗi/bị chặn giữa chừng: cho phép thử quét tiếp từ trang chưa tải được
                    resumeUrl = it.resumeUrl ?: if (isError && !it.reachedEnd) discoverNext else null,
                    message = listOfNotNull(finalMsg, "Lần này: ${d.pages} trang, ${d.found.size} bài · tổng xem trước ${it.items.size} bài.").joinToString(" "))
            }
        }
    }

    private fun toFoundItem(s: FoundSong, queuedKeys: Set<String>): FoundItem {
        val saved = store.isSaved(store.keyForUrl(s.url), compact = _batch.value.queue.saveCompact, page = _batch.value.queue.savePage)
        val queued = BatchQueue.dedupeKey(s.url) in queuedKeys
        return FoundItem(s, selected = !saved && !queued, alreadySaved = saved, inQueue = queued)
    }

    /** Giữ nguyên các bài đã hiển thị (và lựa chọn người dùng đã sửa), nối thêm bài mới vào cuối. */
    private fun mergeSelection(old: List<FoundItem>, new: List<FoundItem>): List<FoundItem> {
        val have = old.mapTo(HashSet()) { BatchQueue.dedupeKey(it.song.url) }
        return old + new.filter { have.add(BatchQueue.dedupeKey(it.song.url)) }
    }

    fun discoverToggle(url: String) = _discover.update { d ->
        d.copy(items = d.items.map { if (it.song.url == url) it.copy(selected = !it.selected) else it })
    }

    /** all = true: chọn tất cả; false: bỏ chọn tất cả; null: chỉ chọn bài chưa lưu và chưa có trong hàng đợi. */
    fun discoverSelect(all: Boolean?) = _discover.update { d ->
        d.copy(items = d.items.map { it.copy(selected = all ?: (!it.alreadySaved && !it.inQueue)) })
    }

    /** Người dùng xác nhận: thêm bài đã chọn vào hàng đợi rồi bắt đầu tải. */
    fun discoverConfirm() {
        val d = _discover.value
        val urls = d.items.filter { it.selected }.map { it.song.url }
        if (urls.isEmpty()) { _discover.update { it.copy(message = "Chưa chọn bài nào.", isError = true) }; return }
        if (!_check.value.confirmed) { _discover.update { it.copy(message = "Hãy tick xác nhận quyền lưu trước.", isError = true) }; return }
        val r = batchAdd(urls.joinToString("\n"))
        val queued = _batch.value.queue.items.map { BatchQueue.dedupeKey(it.url) }.toSet()
        _discover.update { s -> s.copy(
            items = s.items.map { if (BatchQueue.dedupeKey(it.song.url) in queued) it.copy(inQueue = true, selected = false) else it },
            message = "Đã thêm ${r.added} bài vào hàng đợi (bỏ ${r.duplicates} trùng). Bắt đầu tải…", isError = false) }
        batchStart()
    }

    // =====================================================================
    // BÀI MỚI TRÊN HỢP ÂM VIỆT (v0.11): đọc trang "Hợp âm mới nhất", so với bài đã có.
    // =====================================================================
    private val _newSongs = MutableStateFlow(NewSongsUi(daily = NewSongs.isDailyEnabled(app)))
    val newSongs: StateFlow<NewSongsUi> = _newSongs

    private fun queuedKeysNow(): Set<String> =
        if (_batch.value.queueLoading) NewSongs.queuedKeysFromFile(getApplication())
        else _batch.value.queue.items.filter { it.state != ItemState.CANCELLED }.mapTo(HashSet()) { BatchQueue.dedupeKey(it.url) }

    /** Dựng danh sách hiển thị từ kết quả: chỉ giữ bài CHƯA lưu & chưa trong hàng đợi (giữ lựa chọn cũ nếu có). */
    private suspend fun showNewSongs(r: NewSongCheck.Result?, message: String?, isError: Boolean) {
        val old = _newSongs.value.items.associateBy { BatchQueue.dedupeKey(it.song.url) }
        val items = withContext(Dispatchers.IO) {
            val known = NewSongs.knownChecker(store, queuedKeysNow())
            r?.newSongs.orEmpty().filter { !known(it.url) }.map { s ->
                FoundItem(s, selected = old[BatchQueue.dedupeKey(s.url)]?.selected ?: true, alreadySaved = false, inQueue = false)
            }
        }
        _newSongs.update { it.copy(checking = false, result = r, items = items, message = message, isError = isError) }
    }

    /** Nạp kết quả lần kiểm tra gần nhất (vd. do việc chạy nền mỗi ngày ghi ra) — khi mở app / chạm thông báo. */
    fun reloadNewSongs() {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { NewSongs.load(getApplication()) } ?: return@launch
            showNewSongs(r, null, false)
        }
    }

    fun checkNewSongs() {
        if (_newSongs.value.checking) return
        if (_discover.value.running) { _newSongs.update { it.copy(message = "Đang quét danh mục — thử lại sau.", isError = false) }; return }
        _newSongs.update { it.copy(checking = true, message = "Đang kiểm tra trang \"Hợp âm mới nhất\"…", isError = false) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                NewSongCheck.run(
                    fetch = { u ->
                        kotlinx.coroutines.runBlocking { acquireShared() }      // dùng chung giới hạn tốc độ với việc tải
                        lastRequestAt = System.currentTimeMillis()
                        fetcher.fetch(u).also { lastResponseMs = it.elapsedMs; lastRequestAt = System.currentTimeMillis() }
                            .let { NewSongCheck.Fetched(it.status, it.body, it.error) }
                    },
                    isKnown = NewSongs.knownChecker(store, queuedKeysNow()),
                    pause = { Thread.sleep(pace()) },
                    allowed = { path -> (robots ?: RobotsRules.FALLBACK_HOPAMVIET).isAllowed(path) },
                )
            }
            if (r.error == null || r.newSongs.isNotEmpty()) withContext(Dispatchers.IO) { NewSongs.save(getApplication(), r) }
            val msg = when {
                r.error != null -> r.error
                r.newSongs.isEmpty() -> "✅ Không có bài mới — mọi bài trong trang \"Hợp âm mới nhất\" đều đã có trong máy (${r.pagesRead} request)."
                r.maybeGap -> "Có ${r.newSongs.size} bài mới. ⚠ Cả ${r.pagesRead} trang \"mới nhất\" đều là bài chưa có — có thể còn bài mới cũ hơn " +
                    "đã trôi khỏi danh sách; nên quét lại các danh mục để chắc chắn đủ."
                else -> "Có ${r.newSongs.size} bài mới chưa lưu (${r.pagesRead} request)."
            }
            showNewSongs(r, msg, r.error != null)
        }
    }

    fun newSongToggle(url: String) = _newSongs.update { n ->
        n.copy(items = n.items.map { if (it.song.url == url) it.copy(selected = !it.selected) else it })
    }

    fun newSongsSelect(all: Boolean) = _newSongs.update { n -> n.copy(items = n.items.map { it.copy(selected = all) }) }

    /** Thêm các bài mới đã chọn vào hàng đợi rồi bắt đầu tải (như "Xác nhận & tải"). */
    fun newSongsDownload() {
        val urls = _newSongs.value.items.filter { it.selected }.map { it.song.url }
        when {
            urls.isEmpty() -> { _newSongs.update { it.copy(message = "Chưa chọn bài nào.", isError = true) }; return }
            !_check.value.confirmed -> { _newSongs.update { it.copy(message = "Hãy tick xác nhận quyền lưu ở đầu màn hình trước.", isError = true) }; return }
            queueNotReady() -> { _newSongs.update { it.copy(message = "Đang đọc hàng đợi… thử lại sau giây lát.", isError = false) }; return }
        }
        val r = batchAdd(urls.joinToString("\n"))
        val added = urls.mapTo(HashSet()) { BatchQueue.dedupeKey(it) }
        _newSongs.update { n -> n.copy(items = n.items.filter { BatchQueue.dedupeKey(it.song.url) !in added },
            message = "Đã thêm ${r.added} bài mới vào hàng đợi" + (if (r.duplicates > 0) " (bỏ ${r.duplicates} trùng)" else "") + ".", isError = false) }
        if (_batch.value.queue.hasPending && !_batch.value.running) batchStart()
    }

    fun setDailyNewCheck(on: Boolean) {
        NewSongs.setDailyEnabled(getApplication(), on)
        _newSongs.update { it.copy(daily = on) }
    }

    /** Nhập file TXT (mỗi dòng một link). Giới hạn 1 MB. */
    fun batchImportTxt(uri: Uri, name: String?) {
        viewModelScope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                        val bytes = input.readNBytesCompat(1_048_577)
                        require(bytes.size <= 1_048_576) { "File lớn hơn 1 MB" }
                        bytes.toString(Charsets.UTF_8)
                    }
                }
            }
            text.onSuccess { t ->
                val r = batchAdd(t)
                _batch.update { it.copy(message = "Đã nhập ${name ?: "file"}: thêm ${r.added}, trùng ${r.duplicates}, không hợp lệ ${r.invalid.size}.") }
            }.onFailure { e -> _batch.update { it.copy(message = "Không đọc được file: ${e.message}") } }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
        while (total < max) { val n = read(buf, 0, minOf(buf.size, max - total)); if (n < 0) break; out.write(buf, 0, n); total += n }
        return out.toByteArray()
    }

    // =====================================================================
    // Sau khi lưu: tải ảnh thu nhỏ YouTube (img.youtube.com, để xem offline nhận biết ca sĩ)
    // và tự sao lưu sang thư mục người dùng chọn (nếu bật). Lỗi ở đây không ảnh hưởng bản đã lưu.
    // =====================================================================
    private fun afterSaved(key: String, song: ParsedSong?) {
        song?.singers?.mapNotNull { it.youtubeId }?.distinct()?.forEach { id ->
            if (!store.hasThumb(key, id)) {
                val url = SingerInfo("", null, null, id).thumbnailUrl ?: return@forEach
                fetcher.fetchImage(url)?.let { bytes -> runCatching { store.saveThumb(key, id, bytes) } }
            }
        }
        if (backup.autoBackup && backup.hasValidFolder()) {
            runCatching { backup.backupKey(key) }.onFailure { e ->
                _storage.update { it.copy(message = "Tự sao lưu lỗi ($key): ${e.message}") }
            }
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val (name, valid) = withContext(Dispatchers.IO) { backup.folderName() to backup.hasValidFolder() }
            _storage.update { it.copy(folder = name, valid = valid, auto = backup.autoBackup) }
        }
    }

    fun chooseStorageFolder(uri: Uri) {
        runCatching { backup.setFolder(uri) }
            .onSuccess { _storage.update { it.copy(message = "Đã chọn thư mục. Bấm \"Sao lưu tất cả\" để chép các bài đã lưu.") }; refreshStorage() }
            .onFailure { e -> _storage.update { it.copy(message = "Không lấy được quyền thư mục: ${e.message}") } }
    }

    fun setAutoBackup(v: Boolean) { backup.autoBackup = v; _storage.update { it.copy(auto = v) } }

    fun backupAllNow() = runStorageJob("Đang sao lưu…") {
        val r = backup.backupAll()
        runCatching { backup.writeExtra(ScanProgressBook.FILE_NAME, _progress.value.serialize()) }
        "Đã sao lưu ${r.songs} bài (${r.files} file mới/đổi)." + errText(r.errors)
    }

    fun restoreNow() = runStorageJob("Đang khôi phục…") {
        val r = backup.restoreAll()
        backup.readExtra(ScanProgressBook.FILE_NAME)?.let { t ->
            val merged = _progress.value.merge(ScanProgressBook.parse(t))
            saveProgress(merged)
            setDiscoverUrl(_discover.value.url)
        }
        "Đã khôi phục ${r.songs} bài (${r.files} file). Bài đã có trong app được giữ nguyên." + errText(r.errors)
    }

    private fun errText(e: List<String>) = if (e.isEmpty()) "" else " Lỗi ${e.size}: " + e.take(3).joinToString("; ")

    private fun runStorageJob(busyMsg: String, job: () -> String) {
        if (_storage.value.busy) return
        if (!backup.hasValidFolder()) { _storage.update { it.copy(message = "Hãy chọn thư mục lưu trước.") }; return }
        _storage.update { it.copy(busy = true, message = busyMsg) }
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching(job).getOrElse { "Lỗi: ${it.message}" } }
            _storage.update { it.copy(busy = false, message = msg) }
            refreshLibrary()
        }
    }

    /** Ảnh thu nhỏ đã lưu của một video (null nếu chưa có). */
    fun thumbFile(key: String, youtubeId: String): File? = store.thumbFile(key, youtubeId).takeIf { it.exists() && it.length() > 0 }

    override fun onCleared() {
        DownloadService.stop(getApplication())
        // ViewModel bị huỷ (thoát app): mục đang tải quay về "Chờ" để lần sau tiếp tục.
        if (_batch.value.running) synchronized(batchLock) {
            _batch.update { it.copy(queue = it.queue.recoverAfterRestart()) }; queueDirty = true
        }
        flushQueue()                      // ghi đồng bộ lần cuối (app đang đóng)
        if (indexSaveScheduled) writeIndex(_library.value)
        queueWriter.close()
        super.onCleared()
    }

    // Đặt ở CUỐI class: mọi StateFlow (_batch, _discover, _auto, _delayMs) đã được khởi tạo xong.
    init {
        observeForeground()
        observeLibrary()
        loadLibraryAtStart()
        reloadNewSongs()
        // Đọc hàng đợi ở luồng nền: giao diện hiện ngay, không còn màn hình trắng vài giây lúc mở app.
        viewModelScope.launch {
            val q = withContext(Dispatchers.IO) { loadBatch() }
            synchronized(batchLock) { _batch.update { it.copy(queue = q, queueLoading = false) } }
        }
    }

    companion object {
        const val BATCH_DELAY_MS = 3_000L
        /** Nhật ký quét giữ tối đa bấy nhiêu dòng gần nhất (danh sách dài làm màn hình vẽ lại chậm). */
        const val LOG_LINES = 60
        private val ZONE: ZoneId = ZoneId.systemDefault()
        fun fmt(ms: Long): String = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZONE).format(Instant.ofEpochMilli(ms))
        fun iso(ms: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZONE).format(Instant.ofEpochMilli(ms))
    }
}
