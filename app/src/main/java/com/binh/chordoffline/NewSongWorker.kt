package com.binh.chordoffline

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.binh.chordoffline.core.BatchQueue
import com.binh.chordoffline.core.ItemState
import com.binh.chordoffline.core.NewSongCheck
import com.binh.chordoffline.core.SongStore
import com.binh.chordoffline.net.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kiểm tra & lưu kết quả "bài mới" — dùng chung cho nút trong app và việc tự kiểm tra mỗi ngày.
 * Kết quả lần gần nhất lưu ở new_songs.tsv để app hiện lại danh sách khi mở từ thông báo.
 */
object NewSongs {
    private const val PREFS = "new_songs"
    private const val KEY_DAILY = "daily"
    private const val KEY_ASKED_NOTIF = "asked_notif"
    const val EXTRA_OPEN_TAB = "open_tab"

    fun file(ctx: Context) = File(ctx.filesDir, "new_songs.tsv")
    fun load(ctx: Context): NewSongCheck.Result? = runCatching { NewSongCheck.parse(file(ctx).readText()) }.getOrNull()
    fun save(ctx: Context, r: NewSongCheck.Result) {
        runCatching {
            val f = file(ctx); val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(NewSongCheck.serialize(r)); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** Bài đã có trong máy (bất kỳ bản nào) hoặc đã nằm trong hàng đợi (trừ mục đã huỷ). */
    fun knownChecker(store: SongStore, queuedKeys: Set<String>): (String) -> Boolean = { url ->
        BatchQueue.dedupeKey(url) in queuedKeys || store.hasAny(store.keyForUrl(url))
    }

    fun queuedKeysFromFile(ctx: Context): Set<String> = runCatching {
        BatchQueue.parse(File(ctx.filesDir, "batch_queue.tsv").readText()).items
            .filter { it.state != ItemState.CANCELLED }.mapTo(HashSet()) { BatchQueue.dedupeKey(it.url) }
    }.getOrDefault(emptySet())

    fun isDailyEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DAILY, true)

    fun setDailyEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DAILY, on).apply()
        schedule(ctx)
    }

    /** Bật: lên lịch mỗi ngày một lần, chỉ khi có Wi-Fi (KEEP: không tạo trùng). Tắt: huỷ lịch. */
    fun schedule(ctx: Context) {
        runCatching {
            val wm = WorkManager.getInstance(ctx)
            if (!isDailyEnabled(ctx)) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<NewSongWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    /** Hỏi quyền thông báo tối đa một lần (Android 13+) để báo bài mới. */
    fun shouldAskNotificationPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !isDailyEnabled(ctx)) return false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(KEY_ASKED_NOTIF, false)) return false
        p.edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
        return true
    }

    fun notifyNew(ctx: Context, count: Int, maybeGap: Boolean) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bài mới trên Hợp Âm Việt", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Báo khi Hợp Âm Việt có bài mới chưa lưu (kiểm tra mỗi ngày)" })
            val open = PendingIntent.getActivity(ctx, 7,
                Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, 2)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = "Có $count bài mới chưa lưu" + (if (maybeGap) " (có thể còn nhiều hơn)" else "") + " — chạm để xem và chọn tải."
            nm.notify(NOTIF_ID, android.app.Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Hợp Âm Việt có bài mới")
                .setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(open).build())
        }
    }

    private const val WORK = "new-songs-daily"
    private const val CHANNEL = "new_songs"
    private const val NOTIF_ID = 77
}

/** Việc chạy nền mỗi ngày: đọc trang "Hợp âm mới nhất" (thường 1–2 request), có bài mới thì báo. */
class NewSongWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!NewSongs.isDailyEnabled(ctx)) return@withContext Result.success()
        val store = SongStore(File(ctx.filesDir, "songs"))
        val fetcher = HttpFetcher()
        val previous = NewSongs.load(ctx)?.newSongs?.mapTo(HashSet()) { BatchQueue.dedupeKey(it.url) } ?: emptySet()
        val r = NewSongCheck.run(
            fetch = { u -> fetcher.fetch(u).let { NewSongCheck.Fetched(it.status, it.body, it.error) } },
            isKnown = NewSongs.knownChecker(store, NewSongs.queuedKeysFromFile(ctx)),
            pause = { Thread.sleep(2_000) },
        )
        if (r.error != null && r.newSongs.isEmpty()) {
            // lỗi mạng: thử lại sau (WorkManager tự giãn thời gian); website chặn/giới hạn: KHÔNG thử lại hôm nay
            return@withContext if (r.error.startsWith("Không tải được")) Result.retry() else Result.success()
        }
        NewSongs.save(ctx, r)
        // chỉ báo khi có bài mới CHƯA từng báo (không làm phiền mỗi ngày với cùng danh sách)
        if (r.newSongs.any { BatchQueue.dedupeKey(it.url) !in previous }) NewSongs.notifyNew(ctx, r.newSongs.size, r.maybeGap)
        Result.success()
    }
}
