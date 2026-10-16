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
import com.binh.chordoffline.core.ChordText
import com.binh.chordoffline.core.LibrarySearch
import com.binh.chordoffline.core.SaveState
import com.binh.chordoffline.core.SearchField
import com.binh.chordoffline.core.SortMode
import com.binh.chordoffline.core.Transpose
import com.binh.chordoffline.core.TransposeState
import com.binh.chordoffline.core.Version

@Composable
fun LibraryScreen(vm: AppViewModel, modifier: Modifier, onOpen: (String, Version) -> Unit) {
    val songs by vm.library.collectAsState()
    LaunchedEffect(Unit) { vm.refreshLibrary() }
    var confirmDelete by remember { mutableStateOf<Pair<String, Version?>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var field by rememberSaveable { mutableStateOf(SearchField.ALL) }
    var sort by rememberSaveable { mutableStateOf(SortMode.RECENT) }
    val shown = remember(songs, query, field, sort) { LibrarySearch.filter(songs, query, field, sort) }

    if (songs.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Chưa lưu bài nào. Dùng tab \"Kiểm tra URL\" hoặc \"Tải nhiều\" để tải và lưu.")
        }
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tìm tên bài, ca sĩ, nhạc sĩ (không cần dấu)") },
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") } },
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SearchField.entries.forEach { f -> FilterChip(selected = field == f, onClick = { field = f }, label = { Text(f.label) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Sắp xếp:", style = MaterialTheme.typography.bodySmall)
                    SortMode.entries.forEach { m -> FilterChip(selected = sort == m, onClick = { sort = m }, label = { Text(m.label) }) }
                }
                Text("${shown.size}/${songs.size} bài · mở offline, không cần mạng", style = MaterialTheme.typography.labelSmall)
            }
            if (shown.isEmpty()) item { Text("Không có bài nào khớp \"$query\".") }
            items(shown, key = { it.key }) { song ->
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
    }

    confirmDelete?.let { (key, ver) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Xoá?") },
            text = { Text(if (ver == null) "Xoá mọi bản đã lưu của bài này." else "Xoá bản \"${ver.label}\".") },
            confirmButton = { TextButton(onClick = { vm.delete(key, ver); confirmDelete = null }) { Text("Xoá") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Huỷ") } },
        )
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
            Text(listOfNotNull(doc.meta["rhythm"]?.let { "Điệu: $it" },
                doc.meta["singer_tones"]?.let { "Tone ca sĩ: $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
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
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            val chordColor = MaterialTheme.colorScheme.primary
            SelectionContainer {
                if (!chordsAbove) {
                    Text(buildAnnotatedString {
                        var last = 0
                        ChordText.CHORD.findAll(body).forEach { m ->
                            append(body.substring(last, m.range.first))
                            withStyle(SpanStyle(color = chordColor, fontWeight = FontWeight.Bold)) { append(m.value) }
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
                                    if (c.isNotEmpty()) Text(c, style = mono, color = chordColor,
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
                    loadDataWithBaseURL(null, withViewerCss(h), "text/html", "utf-8", null)
                }
            })
        }
    }
}

/**
 * CSS chỉ dùng khi XEM (file đã lưu giữ nguyên): trang gốc dựa vào CSS/JS bên ngoài nên khi offline
 * icon SVG phình to và lời mất xuống dòng. Thu nhỏ icon, ẩn quảng cáo, giữ xuống dòng của lời.
 */
private fun withViewerCss(html: String): String {
    val css = """<style id="chord-offline-viewer">
        body{font-family:sans-serif;padding:8px;line-height:1.5}
        svg,img{max-width:1.4em!important;max-height:1.4em!important;width:auto!important;height:auto!important}
        ins.adsbygoogle,.ad-container,iframe{display:none!important}
        #lyricBox{border:1px solid #ccc;border-radius:8px;padding:12px;margin:12px 0}
        .lyric-version{white-space:pre-wrap;font-size:17px;line-height:1.7}
        .chord{color:#0B7285;font-weight:bold}
        </style>"""
    val i = html.indexOf("</head>", ignoreCase = true)
    return if (i >= 0) html.substring(0, i) + css + html.substring(i) else css + html
}
