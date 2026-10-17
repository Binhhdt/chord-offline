package com.binh.chordoffline.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.binh.chordoffline.core.BatchItem
import com.binh.chordoffline.core.DownloadConfig
import com.binh.chordoffline.core.ThroughputMeter
import com.binh.chordoffline.core.ItemState
import com.binh.chordoffline.core.ListingPolicy
import com.binh.chordoffline.core.PacePolicy
import com.binh.chordoffline.core.ScanProgressBook
import com.binh.chordoffline.core.Version

/**
 * Màn "Tải nhiều": lấy danh sách từ trang danh mục (xem trước, chọn, xác nhận) hoặc dán link / nhập TXT.
 * Tải song song có kiểm soát (mặc định 3 luồng, tổng tối đa ~3 request/giây); bài đã lưu thành công không tải lại.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BatchScreen(vm: AppViewModel, modifier: Modifier, onOpen: (String, Version) -> Unit) {
    val b by vm.batch.collectAsState()
    val d by vm.discover.collectAsState()
    val check by vm.check.collectAsState()
    val progress by vm.progress.collectAsState()
    val auto by vm.auto.collectAsState()

    var mode by rememberSaveable { mutableIntStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var showPreview by rememberSaveable { mutableStateOf(true) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val q = b.queue
    val context = LocalContext.current
    val txtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.batchImportTxt(uri, fileName(context, uri))
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Thanh tiến độ gọn luôn dính ở đầu màn hình khi có hàng đợi -> cuộn tới đâu cũng thấy
        if (q.total > 0) stickyHeader(key = "mini-progress") { MiniProgress(vm, b, check.confirmed) }
        item {
            Text("Tải nhiều bài", style = MaterialTheme.typography.titleLarge)
            Text("Tải song song ${vm.workers.collectAsState().value} luồng, tổng tối đa ~${DownloadConfig.MAX_REQUESTS_PER_SECOND.toInt()} request/giây. " +
                "Website báo bận (429/502/503/504) thì tự giảm luồng và thử lại chậm dần; gặp chặn (403, trang xác minh) thì dừng hẳn — " +
                "app không vượt CAPTCHA, đăng nhập hay biện pháp bảo vệ của website.",
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = check.confirmed, onCheckedChange = vm::setConfirmed, enabled = !b.running)
                Text("Tôi có quyền lưu các bài này, chỉ dùng cá nhân/phi thương mại, giữ ghi nguồn và không chia sẻ lại.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Từ trang danh mục") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Dán link / TXT") })
            }
        }

        if (mode == 0) {
            discoverScanCard(vm, d, b.running)
            item { AutoCard(vm, auto, d, b, check.confirmed) }
            if (progress.all.isNotEmpty()) item { ScanHistoryCard(vm, progress, d.url) }
            item { SaveOptionsCard(vm, b) }
            if (d.items.isNotEmpty()) item { PreviewHeader(vm, d, b.running, showPreview) { showPreview = !showPreview } }
            // Tiến độ ngay dưới nút xác nhận
            item { ProgressCard(vm, b, check.confirmed) }
            if (showPreview) previewItems(vm, d)
        } else item {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("Mỗi dòng một link https://hopamviet.vn/chord/song/…") },
                minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { val r = vm.batchAdd(input); if (r.invalid.isEmpty()) input = "" }, enabled = input.isNotBlank()) {
                    Text("Thêm vào hàng đợi")
                }
                OutlinedButton(onClick = { txtPicker.launch(arrayOf("text/plain", "text/*", "*/*")) }) { Text("Nhập file TXT") }
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

        if (mode == 1) {
            item { SaveOptionsCard(vm, b) }
            item { ProgressCard(vm, b, check.confirmed) }
        }
        if (q.total > 0) item {
            TextButton(onClick = { showQueue = !showQueue }) {
                Text(if (showQueue) "▲ Ẩn danh sách hàng đợi" else "▼ Xem danh sách hàng đợi (${q.total} bài)")
            }
        }
        if (showQueue) items(q.items, key = { it.url }) { item -> BatchRow(item, b.running && item.url in b.currentUrls, onOpen) }
    }
}

