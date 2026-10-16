package com.binh.chordoffline.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.binh.chordoffline.CrashLog
import com.binh.chordoffline.core.*
import com.binh.chordoffline.onBigStack
import com.binh.chordoffline.net.FetchResult
import com.binh.chordoffline.net.HttpFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val currentUrl: String? = null,
    val waitSeconds: Int = 0,
    val message: String? = null,
    val lastAdd: AddResult? = null,
)

/** Một bài tìm thấy khi quét trang danh mục, kèm trạng thái chọn để xem trước. */
data class FoundItem(val song: FoundSong, val selected: Boolean, val alreadySaved: Boolean, val inQueue: Boolean)

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

    private var robots: RobotsRules? = null
    private var lastRequestAt = 0L
    private val minIntervalMs = 5_000L

    // ---------- Tải nhiều ----------
    private val batchFile = File(app.filesDir, "batch_queue.tsv")
    private val _batch = MutableStateFlow(BatchUi(queue = loadBatch()))
    val batch: StateFlow<BatchUi> = _batch
    private var batchJob: Job? = null

    val backup = StorageBackup(app, store)

    // ---------- Màu hợp âm (lưu cho mọi bài, chỉ đổi hiển thị) ----------
    private val displayPrefs = app.getSharedPreferences("display", android.content.Context.MODE_PRIVATE)
    private val _chordStyle = MutableStateFlow(ChordStyle(
        color = displayPrefs.getLong("chord_color", ChordColors.DEFAULT),
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
            refreshLibrary()
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
            refreshLibrary()
        }
    }

    private fun refreshStatuses() {
        val key = _check.value.songKey ?: return
        viewModelScope.launch {
            val (p, c) = withContext(Dispatchers.IO) { store.status(key, Version.PAGE) to store.status(key, Version.COMPACT) }
            _check.update { it.copy(pageStatus = p, compactStatus = c) }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch { _library.value = withContext(Dispatchers.IO) { store.list() } }
    }

    fun delete(key: String, v: Version?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { if (v == null) store.deleteAll(key) else store.delete(key, v) }
            refreshLibrary(); refreshStatuses()
        }
    }

    suspend fun load(key: String, v: Version): String? = withContext(Dispatchers.IO) { store.read(key, v) }

    // =====================================================================
    // Tải nhiều URL: tuần tự, nghỉ BATCH_DELAY_MS giữa các request, có tạm dừng / tiếp tục / huỷ / thử lại.
    // Gặp 403/429/503 hoặc trang xác minh -> tự tạm dừng cả hàng đợi, không thử vượt qua.
    // =====================================================================

    private fun loadBatch(): BatchQueue =
        runCatching { BatchQueue.parse(batchFile.readText()) }.getOrDefault(BatchQueue()).recoverAfterRestart()

    private val batchLock = Any()

    /** Đổi hàng đợi và ghi ngay ra file (file nhỏ, ghi kiểu tạm-rồi-đổi-tên). */
    private fun setQueue(f: (BatchQueue) -> BatchQueue) = synchronized(batchLock) {
        val q = f(_batch.value.queue)
        _batch.update { it.copy(queue = q) }
        runCatching {
            val tmp = File(batchFile.parentFile, batchFile.name + ".tmp")
            tmp.writeText(q.serialize())
            if (!tmp.renameTo(batchFile)) { batchFile.delete(); tmp.renameTo(batchFile) }
        }
        q
    }

    fun batchAdd(text: String): AddResult {
        var result = AddResult(0, 0, emptyList())
        setQueue { q -> q.add(text).also { result = it.second }.first }
        _batch.update { it.copy(lastAdd = result, message = null) }
        return result
    }

    fun batchOptions(compact: Boolean, page: Boolean) {
        if (_batch.value.running) return
        setQueue { it.copy(saveCompact = compact, savePage = page) }
    }

    fun batchStart() {
        val b = _batch.value
        when {
            b.running -> return
            !_check.value.confirmed -> { _batch.update { it.copy(message = "Hãy xác nhận bạn có quyền lưu nội dung trước.") }; return }
            !b.queue.saveCompact && !b.queue.savePage -> { _batch.update { it.copy(message = "Chọn ít nhất một kiểu lưu.") }; return }
            !b.queue.hasPending -> { _batch.update { it.copy(message = "Không còn mục nào đang chờ.") }; return }
            _check.value.loading -> { _batch.update { it.copy(message = "Đang tải ở tab Kiểm tra URL, thử lại sau.") }; return }
            _discover.value.running -> { _batch.update { it.copy(message = "Đang quét danh mục — đợi quét xong hoặc bấm Dừng quét.") }; return }
        }
        _batch.update { it.copy(running = true, pauseRequested = false, message = null) }
        batchJob = viewModelScope.launch { runBatch() }
    }

    fun batchPause() {
        if (_batch.value.running) _batch.update { it.copy(pauseRequested = true, message = "Sẽ tạm dừng sau bài đang tải…") }
    }

    fun batchCancel() {
        batchJob?.cancel()
        batchJob = null
        setQueue { it.cancelRemaining(System.currentTimeMillis()) }
        _batch.update { it.copy(running = false, pauseRequested = false, currentUrl = null, waitSeconds = 0,
            message = "Đã huỷ các mục chưa tải. Mục đã xong vẫn được giữ.") }
    }

    fun batchRetryFailed() {
        if (_batch.value.running) return
        setQueue { it.retryFailed() }
        batchStart()
    }

    fun batchClearDone() { if (!_batch.value.running) setQueue { it.clearDone() } }
    fun batchClearAll() { if (!_batch.value.running) { setQueue { it.clearAll() }; _batch.update { it.copy(lastAdd = null, message = null) } } }

    private suspend fun runBatch() {
        var stopMessage: String? = null
        try {
            while (true) {
                val item = _batch.value.queue.next() ?: break
                // nghỉ giữa các request (tính cả request robots.txt và tab Kiểm tra URL)
                while (true) {
                    if (_batch.value.pauseRequested) break
                    val wait = BATCH_DELAY_MS - (System.currentTimeMillis() - lastRequestAt)
                    if (wait <= 0) break
                    _batch.update { it.copy(waitSeconds = ((wait + 999) / 1000).toInt(), currentUrl = null) }
                    delay(minOf(wait, 250L))
                }
                if (_batch.value.pauseRequested) { stopMessage = "Đã tạm dừng. Bấm Tiếp tục để tải tiếp."; break }

                setQueue { q -> q.update(item.url) { it.copy(state = ItemState.RUNNING, attempts = it.attempts + 1, message = null) } }
                _batch.update { it.copy(currentUrl = item.url, waitSeconds = 0) }

                val out = try {
                    processBatchItem(item.url, _batch.value.queue)
                } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    BatchOutcome(false, null, null, "Lỗi nội bộ: ${e.javaClass.simpleName}: ${e.message}", false)
                }
                setQueue { q -> q.update(item.url) {
                    it.copy(state = if (out.ok) ItemState.DONE else ItemState.ERROR, message = out.message,
                        title = out.title ?: it.title, key = out.key ?: it.key, updatedAt = System.currentTimeMillis())
                } }
                refreshLibrary()
                if (out.blocking) {
                    stopMessage = "Website đang giới hạn/chặn truy cập (${out.message}). Đã tạm dừng cả hàng đợi — " +
                        "app không thử vượt qua. Hãy đợi một thời gian rồi mới bấm Tiếp tục."
                    break
                }
            }
        } finally {
            _batch.update {
                it.copy(running = false, pauseRequested = false, currentUrl = null, waitSeconds = 0,
                    message = it.message.takeIf { m -> m?.startsWith("Đã huỷ") == true } ?: stopMessage
                        ?: if (it.queue.hasPending) "Đã dừng." else "Hoàn tất: ${it.queue.count(ItemState.DONE)} bài đã lưu, ${it.queue.count(ItemState.ERROR)} lỗi.")
            }
        }
    }

    private data class BatchOutcome(val ok: Boolean, val title: String?, val key: String?, val message: String?, val blocking: Boolean)

    private suspend fun processBatchItem(url: String, opts: BatchQueue): BatchOutcome {
        // Bài đã có đủ các bản được yêu cầu -> không tải lại (không gửi request).
        val knownKey = store.keyForUrl(url)
        if (withContext(Dispatchers.IO) { store.isSaved(knownKey, opts.saveCompact, opts.savePage) }) {
            val title = withContext(Dispatchers.IO) { store.info(knownKey)["title"] }
            return BatchOutcome(true, title, knownKey, "Đã có trong máy — bỏ qua, không tải lại", false)
        }
        val r = robots ?: withContext(Dispatchers.IO) { loadRobots() }.also {
            robots = it
            delay(BATCH_DELAY_MS)   // giữ khoảng nghỉ giữa request robots.txt và request bài hát
        }
        val path = UrlPolicy.path(url)
        if (!r.isAllowed(path)) return BatchOutcome(false, null, null, "robots.txt không cho phép $path", false)

        lastRequestAt = System.currentTimeMillis()
        val f = withContext(Dispatchers.IO) { fetcher.fetch(url) }
        lastRequestAt = System.currentTimeMillis()
        coroutineContext.ensureActive()   // đã huỷ trong lúc tải -> không lưu gì

        val blocking = BatchQueue.isBlockingResponse(f.status, f.body)
        val html = f.body
        if (!f.ok || html == null || blocking)
            return BatchOutcome(false, null, null, Diagnosis.explainMissingLyrics(f.status, f.contentType, html, f.error), blocking)

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
            withContext(Dispatchers.IO) { afterSaved(key, parsed) }
        val info = "${parsed.lyricLineCount} dòng, ${parsed.inlineChordCount} hợp âm · ${f.elapsedMs} ms"
        return if (problems.isEmpty()) BatchOutcome(true, parsed.title, key, info, false)
        else BatchOutcome(false, parsed.title, key, problems.joinToString("; "), false)
    }

    // =====================================================================
    // Lấy danh sách bài từ trang danh mục (thể loại/nhạc sĩ/ca sĩ/điệu/hot/mới).
    // Tải tuần tự, nghỉ BATCH_DELAY_MS giữa các trang, giới hạn số trang, chống vòng lặp.
    // Chỉ lấy link; KHÔNG tải bài cho tới khi người dùng xác nhận.
    // =====================================================================

    /** Đổi URL thì quên điểm "Quét tiếp" của danh sách cũ. */
    fun setDiscoverUrl(v: String) = _discover.update { it.copy(url = v, resumeUrl = null, reachedEnd = false, lastScanned = 0, totalPages = null) }
    private var discoverNext: String? = null
    fun setDiscoverPages(n: Int) = _discover.update { it.copy(maxPages = n.coerceIn(1, ListingPolicy.MAX_PAGES_LIMIT)) }

    fun discoverStart() {
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

    fun discoverStop() {
        discoverJob?.cancel(); discoverJob = null
        _discover.update { it.copy(running = false, waitSeconds = 0, currentPage = null, resumeUrl = discoverNext,
            message = "Đã dừng quét. Danh sách tìm được đến lúc dừng vẫn được giữ.", isError = false) }
    }

    private suspend fun waitForSlot(onWait: (Int) -> Unit) {
        while (true) {
            val wait = BATCH_DELAY_MS - (System.currentTimeMillis() - lastRequestAt)
            if (wait <= 0) return
            onWait(((wait + 999) / 1000).toInt())
            delay(minOf(wait, 250L))
        }
    }

    private suspend fun runDiscover(startUrl: String, maxPages: Int) {
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
                waitForSlot { s -> _discover.update { it.copy(waitSeconds = s, currentPage = null) } }
                val path = UrlPolicy.path(cur)
                if (robots?.isAllowed(path) == false) { finalMsg = "robots.txt không cho phép $path — dừng."; isError = true; break }
                _discover.update { it.copy(waitSeconds = 0, currentPage = cur) }
                lastRequestAt = System.currentTimeMillis()
                val f = withContext(Dispatchers.IO) { fetcher.fetch(cur) }
                lastRequestAt = System.currentTimeMillis()
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
                val logLine = "Trang $pageNo${total?.let { "/$it" } ?: ""}: ${page.songs.size} bài (${added} mới) · HTTP ${f.status} · ${f.elapsedMs} ms" +
                    (if (page.method == "fallback") " · ⚠ không thấy cột chính, đã loại cột bên/menu" else "") +
                    (if (page.excluded > 0) " · bỏ ${page.excluded} link ngoài danh sách chính" else "")
                val found = d.found
                val items = withContext(Dispatchers.IO) { found.map { toFoundItem(it) } }
                discoverNext = page.nextUrl
                _discover.update { it.copy(log = it.log + logLine, items = mergeSelection(it.items, items),
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
            _discover.update {
                if (!it.running) it   // đã dừng bằng nút Dừng quét
                else it.copy(running = false, waitSeconds = 0, currentPage = null, isError = isError,
                    // dừng vì lỗi/bị chặn giữa chừng: cho phép thử quét tiếp từ trang chưa tải được
                    resumeUrl = it.resumeUrl ?: if (isError && !it.reachedEnd) discoverNext else null,
                    message = listOfNotNull(finalMsg, "Lần này: ${d.pages} trang, ${d.found.size} bài · tổng xem trước ${it.items.size} bài.").joinToString(" "))
            }
        }
    }

    private fun toFoundItem(s: FoundSong): FoundItem {
        val saved = store.isSaved(store.keyForUrl(s.url), compact = _batch.value.queue.saveCompact, page = _batch.value.queue.savePage)
        val key = BatchQueue.dedupeKey(s.url)
        val queued = _batch.value.queue.items.any { BatchQueue.dedupeKey(it.url) == key && it.state != ItemState.CANCELLED }
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
        "Đã sao lưu ${r.songs} bài (${r.files} file mới/đổi)." + errText(r.errors)
    }

    fun restoreNow() = runStorageJob("Đang khôi phục…") {
        val r = backup.restoreAll()
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
        // ViewModel bị huỷ (thoát app): mục đang tải quay về "Chờ" để lần sau tiếp tục.
        if (_batch.value.running) setQueue { it.recoverAfterRestart() }
        super.onCleared()
    }

    companion object {
        const val BATCH_DELAY_MS = 3_000L
        private val ZONE: ZoneId = ZoneId.systemDefault()
        fun fmt(ms: Long): String = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZONE).format(Instant.ofEpochMilli(ms))
        fun iso(ms: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZONE).format(Instant.ofEpochMilli(ms))
    }
}
