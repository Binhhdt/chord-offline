package com.binh.chordoffline.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.binh.chordoffline.core.BatchItem
import com.binh.chordoffline.core.ItemState
import com.binh.chordoffline.core.Version

/** Màn "Tải nhiều": mỗi dòng một link, tải tuần tự, nghỉ 3 giây giữa các bài. */
@Composable
fun BatchScreen(vm: AppViewModel, modifier: Modifier, onOpen: (String, Version) -> Unit) {
    val b by vm.batch.collectAsState()
    val check by vm.check.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val q = b.queue

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Tải nhiều bài", style = MaterialTheme.typography.titleLarge)
            Text("Tải tuần tự, nghỉ ${AppViewModel.BATCH_DELAY_MS / 1000} giây giữa các bài. Gặp giới hạn/chặn (403, 429, 503, trang xác minh) " +
                "thì tự tạm dừng — app không vượt CAPTCHA, đăng nhập hay giới hạn của website.",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = check.confirmed, onCheckedChange = vm::setConfirmed, enabled = !b.running)
                Text("Tôi có quyền lưu các bài này, chỉ dùng cá nhân/phi thương mại, giữ ghi nguồn và không chia sẻ lại.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("Mỗi dòng một link https://hopamviet.vn/chord/song/…") },
                minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { val r = vm.batchAdd(input); if (r.invalid.isEmpty()) input = "" }, enabled = input.isNotBlank()) {
                    Text("Thêm vào hàng đợi")
                }
                if (input.isNotBlank()) TextButton(onClick = { input = "" }) { Text("Xoá ô nhập") }
            }
            b.lastAdd?.let { r ->
                Text("Đã thêm ${r.added} · trùng bỏ qua ${r.duplicates} · không hợp lệ ${r.invalid.size}",
                    style = MaterialTheme.typography.bodySmall)
                r.invalid.take(5).forEach { (line, why) ->
                    Text("✗ $line — $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (r.invalid.size > 5) Text("… và ${r.invalid.size - 5} dòng khác", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard("Kiểu lưu") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = q.saveCompact, onCheckedChange = { vm.batchOptions(it, q.savePage) }, enabled = !b.running)
                    Text("Lời/hợp âm gọn (.cho, vài KB/bài)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = q.savePage, onCheckedChange = { vm.batchOptions(q.saveCompact, it) }, enabled = !b.running)
                    Text("Nguyên trang HTML (~300 KB/bài)")
                }
            }
        }
        item {
            SectionCard("Tiến độ") {
                val done = q.count(ItemState.DONE); val err = q.count(ItemState.ERROR)
                val pending = q.count(ItemState.PENDING) + q.count(ItemState.RUNNING); val cancelled = q.count(ItemState.CANCELLED)
                Text("Tổng ${q.total} · Xong $done · Lỗi $err · Chờ $pending · Huỷ $cancelled", fontWeight = FontWeight.SemiBold)
                if (q.total > 0) LinearProgressIndicator(progress = { q.finished.toFloat() / q.total }, modifier = Modifier.fillMaxWidth())
                when {
                    b.running && b.currentUrl != null -> Text("Đang tải: ${b.currentUrl}", style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    b.running && b.waitSeconds > 0 -> Text("Nghỉ giữa các request… còn ${b.waitSeconds} giây", style = MaterialTheme.typography.bodySmall)
                }
                if (b.pauseRequested) Text("Sẽ tạm dừng sau bài đang tải…", style = MaterialTheme.typography.bodySmall)
                b.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Website")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::batchStart, enabled = !b.running && q.hasPending && check.confirmed) {
                        Text(if (q.finished > 0) "Tiếp tục" else "Bắt đầu")
                    }
                    OutlinedButton(onClick = vm::batchPause, enabled = b.running && !b.pauseRequested) { Text("Tạm dừng") }
                    OutlinedButton(onClick = vm::batchCancel, enabled = b.running || q.hasPending) { Text("Huỷ") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = vm::batchRetryFailed,
                        enabled = !b.running && (err + cancelled) > 0 && check.confirmed) { Text("Thử lại lỗi ($err+$cancelled)") }
                    TextButton(onClick = vm::batchClearDone, enabled = !b.running && done > 0) { Text("Bỏ mục xong") }
                    TextButton(onClick = vm::batchClearAll, enabled = !b.running && q.total > 0) { Text("Xoá hết") }
                }
            }
        }
        items(q.items, key = { it.url }) { item -> BatchRow(item, b.running && b.currentUrl == item.url, onOpen) }
    }
}

@Composable
private fun BatchRow(item: BatchItem, current: Boolean, onOpen: (String, Version) -> Unit) {
    val icon = when (item.state) {
        ItemState.PENDING -> "⏳"; ItemState.RUNNING -> "🔄"; ItemState.DONE -> "✅"; ItemState.ERROR -> "❌"; ItemState.CANCELLED -> "⛔"
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text("$icon ${item.state.label}${if (item.attempts > 1) " · lần ${item.attempts}" else ""}" +
                (item.title?.let { " · $it" } ?: ""), fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold)
            Text(item.url, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (item.state == ItemState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.state == ItemState.DONE && item.key != null) {
                TextButton(onClick = { onOpen(item.key, Version.COMPACT) }, contentPadding = PaddingValues(0.dp)) { Text("Mở bản gọn") }
            }
        }
    }
}
