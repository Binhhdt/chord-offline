package com.binh.chordoffline.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.binh.chordoffline.CrashLog
import com.binh.chordoffline.core.*

@Composable
fun CheckScreen(vm: AppViewModel, modifier: Modifier, onOpen: (String, Version) -> Unit) {
    val s by vm.check.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.compareWithSaved(uri, displayName(context, uri))
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Kiểm tra tải trực tiếp", style = MaterialTheme.typography.titleLarge)
        var lastCrash by remember { mutableStateOf(CrashLog.read(context)) }
        lastCrash?.let { log ->
            ErrorCard("App đã bị lỗi ở lần chạy trước", log, onDismiss = { CrashLog.clear(context); lastCrash = null })
        }
        OutlinedTextField(
            value = s.url, onValueChange = vm::setUrl, label = { Text("URL trang bài hát") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = s.confirmed, onCheckedChange = vm::setConfirmed)
            Text(
                "Tôi có quyền lưu nội dung này, chỉ dùng cá nhân/phi thương mại trên máy này, " +
                    "giữ ghi nguồn và không chia sẻ lại (theo điều khoản Hợp Âm Việt).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = vm::fetchAndInspect, enabled = s.confirmed && !s.loading) {
            Text(if (s.loading) "Đang tải…" else "Tải trực tiếp & kiểm tra")
        }
        if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        s.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        s.errorDetail?.let { ErrorCard("Chi tiết lỗi", it, onDismiss = null) }
        s.robotsInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        s.fetch?.let { f ->
            SectionCard("1. Phản hồi HTTP") {
                KV("HTTP status", f.status?.let { "$it ${f.statusMessage.orEmpty()}" } ?: "—")
                KV("URL yêu cầu", f.requestedUrl)
                KV("URL sau redirect", f.finalUrl ?: "—")
                KV("Số lần redirect", f.redirects.toString())
                KV("Content-Type", f.contentType ?: "—")
                KV("Kích thước", "${f.bytes} byte (${"%.1f".format(f.bytes / 1024.0)} KB)" + if (f.truncated) " — ĐÃ CẮT" else "")
                KV("Thời gian tải", "${f.elapsedMs} ms")
                KV("Giao thức", f.protocol ?: "—")
                KV("Thời điểm", AppViewModel.fmt(f.fetchedAtMillis))
                f.error?.let { KV("Lỗi", it) }
                Text("Không ghi/hiển thị cookie hay header nhạy cảm.", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (s.selectors.isNotEmpty()) {
            SectionCard("2. Selector trong HTML server") {
                s.selectors.forEach { r ->
                    Text(
                        "${if (r.found) "✅" else "❌"} ${r.selector}  ×${r.count}",
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    r.detail?.let { Text("    $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        s.parsed?.let { p ->
            SectionCard("Dữ liệu đọc được") {
                KV("Tên bài", p.title ?: "—")
                KV("Sáng tác", p.composers.joinToString().ifBlank { "—" })
                KV("Tone", p.tone ?: "—")
                KV("Điệu", p.rhythm ?: "—")
                KV("ID", "${p.slugId ?: "—"} / ${p.songId ?: "—"}")
                KV("Lời", if (p.hasLyrics) "${p.lyricLineCount} dòng, ${p.inlineChordCount} hợp âm inline" else "không có")
                KV("Ca sĩ / video", if (p.singers.isEmpty()) "không có" else p.singers.joinToString("; ") {
                    "${it.name}${it.tone?.let { t -> " ($t)" } ?: ""}${if (it.youtubeId != null) " ▶" else ""}" } +
                    " · ${p.singers.count { it.youtubeId != null }}/${p.singers.size} có video")
            }
        }
        s.missingReason?.let { reason ->
            SectionCard("Không lấy được lời/hợp âm") {
                Text(reason)
                Spacer(Modifier.height(6.dp))
                Text(
                    "App không vượt qua chặn/xác minh và không dùng trình duyệt ẩn để chạy JavaScript. " +
                        "Phương án phù hợp: mở trang bằng trình duyệt hoặc ứng dụng chính thức của Hợp Âm Việt (có chế độ offline), " +
                        "hoặc xin phép bằng văn bản nếu cần lưu bản sao.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (s.fetch?.body != null) {
            SectionCard("3. So sánh với file HTML đã lưu") {
                Text("Chọn file .html bạn đã lưu từ trình duyệt. File chỉ được đọc trên máy, không gửi đi đâu.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { picker.launch(arrayOf("text/html", "application/xhtml+xml", "text/plain", "*/*")) },
                    enabled = !s.comparing) { Text(if (s.comparing) "Đang so sánh…" else "Chọn file HTML đã lưu") }
                s.savedFileName?.let { KV("File", it) }
                s.comparison?.let { ComparisonView(it) }
            }
        }

        if (s.fetch?.ok == true) {
            SectionCard("4. Lưu offline (hai bản độc lập)") {
                val key = s.songKey
                SaveRow("Nguyên trang (HTML server, không có CSS/JS)", s.pageStatus,
                    enabled = s.fetch?.body != null && s.pageStatus.state != SaveState.SAVING,
                    onSave = vm::savePage, onOpen = key?.let { k -> { onOpen(k, Version.PAGE) } })
                SaveRow("Lời/hợp âm gọn (.cho, giữ dòng trống & hợp âm inline)", s.compactStatus,
                    enabled = s.parsed?.hasLyrics == true && s.compactStatus.state != SaveState.SAVING,
                    onSave = vm::saveCompact, onOpen = key?.let { k -> { onOpen(k, Version.COMPACT) } })
                if (s.parsed?.hasLyrics != true) Text("Bản gọn không khả dụng vì HTML server không có lời.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ComparisonView(r: ComparisonReport) {
    if (r.sameSong == false) {
        Text(
            "⚠️ Hai file là HAI BÀI KHÁC NHAU — server: ${r.serverSong}, bản lưu: ${r.savedSong}. " +
                "Kết quả dưới đây không dùng để kết luận được. Hãy chọn file HTML lưu từ trình duyệt của CÙNG bài, " +
                "hoặc nhập URL của bài trong file đã lưu rồi tải lại.",
            color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
    } else if (r.sameSong == true) {
        Text("✓ Cùng một bài: ${r.serverSong}", style = MaterialTheme.typography.bodySmall)
    }
    KV("Kích thước", "server ${r.serverBytes / 1024} KB · bản lưu ${r.savedBytes / 1024} KB")
    KV("Lời giống hệt", when (r.lyricsIdentical) { true -> "Có (${r.serverLyricLines} dòng)"; false -> "Khác (server ${r.serverLyricLines} / lưu ${r.savedLyricLines} dòng)"; null -> "Không so được (một bên thiếu lời)" })
    Presence.entries.forEach { pr ->
        val rows = r.rows.filter { it.presence == pr }
        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(pr.label, fontWeight = FontWeight.Bold)
            rows.forEach { row ->
                Text("• ${row.label}: server=${row.server} · lưu=${row.saved}${if (row.differs && pr == Presence.BOTH) " (khác)" else ""}",
                    style = MaterialTheme.typography.bodySmall)
                Text("   ${row.selector}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun SaveRow(label: String, st: VersionStatus, enabled: Boolean, onSave: () -> Unit, onOpen: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(statusText(st), style = MaterialTheme.typography.bodySmall,
            color = if (st.state == SaveState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = enabled) { Text(if (st.state == SaveState.SAVED) "Lưu lại" else "Lưu") }
            if (onOpen != null && st.state == SaveState.SAVED) OutlinedButton(onClick = onOpen) { Text("Mở") }
        }
    }
}

fun statusText(st: VersionStatus): String = buildString {
    append(st.state.label)
    st.savedAtMillis?.let { append(" · ").append(AppViewModel.fmt(it)) }
    st.bytes?.let { append(" · ").append("%.1f".format(it / 1024.0)).append(" KB") }
    st.error?.let { append(" · lỗi gần nhất: ").append(it) }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$k: ", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(min = 110.dp))
        Text(v, style = MaterialTheme.typography.bodySmall)
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

@Composable
private fun ErrorCard(title: String, detail: String, onDismiss: (() -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    SectionCard(title) {
        Text("Chụp màn hình hoặc bấm Sao chép rồi gửi nội dung này để sửa lỗi.", style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Text(detail.take(4000), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(detail)) }) { Text("Sao chép") }
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Đã xem, xoá") }
        }
    }
}
