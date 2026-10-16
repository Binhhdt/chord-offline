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
import com.binh.chordoffline.core.ChordText
import com.binh.chordoffline.core.SaveState
import com.binh.chordoffline.core.Version

@Composable
fun LibraryScreen(vm: AppViewModel, modifier: Modifier, onOpen: (String, Version) -> Unit) {
    val songs by vm.library.collectAsState()
    LaunchedEffect(Unit) { vm.refreshLibrary() }
    var confirmDelete by remember { mutableStateOf<Pair<String, Version?>?>(null) }

    if (songs.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Chưa lưu bài nào. Dùng tab \"Kiểm tra URL\" để tải và lưu.")
        }
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(songs, key = { it.key }) { song ->
                SectionCard(song.title) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactViewer(vm: AppViewModel, key: String, onBack: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) { text = vm.load(key, Version.COMPACT) ?: "" }
    var chordsAbove by remember { mutableStateOf(false) }
    var size by remember { mutableFloatStateOf(16f) }
    val doc = text?.let { ChordText.parseChordPro(it) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(doc?.meta?.get("title") ?: "Lời/hợp âm", maxLines = 1) },
            navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
        )
    }) { pad ->
        if (doc == null) { Box(Modifier.padding(pad).fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }; return@Scaffold }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            doc.meta["composer"]?.let { Text("Sáng tác: $it", style = MaterialTheme.typography.bodySmall) }
            Text(listOfNotNull(doc.meta["key"]?.let { "Tone: $it" }, doc.meta["rhythm"]?.let { "Điệu: $it" },
                doc.meta["singer_tones"]?.let { "Tone ca sĩ: $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
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
                        ChordText.CHORD.findAll(doc.body).forEach { m ->
                            append(doc.body.substring(last, m.range.first))
                            withStyle(SpanStyle(color = chordColor, fontWeight = FontWeight.Bold)) { append(m.value) }
                            last = m.range.last + 1
                        }
                        append(doc.body.substring(last))
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
                            doc.body.lines().forEach { line ->
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
