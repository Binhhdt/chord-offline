package com.binh.chordoffline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Dịch vụ tiền cảnh: giữ app chạy khi tắt màn hình / chuyển sang app khác trong lúc quét hoặc tải,
 * hiện tiến độ trên thanh thông báo. Việc tải thật vẫn do ViewModel làm (tuần tự, có nghỉ giữa các request).
 * Vuốt tắt app khỏi danh sách đa nhiệm thì dừng; trạng thái hàng đợi vẫn được lưu để tải tiếp.
 */
class DownloadService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChordOffline:download")
            .apply { setReferenceCounted(false); acquire(10L * 60 * 60 * 1000) }   // tối đa 10 giờ
    }

    /**
     * LUÔN gọi startForeground() trước tiên (Android bắt buộc trong vài giây sau startForegroundService),
     * kể cả khi app đã muốn dừng — dừng NGAY SAU đó. Trước v0.10, app có thể gọi stopService() trước khi
     * dịch vụ kịp startForeground -> Android 15 báo ForegroundServiceDidNotStartInTimeException và đóng app.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, intent?.getStringExtra(EXTRA_TITLE) ?: "Đang tải hợp âm", intent?.getStringExtra(EXTRA_TEXT) ?: "",
            intent?.getIntExtra(EXTRA_DONE, 0) ?: 0, intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        }
        synchronized(lock) {
            started = true
            if (stopWanted) { stopWanted = false; stopSelf() }
        }
        return START_NOT_STICKY
    }

    /** Android 15 giới hạn thời gian dịch vụ dataSync: hết giờ thì tự dừng (hàng đợi vẫn lưu, mở app để tiếp tục). */
    override fun onTimeout(startId: Int) { stopSelf() }

    override fun onDestroy() {
        synchronized(lock) { requested = false; started = false; stopWanted = false }
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "download"
        private const val ID = 42
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"
        private val lock = Any()
        /** Đã gọi startForegroundService (dịch vụ đang/sắp chạy). */
        @Volatile private var requested = false
        /** Dịch vụ đã gọi startForeground() — từ lúc này mới được stopService(). */
        @Volatile private var started = false
        /** App muốn dừng trước khi dịch vụ kịp startForeground -> dịch vụ tự dừng ngay sau khi startForeground. */
        @Volatile private var stopWanted = false
        val running: Boolean get() = requested

        private fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tải hợp âm", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Tiến độ quét danh mục và tải bài" })
        }

        private fun build(ctx: Context, title: String, text: String, done: Int, total: Int): Notification {
            channel(ctx)
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(total, done.coerceAtMost(total), total == 0)
                .setContentIntent(open)
                .build()
        }

        /** Hiện/cập nhật thông báo; lần đầu thì khởi động dịch vụ tiền cảnh. */
        fun show(ctx: Context, title: String, text: String, done: Int, total: Int) {
            synchronized(lock) {
                if (!requested) {
                    val i = Intent(ctx, DownloadService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
                        .putExtra(EXTRA_DONE, done).putExtra(EXTRA_TOTAL, total)
                    try { ctx.startForegroundService(i); requested = true; stopWanted = false }
                    catch (e: Exception) { /* app đang ở nền / hết hạn mức dataSync: bỏ qua, việc tải vẫn chạy khi app mở */ }
                    return
                }
                stopWanted = false              // đang chờ dừng mà lại có việc -> huỷ lệnh dừng
                if (!started) return            // dịch vụ chưa kịp startForeground: thông báo sẽ hiện khi nó chạy
            }
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, title, text, done, total)) }
        }

        fun stop(ctx: Context) {
            synchronized(lock) {
                if (!requested) return
                if (started) {
                    runCatching { ctx.stopService(Intent(ctx, DownloadService::class.java)) }
                    requested = false; started = false
                } else stopWanted = true        // chưa startForeground: KHÔNG stopService lúc này (sẽ làm app bị đóng)
            }
        }
    }
}