@Composable
private fun SaveOptionsCard(vm: AppViewModel, b: BatchUi) {
    val q = b.queue
    SectionCard("Kiểu lưu") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = q.saveCompact, onCheckedChange = { vm.batchOptions(it, q.savePage) }, enabled = !b.running)
            Text("Lời/hợp âm gọn (.cho, vài KB/bài)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = q.savePage, onCheckedChange = { vm.batchOptions(q.saveCompact, it) }, enabled = !b.running)
            Text("Nguyên trang HTML (~300 KB/bài)")
        }
        Text("Bài đã có đủ các bản đã chọn sẽ được bỏ qua, không tải lại.", style = MaterialTheme.typography.labelSmall)
        val workers by vm.workers.collectAsState()
        Text("Số luồng tải cùng lúc", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DownloadConfig.WORKER_OPTIONS.forEach { o ->
                FilterChip(selected = workers == o, onClick = { vm.setWorkers(o) }, enabled = !b.running,
                    label = { Text(if (o == DownloadConfig.MAX_WORKERS) "$o (mặc định)" else "$o") })
            }
        }
        val extra by vm.extraDelay.collectAsState()
        Text("Nghỉ thêm sau mỗi bài (mỗi luồng)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DownloadConfig.DELAY_OPTIONS.forEach { o ->
                FilterChip(selected = extra == o, onClick = { vm.setExtraDelay(o) }, enabled = !b.running,
                    label = { Text(if (o == 0L) "0" else PacePolicy.label(o)) })
            }
        }
        Text("Dù chọn bao nhiêu luồng, tổng số request luôn ≤ ~${DownloadConfig.MAX_REQUESTS_PER_SECOND.toInt()}/giây. " +
            "Website bận thì app tự giảm luồng, chờ 1 → 2 → 4 giây (theo Retry-After nếu có) rồi thử lại tối đa ${DownloadConfig.MAX_RETRIES} lần.",
            style = MaterialTheme.typography.labelSmall)
        val ms by vm.delayMs.collectAsState()
        Text("Nghỉ giữa các trang khi quét danh mục", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PacePolicy.OPTIONS.forEach { o ->
                FilterChip(selected = ms == o, onClick = { vm.setDelay(o) }, label = { Text(PacePolicy.label(o)) })
            }
        }
        Text("Quét danh mục vẫn đi tuần tự từng trang. Website trả lời chậm thì app tự nghỉ lâu hơn.",
            style = MaterialTheme.typography.labelSmall)
    }
}

