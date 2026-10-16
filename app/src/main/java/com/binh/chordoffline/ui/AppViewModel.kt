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
import kotlinx.coroutines.Dispatchers
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

    fun setUrl(v: String) = _check.update { it.copy(url = v) }
    fun setConfirmed(v: Boolean) = _check.update { it.copy(confirmed = v) }

    /** Tải trực tiếp URL (một request cho mỗi lần bấm, cách nhau tối thiểu 5 giây). */
    fun fetchAndInspect() {
        val s = _check.value
        if (!s.confirmed) { _check.update { it.copy(message = "Hãy xác nhận bạn có quyền lưu nội dung này trước.") }; return }
        UrlPolicy.validateSongUrl(s.url)?.let { reason -> _check.update { it.copy(message = reason) }; return }
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
                    FetchMeta(f.requestedUrl, f.finalUrl ?: f.requestedUrl, f.status ?: 0, f.contentType, f.fetchedAtMillis))
            }
            _check.update { it.copy(pageStatus = st, songKey = key) }
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

    companion object {
        private val ZONE: ZoneId = ZoneId.systemDefault()
        fun fmt(ms: Long): String = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZONE).format(Instant.ofEpochMilli(ms))
        fun iso(ms: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZONE).format(Instant.ofEpochMilli(ms))
    }
}
