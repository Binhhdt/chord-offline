package com.binh.chordoffline.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.binh.chordoffline.core.ChordColors
import com.binh.chordoffline.core.ChordStyle
import com.binh.chordoffline.core.SavedSong
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.binh.chordoffline.core.SingerExtractor
import com.binh.chordoffline.core.SingerInfo
import com.binh.chordoffline.core.ChordText
import com.binh.chordoffline.core.LibrarySearch
import com.binh.chordoffline.core.SaveState
import com.binh.chordoffline.core.SearchField
import com.binh.chordoffline.core.SortMode
import com.binh.chordoffline.core.Transpose
import com.binh.chordoffline.core.TransposeState
import com.binh.chordoffline.core.Version
import com.binh.chordoffline.AppVersion

@Composable
fun LibraryScreen(vm: AppViewModel, modifier: Modifier, onOpen: (String, Version) -> Unit) {
    val songs by vm.library.collectAsState()
    val entries by vm.searchIndex.collectAsState()
    val loading by vm.libraryLoading.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshLibraryIfNeeded(); vm.refreshStorage() }
    var confirmDelete by remember { mutableStateOf<Pair<String, Version?>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var applied by rememberSaveable { mutableStateOf("") }        // truy vấn đang lọc danh sách
    var showSuggest by rememberSaveable { mutableStateOf(false) }
    var field by rememberSaveable { mutableStateOf(SearchField.ALL) }
    var sort by rememberSaveable { mutableStateOf(SortMode.RECENT) }
    // Lọc/sắp xếp/gợi ý chạy ở luồng nền trên chỉ mục bỏ dấu tính sẵn (vm.searchIndex).
    // Không tìm & xếp "Mới lưu": danh sách thư viện đã đúng thứ tự -> hiện ngay, không cần tính.
    val indexReady = entries.size == songs.size
    val plain = applied.isBlank() && sort == SortMode.RECENT
    val computed by produceState<List<SavedSong>?>(initialValue = null, entries, applied, field, sort, plain) {
        if (plain) { value = null; return@produceState }
        value = null                               // đang tính -> "Đang tìm…"
        if (entries.isEmpty() && songs.isNotEmpty()) return@produceState   // chỉ mục chưa dựng xong; sẽ tính lại khi xong
        value = withContext(Dispatchers.Default) { LibrarySearch.filterIndexed(entries, applied, field, sort) }
    }
    val shown: List<SavedSong>? = if (plain) songs else computed
    val suggestions by produceState<List<SavedSong>?>(initialValue = emptyList(), entries, query, indexReady) {
        if (query.isBlank()) { value = emptyList(); return@produceState }
        value = null                               // đang tìm
        if (!indexReady) return@produceState
        kotlinx.coroutines.delay(150)              // đợi ngừng gõ một chút rồi mới tìm
        value = withContext(Dispatchers.Default) { LibrarySearch.suggestIndexed(entries, query) }
    }
    fun openSong(s: SavedSong) = onOpen(s.key, if (s.compact.state == SaveState.SAVED) Version.COMPACT else Version.PAGE)
    fun runSearch() { applied = query; showSuggest = false }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SearchBox(query, onChange = { query = it; showSuggest = true; if (it.isBlank()) applied = "" },
                onSearch = ::runSearch, onClear = { query = ""; applied = ""; showSuggest = false })
            if (showSuggest && query.isNotBlank()) SuggestionCard(suggestions, query, onPick = { openSong(it) }, onAll = ::runSearch)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SearchField.entries.forEach { f -> FilterChip(selected = field == f, onClick = { field = f }, label = { Text(f.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Sắp xếp:", style = MaterialTheme.typography.bodySmall)
                SortMode.entries.forEach { m -> FilterChip(selected = sort == m, onClick = { sort = m }, label = { Text(m.label) }) }
            }
            Text(when {
                loading && songs.isEmpty() -> "Đang tải thư viện…"
                shown == null -> "Đang tìm…"
                else -> (if (applied.isNotBlank()) "Kết quả cho \"$applied\": " else "") +
                    "${shown.size}/${songs.size} bài · mở offline, không cần mạng"
            } + " · " + AppVersion.label(context), style = MaterialTheme.typography.labelSmall)
        }
        item { StorageCard(vm) }
        if (loading && songs.isEmpty()) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Đang tải thư viện…")
            }
        } else if (songs.isEmpty()) item {
            Text("Chưa lưu bài nào. Dùng tab \"Kiểm tra URL\" hoặc \"Tải nhiều\" để tải và lưu, " +
                "hoặc bấm \"Khôi phục\" nếu đã sao lưu trước đó.")
        } else if (shown == null) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Đang tìm…")
            }
        } else if (shown.isEmpty()) item { Text("Không có bài nào khớp \"$applied\".") }
        items(shown ?: emptyList(), key = { it.key }) { song ->
            SectionCard(song.title) {
                val info = listOfNotNull(song.singers?.let { "Ca sĩ: $it" }, song.composers?.let { "Nhạc sĩ: $it" },
                    song.tone?.let { "Tone: $it" })
                if (info.isNotEmpty()) Text(info.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                song.sourceUrl?.let { Text("Nguồn: $it", style = MaterialTheme.typography.labelSmall) }
                listOf(Version.COMPACT to song.compact, Version.PAGE to song.page).forEach { (ver, st) ->
                    Spacer(Modifier.height(4.dp))
                    Text(ver.label, fontWeight = FontWeight.SemiBold)
                    Text(statusText(st), style = MaterialTheme.typography.bodySmall)
                    if (st.state == SaveState.SAVED) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpen(song.key, ver) }) { Text("Mở") }
                        TextButton(onClick = { confirmDelete = song.key to ver }) { Text("Xoá bản này") }
                    }
                }
                TextButton(onClick = { confirmDelete = song.key to null }) { Text("Xoá cả bài") }
            }
        }
    }

    confirmDelete?.let { (key, ver) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Xoá?") },
            text = { Text((if (ver == null) "Xoá mọi bản đã lưu của bài này." else "Xoá bản \"${ver.label}\".") +
                " Bản sao trong thư mục lưu file (nếu có) không bị xoá.") },
            confirmButton = { TextButton(onClick = { vm.delete(key, ver); confirmDelete = null }) { Text("Xoá") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Huỷ") } },
        )
    }
}