/** Thanh tiến độ gọn dính trên cùng: số bài, % , thời gian còn lại ước tính, nút Tạm dừng/Tiếp tục. */
@Composable
private fun MiniProgress(vm: AppViewModel, b: BatchUi, confirmed: Boolean) {
    val q = b.queue
    val done = q.count(ItemState.DONE); val err = q.count(ItemState.ERROR)
    val left = q.count(ItemState.PENDING) + q.count(ItemState.RUNNING)
    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (b.running) "⬇ Đang tải " else "⏸ Hàng đợi ") + "${q.finished}/${q.total}",
                        fontWeight = FontWeight.Bold)
                    Text("✅ $done  ❌ $err  ⏳ $left" +
                        if (b.running) " · ${ThroughputMeter.formatRate(b.rate)} · còn ${ThroughputMeter.formatEta(b.etaSec)}" else "",
                        style = MaterialTheme.typography.bodySmall)
                }
                when {
                    b.running -> OutlinedButton(onClick = vm::batchPause, enabled = !b.pauseRequested) { Text("Tạm dừng") }
                    q.hasPending -> Button(onClick = vm::batchStart, enabled = confirmed) { Text("Tiếp tục") }
                }
            }
            LinearProgressIndicator(progress = { if (q.total == 0) 0f else q.finished.toFloat() / q.total },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

private fun LazyListScope.discoverScanCard(vm: AppViewModel, d: DiscoverUi, batchRunning: Boolean) {
    item {
        SectionCard("Lấy danh sách từ trang danh mục") {
            Text("Dán link trang thể loại, nhạc sĩ, ca sĩ, điệu, bài hot hoặc bài mới. Trang tìm kiếm bị robots.txt của website chặn nên không hỗ trợ.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = d.url, onValueChange = vm::setDiscoverUrl, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("URL trang danh sách") }, enabled = !d.running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Số trang tối đa:")
                TextButton(onClick = { vm.setDiscoverPages(d.maxPages - 1) }, enabled = !d.running && d.maxPages > 1) { Text("−") }
                Text("${d.maxPages}", fontWeight = FontWeight.Bold)
                TextButton(onClick = { vm.setDiscoverPages(d.maxPages + 1) },
                    enabled = !d.running && d.maxPages < ListingPolicy.MAX_PAGES_LIMIT) { Text("+") }
                Text("(≈${d.maxPages * 30} bài, tối đa ${ListingPolicy.MAX_PAGES_LIMIT})", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::discoverStart, enabled = !d.running && !batchRunning) { Text("Quét danh sách") }
                OutlinedButton(onClick = vm::discoverStop, enabled = d.running) { Text("Dừng quét") }
            }
            if (d.lastScanned > 0) Text("Đã quét tới trang ${d.lastScanned}${d.totalPages?.let { "/$it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            val resume = d.resumePage
            if (resume != null && !d.running) {
                Button(onClick = vm::discoverContinue, enabled = !batchRunning, modifier = Modifier.fillMaxWidth()) {
                    val end = d.totalPages?.let { minOf(it, resume + d.maxPages - 1) } ?: (resume + d.maxPages - 1)
                    Text("Quét tiếp ${d.maxPages} trang (trang $resume–$end${d.totalPages?.let { "/$it" } ?: ""})")
                }
            }
            if (d.reachedEnd && !d.running) Text("✅ Đã tới trang cuối — hết trang.", color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold)
            if (d.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(d.currentPage?.let { "Đang tải trang: $it" } ?: "Nghỉ giữa các request… còn ${d.waitSeconds} giây",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            d.log.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            d.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (d.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Phần đầu khung xem trước: số bài, chọn/bỏ chọn, nút Xác nhận & tải, nút ẩn/hiện danh sách. */
@Composable
private fun PreviewHeader(vm: AppViewModel, d: DiscoverUi, batchRunning: Boolean, showList: Boolean, onToggle: () -> Unit) {
    SectionCard("Xem trước: ${d.items.size} bài · đã chọn ${d.selectedCount}") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { vm.discoverSelect(true) }) { Text("Chọn tất cả") }
            TextButton(onClick = { vm.discoverSelect(false) }) { Text("Bỏ chọn") }
            TextButton(onClick = { vm.discoverSelect(null) }) { Text("Chỉ bài chưa lưu") }
        }
        Text("Bài đã lưu hoặc đã có trong hàng đợi được bỏ chọn sẵn.", style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::discoverConfirm, enabled = !d.running && !batchRunning && d.selectedCount > 0) {
                Text("Xác nhận & tải ${d.selectedCount} bài")
            }
            TextButton(onClick = onToggle) { Text(if (showList) "▲ Ẩn danh sách" else "▼ Hiện danh sách") }
        }
    }
}

private fun LazyListScope.previewItems(vm: AppViewModel, d: DiscoverUi) {
        items(d.items, key = { "found:" + it.song.url }) { fi ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !d.running) { vm.discoverToggle(fi.song.url) },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = fi.selected, onCheckedChange = { _ -> vm.discoverToggle(fi.song.url) }, enabled = !d.running)
                Column(Modifier.weight(1f)) {
                    Text(fi.song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val tags = listOfNotNull(fi.song.artist, if (fi.alreadySaved) "✓ đã lưu" else null, if (fi.inQueue) "trong hàng đợi" else null)
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
}

@Composable
private fun ProgressCard(vm: AppViewModel, b: BatchUi, confirmed: Boolean) {
    val q = b.queue
    SectionCard("Tiến độ hàng đợi") {
        val done = q.count(ItemState.DONE); val err = q.count(ItemState.ERROR)
        val pending = q.count(ItemState.PENDING) + q.count(ItemState.RUNNING); val cancelled = q.count(ItemState.CANCELLED)
        if (b.running) Text("Đang tải ${q.finished}/${q.total}", fontWeight = FontWeight.Bold)
        Text("Thành công $done · Thất bại $err · Đang chờ $pending · Huỷ $cancelled", fontWeight = FontWeight.SemiBold)
        if (q.total > 0) LinearProgressIndicator(progress = { q.finished.toFloat() / q.total }, modifier = Modifier.fillMaxWidth())
        if (b.running) {
            Text("Tốc độ: ${ThroughputMeter.formatRate(b.rate)} · ETA: ${ThroughputMeter.formatEta(b.etaSec)}",
                style = MaterialTheme.typography.bodyMedium)
            val workers by vm.workers.collectAsState()
            Text("Luồng: ${b.workerLimit}/$workers" +
                (if (b.workerLimit in 1 until workers) " (đã tự giảm vì website báo bận)" else "") +
                (if (b.throttles > 0) " · website báo bận ${b.throttles} lần" else ""),
                style = MaterialTheme.typography.bodySmall)
            b.currentUrls.take(DownloadConfig.HARD_MAX_WORKERS).forEach { u ->
                Text("⬇ $u", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        b.retryOnly?.let { Text("Chế độ thử lại: chỉ tải lại $it bài lỗi, không đụng các bài đang chờ khác.",
            style = MaterialTheme.typography.bodySmall) }
        if (b.postPending > 0) Text("Đang lưu ảnh YouTube & sao lưu nền: còn ${b.postPending} bài",
            style = MaterialTheme.typography.labelSmall)
        if (b.pauseRequested) Text("Sẽ tạm dừng sau các bài đang tải…", style = MaterialTheme.typography.bodySmall)
        b.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (it.startsWith("Website")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::batchStart, enabled = !b.running && q.hasPending && confirmed) {
                Text(if (q.finished > 0) "Tiếp tục" else "Bắt đầu")
            }
            OutlinedButton(onClick = vm::batchPause, enabled = b.running && !b.pauseRequested) { Text("Tạm dừng") }
            OutlinedButton(onClick = vm::batchCancel, enabled = b.running || q.hasPending) { Text("Huỷ") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = vm::batchRetryFailed,
                enabled = !b.running && (err + cancelled) > 0 && confirmed) { Text("Thử lại lỗi ($err+$cancelled)") }
            TextButton(onClick = vm::batchClearDone, enabled = !b.running && done > 0) { Text("Bỏ mục xong") }
            TextButton(onClick = vm::batchClearAll, enabled = !b.running && q.total > 0) { Text("Xoá hết") }
        }
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

private fun fileName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/** Danh sách các danh mục đã quét + đã tới trang mấy (lưu cả sau khi tắt/cài lại app). Bấm để quét tiếp. */
@Composable
private fun ScanHistoryCard(vm: AppViewModel, book: ScanProgressBook, currentUrl: String) {
    var open by rememberSaveable { mutableStateOf(true) }
    SectionCard("Tiến độ các danh mục đã quét") {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
            Text(if (open) "▲ Thu gọn" else "▼ Xem ${book.all.size} danh mục")
        }
        if (open) book.all.forEach { p ->
            val isCurrent = runCatching { ListingPolicy.listKey(currentUrl) == p.listKey }.getOrDefault(false)
            Row(Modifier.fillMaxWidth().clickable { vm.setDiscoverUrl(p.url) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.label, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Đã quét tới trang ${p.lastScanned}${p.totalPages?.let { "/$it" } ?: ""}" +
                            when { p.reachedEnd -> " · ✅ hết trang"; p.resumePage != null -> " · quét tiếp từ trang ${p.resumePage}"; else -> "" } +
                            " · ${AppViewModel.fmt(p.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(if (isCurrent) "đang chọn" else "Chọn ›", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        Text("Chọn một danh mục rồi bấm \"Quét tiếp\" ở trên. Tiến độ được lưu trong app và sao lưu cùng thư mục lưu file.",
            style = MaterialTheme.typography.labelSmall)
    }
}

/** Nút chạy tự động: quét N trang → tải bài chưa lưu → quét tiếp… tới trang cuối. */
@Composable
private fun AutoCard(vm: AppViewModel, a: AutoUi, d: DiscoverUi, b: BatchUi, confirmed: Boolean) {
    SectionCard("Tự động quét & tải đến hết") {
        Text("Lặp lại: quét ${d.maxPages} trang → tự thêm bài chưa lưu → tải xong → quét tiếp. Dừng khi tới trang cuối, " +
            "khi bạn bấm Dừng, hoặc khi website giới hạn. Màn hình được giữ sáng trong lúc chạy; nên cắm sạc.",
            style = MaterialTheme.typography.bodySmall)
        if (a.active) {
            Text("Đang chạy · ${a.phase}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("Đã thêm ${a.totalQueued} bài qua ${a.round} vòng", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = vm::autoStop, modifier = Modifier.fillMaxWidth()) { Text("Dừng tự động") }
        } else {
            val from = d.resumePage?.let { "từ trang $it" } ?: if (d.reachedEnd) "đã hết trang" else "từ trang 1"
            Button(onClick = vm::autoStart, enabled = confirmed && !d.running && !b.running && !d.reachedEnd,
                modifier = Modifier.fillMaxWidth()) { Text("▶ Tự động quét & tải đến hết ($from)") }
        }
        a.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (a.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