/** Ô tìm kiếm kiểu website: 🔍 + ô nhập + 🎤 + ✕ + nút "Tìm". */
@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    var micError by remember { mutableStateOf<String?>(null) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onChange(it) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onChange, singleLine = true, modifier = Modifier.weight(1f),
            placeholder = { Text("Tìm bài hát, ca sĩ, nhạc sĩ") },
            leadingIcon = { Text("🔍") },
            trailingIcon = {
                Row {
                    TextButton(onClick = {
                        micError = null
                        try {
                            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN"))
                        } catch (e: ActivityNotFoundException) { micError = "Máy không có chức năng nhận giọng nói." }
                    }, contentPadding = PaddingValues(4.dp)) { Text("🎤") }
                    if (query.isNotEmpty()) TextButton(onClick = onClear, contentPadding = PaddingValues(4.dp)) { Text("✕") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Button(onClick = onSearch, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Tìm") }
    }
    micError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
}

/** Danh sách gợi ý khi đang gõ (tối đa 6 bài) + "Xem tất cả kết quả". Bấm vào bài để mở offline ngay. */
@Composable
private fun SuggestionCard(found: List<SavedSong>?, query: String, onPick: (SavedSong) -> Unit, onAll: () -> Unit) {
    val list = found ?: emptyList()
    ElevatedCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (found == null) Text("Đang tìm…", Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
        else if (list.isEmpty()) Text("Không có bài đã lưu nào khớp \"$query\".", Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall)
        list.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🎵", color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    (s.singers ?: s.composers)?.let {
                        Text("♪ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        Text("Xem tất cả kết quả cho \"$query\" →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clickable { onAll() }.padding(14.dp))
    }
}

/** Thư mục lưu file ngoài app (còn lại khi gỡ app). */
@Composable
private fun StorageCard(vm: AppViewModel) {
    val st by vm.storage.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.chooseStorageFolder(uri)
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("📁 Thư mục lưu file", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (st.valid) "✓ ${st.folder}" else "chưa chọn", style = MaterialTheme.typography.bodySmall,
                    color = if (st.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(if (open) " ▲" else " ▼")
            }
            if (open) {
                Text("Chọn một thư mục (ví dụ Download/HopAm). App chép các bài đã lưu vào " +
                    "<thư mục>/ChordOffline/<mã bài>/ (song.cho, page.html, ảnh ca sĩ…). Bản sao này vẫn còn khi gỡ app; " +
                    "cài lại rồi bấm Khôi phục. Dữ liệu chỉ nằm trên máy bạn, không gửi đi đâu.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(null) }, enabled = !st.busy) { Text(if (st.valid) "Đổi thư mục" else "Chọn thư mục") }
                    OutlinedButton(onClick = vm::backupAllNow, enabled = st.valid && !st.busy) { Text("Sao lưu tất cả") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::restoreNow, enabled = st.valid && !st.busy) { Text("Khôi phục") }
                    Switch(checked = st.auto, onCheckedChange = vm::setAutoBackup)
                    Text("Tự sao lưu sau mỗi lần lưu", style = MaterialTheme.typography.bodySmall)
                }
                if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            st.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Lưu trạng thái chuyển tone qua xoay màn hình: [steps, lịch sử...]. */
private val TransposeSaver = Saver<TransposeState, ArrayList<Int>>(
    save = { ArrayList(listOf(it.steps) + it.history) },
    restore = { TransposeState(it.first(), it.drop(1)) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactViewer(vm: AppViewModel, key: String, onBack: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) { text = vm.load(key, Version.COMPACT) ?: "" }
    var chordsAbove by remember { mutableStateOf(false) }
    var size by remember { mutableFloatStateOf(16f) }
    var tr by rememberSaveable(key, stateSaver = TransposeSaver) { mutableStateOf(TransposeState()) }
    val style by vm.chordStyle.collectAsState()
    val chordColor = Color(style.color)
    val chordBg = if (style.highlight) chordColor.copy(alpha = 0.18f) else Color.Unspecified
    var showColors by remember { mutableStateOf(false) }
    if (showColors) ChordColorDialog(style, onPick = vm::setChordColor, onHighlight = vm::setChordHighlight,
        onDismiss = { showColors = false })
    val doc = text?.let { ChordText.parseChordPro(it) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(doc?.meta?.get("title") ?: "Lời/hợp âm", maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
        )
    }) { pad ->
        if (doc == null) { Box(Modifier.padding(pad).fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }; return@Scaffold }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // Chuyển tone chỉ đổi phần hiển thị; file .cho gốc không bị sửa.
            val origKey = doc.meta["key"]
            val flats = remember(doc.body, origKey, tr.steps) { Transpose.preferFlats(origKey, tr.steps, doc.body) }
            val body = remember(doc.body, tr.steps, flats) { Transpose.text(doc.body, tr.steps, flats) }
            val keyLabel = when {
                origKey == null -> if (tr.steps == 0) "gốc" else tr.label
                tr.steps == 0 -> "$origKey (gốc)"
                else -> "$origKey → ${Transpose.key(origKey, tr.steps)} (${tr.label})"
            }
            doc.meta["composer"]?.let { Text("Sáng tác: $it", style = MaterialTheme.typography.bodySmall) }
            doc.meta["rhythm"]?.let { Text("Điệu: $it", style = MaterialTheme.typography.bodySmall) }
            val singers = remember(doc.meta) { SingerExtractor.fromMeta(doc.meta) }
            if (singers.isNotEmpty()) SingerSection(vm, key, singers, origKey,
                onUseTone = { steps -> tr = tr.to(steps) })
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tone: $keyLabel", fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { tr = tr.down() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("−½") }
                OutlinedButton(onClick = { tr = tr.up() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+½") }
                TextButton(onClick = { tr = tr.undo() }, enabled = tr.canUndo) { Text("Hoàn tác") }
                TextButton(onClick = { tr = tr.reset() }, enabled = tr.steps != 0) { Text("Về gốc") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !chordsAbove, onClick = { chordsAbove = false }, label = { Text("Inline") })
                FilterChip(selected = chordsAbove, onClick = { chordsAbove = true }, label = { Text("Hợp âm phía trên") })
                TextButton(onClick = { size = (size - 1).coerceAtLeast(10f) }) { Text("A-") }
                TextButton(onClick = { size = (size + 1).coerceAtMost(30f) }) { Text("A+") }
                TextButton(onClick = { showColors = true }) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(chordColor))
                    Text(" Màu")
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                if (!chordsAbove) {
                    Text(buildAnnotatedString {
                        var last = 0
                        ChordText.CHORD.findAll(body).forEach { m ->
                            append(body.substring(last, m.range.first))
                            withStyle(SpanStyle(color = chordColor, fontWeight = FontWeight.Bold, background = chordBg)) { append(m.value) }
                            last = m.range.last + 1
                        }
                        append(body.substring(last))
                    }, fontSize = size.sp, lineHeight = (size * 1.5f).sp)
                } else {
                    // Ngắt dòng theo số cột vừa màn hình, giữ hợp âm thẳng cột với chữ (font monospace).
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val measurer = rememberTextMeasurer()
                        val density = LocalDensity.current
                        val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp)
                        val charPx = remember(size) { measurer.measure("MMMMMMMMMM", mono).size.width / 10f }
                        val cols = with(density) { (maxWidth.toPx() / charPx).toInt() }.coerceAtLeast(12)
                        Column {
                            body.lines().forEach { line ->
                                ChordText.wrapAbove(line, cols).forEach { (c, l) ->
                                    if (c.isNotEmpty()) Text(chordLine(c, chordBg), style = mono, color = chordColor,
                                        fontWeight = FontWeight.Bold, softWrap = false, maxLines = 1)
                                    Text(l.ifEmpty { " " }, style = mono, softWrap = false, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Text("Nguồn: ${doc.meta["source_site"] ?: "Hợp Âm Việt"} — ${doc.meta["source"] ?: ""}",
                style = MaterialTheme.typography.labelSmall)
            doc.meta["fetched_at"]?.let { Text("Tải lúc: $it · Bản lưu cá nhân, không chia sẻ lại.", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageViewer(vm: AppViewModel, key: String, onBack: () -> Unit) {
    var html by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) { html = vm.load(key, Version.PAGE) ?: "" }
    val meta = remember(key) { vm.store.meta(key, Version.PAGE) }
    val chordArgb = vm.chordStyle.collectAsState().value.color
    Scaffold(topBar = {
        TopAppBar(title = { Text("Nguyên trang (offline)", maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text("←") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("Nguồn: ${meta["finalUrl"] ?: ""} · HTTP ${meta["httpStatus"] ?: "?"} · hiển thị không JS, không tải mạng nên thiếu CSS/ảnh.",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
            val h = html
            if (h == null) CircularProgressIndicator(Modifier.padding(24.dp))
            else AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    loadDataWithBaseURL(null, withViewerCss(h, ChordColors.toHex(chordArgb)), "text/html", "utf-8", null)
                }
            })
        }
    }
}

/**
 * CSS chỉ dùng khi XEM (file đã lưu giữ nguyên): trang gốc dựa vào CSS/JS bên ngoài nên khi offline
 * icon SVG phình to và lời mất xuống dòng. Thu nhỏ icon, ẩn quảng cáo, giữ xuống dòng của lời.
 */
private fun withViewerCss(html: String, chordHex: String = "#2B3CC2"): String {
    val css = """<style id="chord-offline-viewer">
        body{font-family:sans-serif;padding:8px;line-height:1.5}
        svg,img{max-width:1.4em!important;max-height:1.4em!important;width:auto!important;height:auto!important}
        ins.adsbygoogle,.ad-container,iframe{display:none!important}
        #lyricBox{border:1px solid #ccc;border-radius:8px;padding:12px;margin:12px 0}
        .lyric-version{white-space:pre-wrap;font-size:17px;line-height:1.7}
        .chord{color:$chordHex;font-weight:bold}
        </style>"""
    val i = html.indexOf("</head>", ignoreCase = true)
    return if (i >= 0) html.substring(0, i) + css + html.substring(i) else css + html
}

/**
 * Tone ca sĩ: ảnh thu nhỏ video (đã lưu sẵn, xem được offline để nhận biết ca sĩ nam/nữ),
 * nút mở YouTube (cần mạng) và nút chuyển hợp âm sang tone của ca sĩ.
 */
@Composable
private fun SingerSection(vm: AppViewModel, key: String, singers: List<SingerInfo>, songKey: String?, onUseTone: (Int) -> Unit) {
    val context = LocalContext.current
    Text("Tone ca sĩ", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    singers.forEach { s ->
        val thumb by produceState<ImageBitmap?>(null, key, s.youtubeId) {
            value = s.youtubeId?.let { id ->
                withContext(Dispatchers.IO) { vm.thumbFile(key, id)?.let { f -> BitmapFactory.decodeFile(f.path)?.asImageBitmap() } }
            }
        }
        fun openYoutube() {
            val url = s.youtubeUrl ?: return
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: ActivityNotFoundException) { }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val img = thumb
            if (img != null) {
                Box(Modifier.width(128.dp).height(72.dp).clickable { openYoutube() }) {
                    Image(img, contentDescription = "Ảnh video ${s.name}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Text("▶", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.Center))
                }
            } else if (s.youtubeId != null) {
                Box(Modifier.width(128.dp).height(72.dp).clickable { openYoutube() }, contentAlignment = Alignment.Center) {
                    Text("▶ Video\n(chưa có ảnh)", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                s.tone?.let { Text("Tone: $it", style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val steps = Transpose.stepsBetween(songKey, s.tone)
                    if (steps != null) TextButton(onClick = { onUseTone(steps) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("Dùng tone này")
                    }
                    if (s.youtubeId != null) TextButton(onClick = { openYoutube() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("YouTube")
                    }
                }
            }
        }
    }
    if (singers.any { it.youtubeId != null }) Text("Ảnh xem được offline; mở video YouTube cần có mạng.",
        style = MaterialTheme.typography.labelSmall)
}

/** Dòng hợp âm (chế độ phía trên): tô nền từng tên hợp âm, giữ nguyên khoảng trắng để thẳng cột. */
private fun chordLine(c: String, bg: Color) = buildAnnotatedString {
    if (bg == Color.Unspecified) { append(c); return@buildAnnotatedString }
    Regex("""\S+|\s+""").findAll(c).forEach { m ->
        if (m.value.isBlank()) append(m.value) else withStyle(SpanStyle(background = bg)) { append(m.value) }
    }
}

/** Chọn màu hợp âm: bảng màu dễ đọc trên nền tối, mã hex tuỳ chỉnh (kiểm tra độ tương phản), tô nền. */
@Composable
private fun ChordColorDialog(style: ChordStyle, onPick: (Long) -> Unit, onHighlight: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var hex by remember { mutableStateOf(ChordColors.toHex(style.color)) }
    val parsed = ChordColors.parseHex(hex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Màu hợp âm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChordColors.PALETTE.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { c ->
                            val selected = c.argb == style.color
                            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(c.argb))
                                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { onPick(c.argb); hex = ChordColors.toHex(c.argb) },
                                contentAlignment = Alignment.Center) {
                                if (selected) Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Text(ChordColors.PALETTE.firstOrNull { it.argb == style.color }?.name ?: "Màu tuỳ chọn",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = hex, onValueChange = { hex = it.take(7) }, singleLine = true,
                        label = { Text("Mã màu #RRGGBB") }, modifier = Modifier.weight(1f))
                    Button(onClick = { parsed?.let(onPick) }, enabled = parsed != null) { Text("Áp dụng") }
                }
                if (parsed != null && !ChordColors.readable(parsed)) Text(
                    "Màu này hơi tối trên nền đen (tương phản ${"%.1f".format(ChordColors.contrast(parsed, ChordColors.BACKGROUND))}:1, nên ≥ 4.5:1) — có thể khó đọc.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = style.highlight, onCheckedChange = onHighlight)
                    Text("Tô nền mờ sau hợp âm")
                }
                // xem trước
                Text(buildAnnotatedString {
                    val bg = if (style.highlight) Color(style.color).copy(alpha = 0.18f) else Color.Unspecified
                    withStyle(SpanStyle(color = Color(style.color), fontWeight = FontWeight.Bold, background = bg)) { append("[Am]") }
                    append(" Chiều nay mưa bay trên ")
                    withStyle(SpanStyle(color = Color(style.color), fontWeight = FontWeight.Bold, background = bg)) { append("[G]") }
                    append(" phố nhỏ")
                }, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(10.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Xong") } },
        dismissButton = { TextButton(onClick = { onPick(ChordColors.DEFAULT); onHighlight(false); hex = ChordColors.toHex(ChordColors.DEFAULT) }) { Text("Mặc định") } },
    )
}
